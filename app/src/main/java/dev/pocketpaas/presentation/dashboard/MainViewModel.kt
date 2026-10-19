package dev.pocketpaas.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pocketpaas.core.config.DeploymentPolicy
import dev.pocketpaas.domain.backup.BackupRestoreSummary
import dev.pocketpaas.domain.backup.PlatformBackupManager
import dev.pocketpaas.domain.backup.SignedBackupEnvelope
import dev.pocketpaas.domain.dashboard.DashboardActions
import dev.pocketpaas.domain.dashboard.DashboardDevice
import dev.pocketpaas.domain.dashboard.DashboardNetworkState
import dev.pocketpaas.domain.dashboard.DashboardOperationKind
import dev.pocketpaas.domain.dashboard.DashboardOperationState
import dev.pocketpaas.domain.logs.ActivityFilter
import dev.pocketpaas.domain.logs.LogExportFormat
import dev.pocketpaas.domain.logs.LogExportFormatter
import dev.pocketpaas.domain.model.ActivityCategory
import dev.pocketpaas.domain.model.ActivityEvent
import dev.pocketpaas.domain.model.ActivityLevel
import dev.pocketpaas.domain.model.DeploymentRecord
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
import dev.pocketpaas.domain.services.ManagedServiceState
import dev.pocketpaas.domain.services.ManagedServiceStatus
import dev.pocketpaas.domain.services.ManagedServices
import dev.pocketpaas.domain.services.ServiceCategory
import dev.pocketpaas.domain.ssh.SshAuthorizedKey
import dev.pocketpaas.domain.ssh.SshConnectionInfo
import dev.pocketpaas.domain.ssh.SshKeyFormat
import dev.pocketpaas.domain.ssh.SshManager
import dev.pocketpaas.domain.ssh.SshSettings
import dev.pocketpaas.domain.ssh.SshState
import dev.pocketpaas.domain.usecase.InitializePlatform
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    private val sshManager: SshManager = NoOpSshManager,
    private val managedServices: ManagedServices = NoOpManagedServices,
    private val deploymentHistory: DeploymentHistoryRepository = NoOpDeploymentHistoryRepository,
    private val activityRepository: ActivityRepository = NoOpActivityRepository,
    private val backupManager: PlatformBackupManager = NoOpPlatformBackupManager,
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
                if (server.status == RemoteServerStatus.RUNNING && oldStatus != server.status) {
                    viewModelScope.launch {
                        runCatching { sshManager.refresh() }
                        runCatching { managedServices.refresh() }
                    }
                }
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
        viewModelScope.launch {
            sshManager.state.collect { ssh ->
                val keys = runCatching { sshManager.listAuthorizedKeys() }.getOrDefault(emptyList())
                mutableState.update { it.copy(ssh = ssh, sshKeys = keys) }
            }
        }
        viewModelScope.launch {
            managedServices.services.collect { list -> mutableState.update { it.copy(managedServices = list) } }
        }
        viewModelScope.launch {
            deploymentHistory.observeRecent(50).collect { records -> mutableState.update { it.copy(deployments = records) } }
        }
        viewModelScope.launch {
            activityRepository.observeEvents(200).collect { events -> mutableState.update { it.copy(activity = events) } }
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
                if (runtime.state.value.status == RemoteServerStatus.RUNNING) {
                    runCatching { sshManager.refresh() }
                    runCatching { managedServices.refresh() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(initializing = false, setupFailed = true) }
            }
        }
    }

    // --- Server / project actions ---

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

    fun forceStopAll() {
        initialization?.cancel()
        zipSelection?.cancel()
        actions.cancelOperation()
        actions.dismissResult()
        viewModelScope.launch {
            runCatching { sshManager.stop() }
            runCatching { managedServices.stopAll() }
            runCatching { runtime.forceReset() }
            mutableState.update {
                it.copy(
                    operationBusy = false,
                    operationDescription = null,
                    runtime = RemoteServerState(RemoteServerStatus.STOPPED),
                )
            }
            runCatching {
                activityRepository.record(
                    ActivityCategory.SERVER,
                    ActivityLevel.INFO,
                    "Tüm servisler ve arka plan işlemleri durduruldu.",
                    "Termux arka planda yeniden tetiklenmeden durum sıfırlandı.",
                )
            }
            notify("Tüm servisler durduruldu ve durum sıfırlandı.")
        }
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
        viewModelScope.launch {
            try {
                if (enabled) sshManager.start() else sshManager.stop()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH işlemi başarısız.") }
        }
    }

    fun setSshPort(port: Int) {
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                sshManager.updateSettings(current.copy(port = port))
                notify("SSH portu :$port olarak güncellendi.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH portu güncellenemedi.") }
        }
    }

    fun setSshPasswordAuth(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                sshManager.updateSettings(current.copy(passwordAuthEnabled = enabled))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH ayarı değiştirilemedi.") }
        }
    }

    fun setSshLan(enabled: Boolean) {
        viewModelScope.launch {
            try {
                val current = state.value.ssh.settings
                sshManager.updateSettings(current.copy(listenLan = enabled))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "SSH ayarı değiştirilemedi.") }
        }
    }

    fun addSshKey() {
        val draft = state.value.sshDraft
        if (draft.keyName.isBlank() || draft.publicKey.isBlank()) {
            mutableState.update { it.copy(sshDraft = it.sshDraft.copy(error = "Anahtar adı ve public key gereklidir.")) }
            return
        }
        viewModelScope.launch {
            try {
                SshKeyFormat.parse(draft.publicKey)
                sshManager.addAuthorizedKey(draft.keyName.trim(), draft.publicKey.trim())
                val keys = runCatching { sshManager.listAuthorizedKeys() }.getOrDefault(emptyList())
                mutableState.update { it.copy(showSshSheet = false, sshDraft = SshDraft(), sshKeys = keys) }
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
        viewModelScope.launch {
            try {
                sshManager.removeAuthorizedKey(id)
                val keys = runCatching { sshManager.listAuthorizedKeys() }.getOrDefault(emptyList())
                mutableState.update { it.copy(sshKeys = keys) }
                notify("SSH anahtarı kaldırıldı.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Anahtar kaldırılamadı.") }
        }
    }

    // --- Managed database services ---

    fun installService(serviceKey: String) {
        viewModelScope.launch {
            try { managedServices.install(serviceKey); notify("Kurulum isteği gönderildi.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Kurulum başarısız.") }
        }
    }
    fun startService(serviceKey: String) {
        viewModelScope.launch {
            try { managedServices.start(serviceKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Servis başlatılamadı.") }
        }
    }
    fun stopService(serviceKey: String) {
        viewModelScope.launch {
            try { managedServices.stop(serviceKey) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notify(failure.message ?: "Servis durdurulamadı.") }
        }
    }

    // --- Log search, filtering & archiving ---

    fun setActivitySearchQuery(query: String) {
        mutableState.update { it.copy(activityFilter = it.activityFilter.copy(query = query.take(128))) }
    }

    fun setActivityLevelFilter(level: ActivityLevel?) {
        mutableState.update { it.copy(activityFilter = it.activityFilter.copy(level = level)) }
    }

    fun setActivityCategoryFilter(category: ActivityCategory?) {
        mutableState.update { it.copy(activityFilter = it.activityFilter.copy(category = category)) }
    }

    fun clearActivityFilters() {
        mutableState.update { it.copy(activityFilter = ActivityFilter()) }
    }

    fun exportLogs(format: LogExportFormat) {
        viewModelScope.launch {
            try {
                val current = state.value
                val payload = LogExportFormatter.exportCombined(
                    events = current.filteredActivity,
                    records = current.filteredDeployments,
                    format = format,
                    exportedAt = System.currentTimeMillis(),
                )
                device.copySensitive(payload)
                notify("${format.name} kayıt arşivi (${current.filteredActivity.size} olay, ${current.filteredDeployments.size} yayın) panoya kopyalandı.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notify("Kayıt arşivi dışa aktarılamadı.")
            }
        }
    }

    fun clearActivityLogs() {
        viewModelScope.launch {
            try {
                activityRepository.clearAll()
                notify("Aktivite kayıtları temizlendi.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notify("Aktivite kayıtları temizlenemedi.")
            }
        }
    }

    // --- Backup & Restore ---

    fun showBackupSheet() {
        mutableState.update { it.copy(showBackupSheet = true, backupDraft = it.backupDraft.copy(error = null)) }
    }

    fun dismissBackupSheet() {
        mutableState.update { it.copy(showBackupSheet = false) }
    }

    fun setBackupImportJson(value: String) {
        mutableState.update { it.copy(backupDraft = it.backupDraft.copy(importJson = value.take(512 * 1024), error = null)) }
    }

    fun setBackupRequireSignature(required: Boolean) {
        mutableState.update { it.copy(backupDraft = it.backupDraft.copy(requireSignature = required, error = null)) }
    }

    fun exportBackup() {
        viewModelScope.launch {
            try {
                val json = backupManager.exportEnvelopeJson(signWithApiKey = true)
                device.copySensitive(json)
                mutableState.update { it.copy(backupDraft = it.backupDraft.copy(lastExportedJson = json, error = null)) }
                notify("HMAC-SHA256 imzalı platform yedeği panoya kopyalandı.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(backupDraft = it.backupDraft.copy(error = failure.message ?: "Yedek oluşturulamadı.")) }
            }
        }
    }

    fun importBackup() {
        val draft = state.value.backupDraft
        if (draft.importJson.isBlank()) {
            mutableState.update { it.copy(backupDraft = it.backupDraft.copy(error = "Geri yüklenecek yedek JSON verisini yapıştırın.")) }
            return
        }
        viewModelScope.launch {
            try {
                val summary = backupManager.importEnvelopeJson(draft.importJson, requireSignature = draft.requireSignature)
                runCatching { sshManager.refresh() }
                runCatching { managedServices.refresh() }
                val keys = runCatching { sshManager.listAuthorizedKeys() }.getOrDefault(emptyList())
                mutableState.update {
                    it.copy(
                        showBackupSheet = false,
                        backupDraft = BackupDraft(),
                        sshKeys = keys,
                    )
                }
                notify("Yedek geri yüklendi: ${summary.restoredSshKeys} SSH anahtarı, ${summary.restoredServices} servis kaydı.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(backupDraft = it.backupDraft.copy(error = failure.message ?: "Yedek geri yüklenemedi.")) }
            }
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

    private object NoOpSshManager : SshManager {
        override val state: StateFlow<SshState> = MutableStateFlow(SshState())
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun updateSettings(settings: SshSettings) = Unit
        override suspend fun addAuthorizedKey(name: String, publicKey: String): SshAuthorizedKey =
            SshAuthorizedKey(0, name, publicKey, "", true, 0)
        override suspend fun removeAuthorizedKey(id: Long) = Unit
        override suspend fun listAuthorizedKeys(): List<SshAuthorizedKey> = emptyList()
        override suspend fun connectionInfo(): SshConnectionInfo =
            SshConnectionInfo("u0_a000", "127.0.0.1", SshSettings.DEFAULT_PORT, "ssh -p 8022 u0_a000@127.0.0.1")
        override suspend fun refresh() = Unit
    }

    private object NoOpManagedServices : ManagedServices {
        override val services: StateFlow<List<ManagedServiceState>> = MutableStateFlow(emptyList())
        override suspend fun install(serviceKey: String): ManagedServiceState =
            ManagedServiceState(serviceKey, serviceKey, ServiceCategory.DATABASE, false, ManagedServiceStatus.STOPPED, null, null, null, 0)
        override suspend fun start(serviceKey: String): ManagedServiceState = install(serviceKey)
        override suspend fun stop(serviceKey: String): ManagedServiceState = install(serviceKey)
        override suspend fun refresh() = Unit
    }

    private object NoOpDeploymentHistoryRepository : DeploymentHistoryRepository {
        override fun observeRecent(limit: Int): Flow<List<DeploymentRecord>> = emptyFlow()
        override suspend fun record(record: DeploymentRecord) = Unit
        override suspend fun recent(limit: Int): List<DeploymentRecord> = emptyList()
    }

    private object NoOpActivityRepository : ActivityRepository {
        override fun observeEvents(limit: Int): Flow<List<ActivityEvent>> = emptyFlow()
        override suspend fun record(category: ActivityCategory, level: ActivityLevel, message: String, detail: String?) = Unit
        override suspend fun recent(limit: Int): List<ActivityEvent> = emptyList()
    }

    private object NoOpPlatformBackupManager : PlatformBackupManager {
        override suspend fun exportSnapshot(signWithApiKey: Boolean): SignedBackupEnvelope =
            SignedBackupEnvelope(version = 1, algorithm = "NONE", exportedAt = 0, payloadJson = "{}", signatureHex = null)
        override suspend fun exportEnvelopeJson(signWithApiKey: Boolean): String = "{}"
        override suspend fun importEnvelopeJson(rawJson: String, requireSignature: Boolean): BackupRestoreSummary =
            BackupRestoreSummary(4, false, 0, 0, false, 0)
    }
}
