package dev.pocketpaas.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.domain.dashboard.DashboardActions
import dev.pocketpaas.domain.dashboard.DashboardDevice
import dev.pocketpaas.domain.dashboard.DashboardNetworkState
import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.dashboard.DashboardOperationState
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.SiteCollectionState
import dev.pocketpaas.domain.model.SiteInput
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.remote.ApiKeyStore
import dev.pocketpaas.domain.remote.RemoteDeployRequest
import dev.pocketpaas.domain.remote.RemoteServerRuntime
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.repository.ActivityRepository
import dev.pocketpaas.domain.repository.DeploymentHistoryRepository
import dev.pocketpaas.domain.repository.SiteRepository
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshManager
import dev.pocketpaas.domain.usecase.InitializePlatform
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val sites: SiteRepository,
    private val runtime: RemoteServerRuntime,
    network: DashboardNetworkState,
    private val actions: DashboardActions,
    private val device: DashboardDevice,
    private val apiKeys: ApiKeyStore,
    private val initializePlatform: InitializePlatform,
    private val deploymentPolicy: DeploymentPolicy,
    private val sshManager: SshManager? = null,
    private val managedServices: ManagedServices? = null,
    private val deploymentHistory: DeploymentHistoryRepository? = null,
    private val activityRepository: ActivityRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = mutableState.asStateFlow()
    private var initialization: Job? = null
    private var zipSelection: Job? = null
    private var zipGeneration = 0L
    private var noticeId = 0L
    private var handledOperation = 0L
    private var operation = DashboardOperationState()

    init {
        viewModelScope.launch {
            sites.state.collect { collection ->
                mutableState.update { previous ->
                    when (collection) {
                        SiteCollectionState.Loading -> previous.copy(loadingSites = true, siteReadFailed = false)
                        is SiteCollectionState.Ready -> previous.copy(
                            sites = collection.sites, loadingSites = false, siteReadFailed = false,
                        )
                        is SiteCollectionState.Failed -> previous.copy(
                            sites = collection.lastKnownSites, loadingSites = false, siteReadFailed = true,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            runtime.state.collect { server ->
                val oldStatus = state.value.runtime.status
                mutableState.update { it.copy(runtime = server) }
                refreshProgress()
                if (server.status == RemoteServerStatus.FAILED && oldStatus != server.status) {
                    notify("Sunucu başlatılamadı veya temizliği tamamlanamadı. Termux ayarlarını kontrol edip Durdur ile yeniden deneyin.")
                }
            }
        }
        viewModelScope.launch {
            network.state.collect { connection ->
                val old = state.value.network
                mutableState.update { it.copy(network = connection) }
                refreshProgress()
                if ((connection.nginxStatus == NetworkServiceStatus.FAILED && old.nginxStatus != connection.nginxStatus) ||
                    (connection.tunnelStatus == NetworkServiceStatus.FAILED && old.tunnelStatus != connection.tunnelStatus)
                ) notify("Nginx veya tünel bağlantısı kurulamadı. Ağ ve Termux paketlerini kontrol edip bağlantıyı yeniden deneyin.")
            }
        }
        viewModelScope.launch {
            actions.operation.collect { next ->
                operation = next
                refreshProgress()
                if (!next.running && next.id > handledOperation) {
                    handledOperation = next.id
                    next.message?.let(::notify)
                    if (!next.failed && !next.cancelled && next.kind == DashboardOperationKind.DEPLOY) {
                        mutableState.update { it.copy(draft = ProjectDraft(), selectedSiteId = next.siteId ?: it.selectedSiteId) }
                    }
                }
            }
        }
        sshManager?.let { manager ->
            viewModelScope.launch {
                manager.state.collect { ssh -> mutableState.update { it.copy(ssh = ssh) } }
            }
        }
        managedServices?.let { services ->
            viewModelScope.launch {
                services.services.collect { list -> mutableState.update { it.copy(managedServices = list) } }
            }
        }
        deploymentHistory?.let { history ->
            viewModelScope.launch {
                history.observeRecent(20).collect { records -> mutableState.update { it.copy(deployments = records) } }
            }
        }
        activityRepository?.let { repository ->
            viewModelScope.launch {
                repository.observeEvents(100).collect { events -> mutableState.update { it.copy(activity = events) } }
            }
        }
        refresh()
    }

    fun refresh() {
        if (initialization?.isActive == true) return
        initialization = viewModelScope.launch {
            mutableState.update { it.copy(initializing = true, setupFailed = false) }
            try {
                val platform = initializePlatform()
                mutableState.update { it.copy(
                    initializing = false, setupFailed = false, capabilities = platform.capabilities,
                    apiPort = platform.apiPort, nginxPort = platform.nginxPort,
                ) }
                runCatching { sshManager?.refresh() }
                runCatching { managedServices?.refresh() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(initializing = false, setupFailed = true) }
            }
        }
    }

    // --- Server / project actions (unchanged) ---

    fun masterSwitch(enabled: Boolean) {
        if (!enabled) {
            perform { actions.stopServer() }
            return
        }
        val current = state.value
        if (!current.setupReady || current.setupFailed) {
            notify("Önce Termux kurulumunu ve komut iznini tamamlayın.")
            return
        }
        if (current.runtime.status != RemoteServerStatus.STOPPED || current.operationBusy) {
            notify("Sunucu durumunun hazır olmasını bekleyin; hata durumunda önce Durdur'a dokunun.")
            return
        }
        perform { actions.startServer(current.lanAccess) }
    }

    fun setLanAccess(enabled: Boolean) {
        if (state.value.runtime.status == RemoteServerStatus.STOPPED && !state.value.operationBusy) {
            mutableState.update { it.copy(lanAccess = enabled) }
        }
    }

    fun retryNetwork() {
        if (state.value.serverRunning && !state.value.operationBusy) perform(actions::retryNetwork)
    }

    fun showAddProject() {
        if (!state.value.canManageSites) {
            notify("Proje eklemek için sunucuyu başlatın ve devam eden işlemin bitmesini bekleyin.")
            return
        }
        mutableState.update { it.copy(showAddProject = true, draft = it.draft.copy(error = null)) }
    }

    fun dismissAddProject() { mutableState.update { it.copy(showAddProject = false) } }
    fun setDomain(value: String) {
        mutableState.update { it.copy(draft = it.draft.copy(domainName = value.take(1024), error = null)) }
    }
    fun setProjectType(value: ProjectType) {
        mutableState.update { it.copy(draft = it.draft.copy(projectType = value, error = null)) }
    }

    fun selectZip(uri: String) {
        zipSelection?.cancel()
        val generation = ++zipGeneration
        mutableState.update { it.copy(draft = it.draft.copy(zipUri = null, zipName = "ZIP bilgisi okunuyor…", error = null)) }
        zipSelection = viewModelScope.launch {
            try {
                val archive = device.describeArchive(uri)
                require(archive.uri.startsWith("content://"))
                require(archive.bytes == null || archive.bytes in 1..deploymentPolicy.maximumArchiveBytes)
                if (generation == zipGeneration) mutableState.update {
                    it.copy(draft = it.draft.copy(zipUri = archive.uri, zipName = archive.name, error = null))
                }
            } catch (_: TimeoutCancellationException) {
                if (generation == zipGeneration) zipError("Dosya bilgisi zamanında okunamadı. ZIP'i yeniden seçin.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == zipGeneration) zipError("ZIP okunamadı veya 64 MiB sınırını aşıyor. Başka bir dosya seçin.")
            }
        }
    }

    fun deploy() {
        val current = state.value
        if (!current.canManageSites) {
            notify("Sunucu hazır değil veya başka bir işlem sürüyor.")
            return
        }
        val domain = try { SiteInput.normalizeDomainName(current.draft.domainName) }
        catch (_: IllegalArgumentException) { draftError("Geçerli bir alan adı girin; örneğin demo.example."); return }
        val uri = current.draft.zipUri ?: run { draftError("Bir ZIP dosyası seçin."); return }
        val existing = current.sites.firstOrNull { it.domainName == domain }
        if (existing != null && (existing.projectType != current.draft.projectType ||
                existing.status !in setOf(SiteStatus.STOPPED, SiteStatus.FAILED) || existing.processId != null)
        ) {
            draftError("Bu alan adı kullanımda. Mevcut siteyi durdurun ve aynı proje türünü seçin.")
            return
        }
        val request = if (existing == null) RemoteDeployRequest(domainName = domain, projectType = current.draft.projectType)
            else RemoteDeployRequest(siteId = existing.id)
        perform {
            actions.deploy(request, uri)
            mutableState.update { it.copy(showAddProject = false) }
        }
    }

    fun selectSite(id: Long) {
        if (state.value.sites.any { it.id == id }) mutableState.update { it.copy(selectedSiteId = id) }
    }
    fun dismissLogs() { mutableState.update { it.copy(selectedSiteId = null) } }
    fun startSite(id: Long) { if (state.value.canManageSites) perform { actions.startSite(id) } }
    fun stopSite(id: Long) { if (state.value.canManageSites) perform { actions.stopSite(id) } }
    fun cancelOperation() {
        if (operation.running) perform(actions::cancelOperation)
        else if (state.value.operationBusy) perform(actions::stopServer)
    }

    fun copyApiKey() {
        viewModelScope.launch {
            try {
                device.copySensitive(apiKeys.token())
                notify("API anahtarı panoya kopyalandı.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notify("API anahtarı kopyalanamadı. Uygulama depolamasını kontrol edin.")
            }
        }
    }

    // --- SSH ---

    fun showSshSheet() { mutableState.update { it.copy(showSshSheet = true, sshDraft = SshDraft()) } }
    fun dismissSshSheet() { mutableState.update { it.copy(showSshSheet = false) } }
    fun setSshKeyName(value: String) { mutableState.update { it.copy(sshDraft = it.sshDraft.copy(keyName = value.take(256), error = null)) } }
    fun setSshPublicKey(value: String) { mutableState.update { it.copy(sshDraft = it.sshDraft.copy(publicKey = value.take(8192), error = null)) } }

    fun toggleSsh(enabled: Boolean) {
        val manager = sshManager ?: run { notify("SSH yöneticisi hazır değil."); return }
        viewModelScope.launch {
            try {
                if (enabled) manager.start() else manager.stop()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH işlemi başarısız.") }
        }
    }

    fun setSshPort(port: Int) {
        val manager = sshManager ?: run { notify("SSH yöneticisi hazır değil."); return }
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                manager.updateSettings(current.copy(port = port))
                notify("SSH portu :$port olarak güncellendi.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH portu güncellenemedi.") }
        }
    }

    fun setSshPasswordAuth(enabled: Boolean) {
        val manager = sshManager ?: return
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                manager.updateSettings(current.copy(passwordAuthEnabled = enabled))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH ayarı değiştirilemedi.") }
        }
    }

    fun setSshLan(enabled: Boolean) {
        val manager = sshManager ?: return
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                manager.updateSettings(current.copy(listenLan = enabled))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH ayarı değiştirilemedi.") }
        }
    }

    fun addSshKey() {
        val manager = sshManager ?: run { notify("SSH yöneticisi hazır değil."); return }
        val draft = state.value.sshDraft
        if (draft.keyName.isBlank() || draft.publicKey.isBlank()) {
            mutableState.update { it.copy(sshDraft = it.sshDraft.copy(error = "Anahtar adı ve public key gereklidir.")) }
            return
        }
        viewModelScope.launch {
            try {
                SshKeyFormat.parse(draft.publicKey)
                manager.addAuthorizedKey(draft.keyName.trim(), draft.publicKey.trim())
                mutableState.update { it.copy(showSshSheet = false, sshDraft = SshDraft()) }
                notify("SSH anahtarı eklendi.")
            } catch (failure: IllegalArgumentException) {
                mutableState.update { it.copy(sshDraft = it.sshDraft.copy(error = failure.message ?: "Anahtar biçimi hatalı.")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(sshDraft = it.sshDraft.copy(error = failure.message ?: "Anahtar eklenemedi.")) }
            }
        }
    }

    fun removeSshKey(id: Long) {
        val manager = sshManager ?: return
        viewModelScope.launch {
            try {
                manager.removeAuthorizedKey(id)
                notify("SSH anahtarı kaldırıldı.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Anahtar kaldırılamadı.") }
        }
    }

    // --- Managed database services ---

    fun installService(serviceKey: String) {
        val services = managedServices ?: return
        viewModelScope.launch {
            try { services.install(serviceKey); notify("Kurulum isteği gönderildi.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Kurulum başarısız.") }
        }
    }
    fun startService(serviceKey: String) {
        val services = managedServices ?: return
        viewModelScope.launch {
            try { services.start(serviceKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Servis başlatılamadı.") }
        }
    }
    fun stopService(serviceKey: String) {
        val services = managedServices ?: return
        viewModelScope.launch {
            try { services.stop(serviceKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Servis durdurulamadı.") }
        }
    }

    fun selectTab(tab: DashboardTab) { mutableState.update { it.copy(selectedTab = tab) } }

    fun consumeNotice(id: Long) {
        mutableState.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }
    fun showMessage(message: String) = notify(message)

    private fun refreshProgress() {
        mutableState.update { current ->
            val transitional = current.runtime.status in setOf(RemoteServerStatus.STARTING, RemoteServerStatus.STOPPING)
            val connecting = current.network.nginxStatus == NetworkServiceStatus.STARTING ||
                current.network.tunnelStatus == NetworkServiceStatus.STARTING
            current.copy(
                operationBusy = operation.running || transitional,
                operationDescription = when {
                    operation.running -> operation.message ?: "İşlem sürüyor…"
                    current.runtime.status == RemoteServerStatus.STARTING -> "Sunucu başlatılıyor…"
                    current.runtime.status == RemoteServerStatus.STOPPING -> "Sunucu ve projeler durduruluyor…"
                    connecting -> "Nginx ve tünel bağlantısı kuruluyor…"
                    else -> null
                },
            )
        }
    }

    private fun zipError(message: String) {
        mutableState.update { it.copy(draft = it.draft.copy(zipUri = null, zipName = null, error = message)) }
    }
    private fun draftError(message: String) { mutableState.update { it.copy(draft = it.draft.copy(error = message)) } }
    private fun perform(action: () -> Unit) {
        try { action() } catch (_: Exception) { notify("İşlem başlatılamadı. İzinleri ve sunucu durumunu kontrol edin.") }
    }
    private fun notify(message: String) {
        mutableState.update { it.copy(notice = DashboardNotice(++noticeId, message)) }
    }
}
