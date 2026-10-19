@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pocketpaas.presentation.dashboard

import android.content.ClipData
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpaas.BuildConfig
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.model.NetworkState
import dev.pocketpaas.domain.model.ProjectType
import dev.pocketpaas.domain.model.RuntimeCapabilities
import dev.pocketpaas.domain.model.Site
import dev.pocketpaas.domain.model.SiteStatus
import dev.pocketpaas.domain.remote.RemoteServerState
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.presentation.theme.PocketPaaSTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onMasterSwitch: (Boolean) -> Unit,
    onLanAccessChanged: (Boolean) -> Unit,
    onRetryNetwork: () -> Unit,
    onRefresh: () -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onAddProject: () -> Unit,
    onDismissAddProject: () -> Unit,
    onDomainChanged: (String) -> Unit,
    onProjectTypeChanged: (ProjectType) -> Unit,
    onZipSelected: (Uri) -> Unit,
    onDeploy: () -> Unit,
    onSiteSelected: (Long) -> Unit,
    onDismissLogs: () -> Unit,
    onStartSite: (Long) -> Unit,
    onStopSite: (Long) -> Unit,
    onCancelOperation: () -> Unit,
    onCopyApiKey: () -> Unit,
    onNoticeConsumed: (Long) -> Unit,
    onOpenSetup: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var showSetupHelp by rememberSaveable { mutableStateOf(false) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onZipSelected)
    }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            try {
                snackbar.showSnackbar(notice.text)
            } finally {
                onNoticeConsumed(notice.id)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!state.showAddProject && state.selectedSite == null) {
                ExtendedFloatingActionButton(
                    onClick = onAddProject,
                    modifier = Modifier.testTag("add-project").semantics { contentDescription = "Yeni proje ekle" },
                    icon = { PlusMark() },
                    text = { Text("Yeni proje") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("dashboard-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header") {
                    DashboardHeader(onRefresh, showSetupHelp, { showSetupHelp = !showSetupHelp }, onOpenSetup)
                }
                item(key = "master") {
                    MasterCard(state, onMasterSwitch, onLanAccessChanged, onCopyApiKey)
                }
                if (showSetupHelp || state.initializing || !state.setupReady || state.setupFailed ||
                    state.runtime.status == RemoteServerStatus.FAILED || state.network.nginxStatus == NetworkServiceStatus.FAILED ||
                    state.network.tunnelStatus == NetworkServiceStatus.FAILED
                ) {
                    item(key = "setup") { SetupCard(state, onRefresh, onRequestTermuxPermission) }
                }
                item(key = "tunnel") {
                    TunnelCard(state, onRetryNetwork) { url ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Tünel adresi", url)))
                            snackbar.showSnackbar("Tünel adresi kopyalandı")
                        }
                    }
                }
                if (state.operationBusy) {
                    item(key = "operation") { OperationCard(state.operationDescription, onCancelOperation) }
                }
                item(key = "sites-title") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Projeler",
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text("${state.sites.size} proje", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                when {
                    state.siteReadFailed -> item(key = "sites-error") {
                        InformationCard("Projeler okunamadı", "Kayıtlı projeler için veritabanını yeniden kontrol edin.") {
                            TextButton(onClick = onRefresh) { Text("Tekrar dene") }
                        }
                    }
                    state.loadingSites -> item(key = "sites-loading") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Projeler yükleniyor…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    state.sites.isEmpty() -> item(key = "sites-empty") {
                        InformationCard("İlk projenize yer açın", "Bir ZIP seçin; kurulum, uygulama süreci ve yönlendirme buradan yönetilsin.") {
                            TextButton(onClick = onAddProject) { Text("Proje ekle") }
                        }
                    }
                }
                items(state.sites, key = Site::id) { site ->
                    SiteCard(site, state.canManageSites, { onSiteSelected(site.id) }, { onStartSite(site.id) }, { onStopSite(site.id) })
                }
            }
        }
    }

    if (state.showAddProject) {
        AddProjectSheet(
            state = state,
            onDismiss = onDismissAddProject,
            onDomainChanged = onDomainChanged,
            onTypeChanged = onProjectTypeChanged,
            onChooseZip = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onDeploy = onDeploy,
            onCancel = onCancelOperation,
        )
    }
    state.selectedSite?.takeIf { !state.showAddProject }?.let { site ->
        LogsSheet(site, state.canManageSites, onDismissLogs, { onStartSite(site.id) }, { onStopSite(site.id) })
    }
}

@Composable
private fun DashboardHeader(onRefresh: () -> Unit, showSetupHelp: Boolean, onSetupHelp: () -> Unit, onOpenSetup: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerMark()
            Column(Modifier.weight(1f)) {
                Text("POCKET PAAS", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.primary)
                Text("Cebindeki sunucu", style = MaterialTheme.typography.headlineMedium)
                Text("Sürüm ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, modifier = Modifier.testTag("refresh-dashboard")) { Text("Yenile") }
        }
        TextButton(onClick = onSetupHelp, modifier = Modifier.testTag("termux-help")) {
            Text(if (showSetupHelp) "Kurulum yardımını gizle" else "Termux kurulumu")
        }
        onOpenSetup?.let { open ->
            TextButton(onClick = open, modifier = Modifier.testTag("easy-setup")) { Text("Kolay kurulum") }
        }
    }
}

@Composable
private fun MasterCard(
    state: DashboardUiState,
    onMasterSwitch: (Boolean) -> Unit,
    onLanAccessChanged: (Boolean) -> Unit,
    onCopyApiKey: () -> Unit,
) {
    val status = state.runtime.status
    val checked = status == RemoteServerStatus.STARTING || status == RemoteServerStatus.RUNNING || status == RemoteServerStatus.STOPPING
    val canToggle = status != RemoteServerStatus.STOPPING && status != RemoteServerStatus.FAILED &&
        (checked || (state.setupReady && !state.initializing && !state.setupFailed))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Ana sunucu", style = MaterialTheme.typography.titleLarge)
                    Text(status.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onMasterSwitch,
                    enabled = canToggle,
                    modifier = Modifier.testTag("master-switch").semantics {
                        contentDescription = "Sunucu ve tüneli başlat veya durdur"
                        stateDescription = status.label
                    },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkBadge("Nginx", state.network.nginxStatus)
                NetworkBadge("Tünel", state.network.tunnelStatus)
            }
            if (status == RemoteServerStatus.STARTING || status == RemoteServerStatus.STOPPING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.runtime.error?.let { ErrorText(it) }
            if (status == RemoteServerStatus.FAILED) {
                OutlinedButton(onClick = { onMasterSwitch(false) }, modifier = Modifier.testTag("retry-cleanup")) {
                    Text("Temizliği yeniden dene")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(
                    checked = state.lanAccess,
                    onCheckedChange = onLanAccessChanged,
                    enabled = status == RemoteServerStatus.STOPPED && !state.initializing,
                    modifier = Modifier.testTag("lan-access").semantics { contentDescription = "Yerel ağdan API erişimi" },
                )
                Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Yerel ağdan yönet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "API :${state.apiPort}. Varsayılan erişim yalnızca bu cihazdandır. LAN erişimi HTTP kullanır; şifreleme yoktur. Yalnız güvendiğiniz ağda açın; Bearer anahtarı gerekir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.serverRunning) {
                TextButton(onClick = onCopyApiKey, modifier = Modifier.testTag("copy-api-key")) {
                    Text("API anahtarını kopyala")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(state: DashboardUiState, onRefresh: () -> Unit, onPermission: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Termux bağlantısı", style = MaterialTheme.typography.titleMedium)
            if (state.initializing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Çalışma ortamı kontrol ediliyor…")
            } else {
                Text(
                    when {
                        state.setupFailed -> "Çalışma ortamı doğrulanamadı. Termux kurulumunu ve komut iznini kontrol edin."
                        state.capabilities?.termuxInstalled != true -> "Önce Termux’u kurup bir kez açın. Ardından aşağıdaki paketleri Termux içinde yükleyin."
                        state.setupReady -> "Paketleri ve harici uygulama ayarını Termux içinde kontrol edin."
                        else -> "Termux komut iznini verin ve Termux ayarlarında harici uygulamalara erişimi etkinleştirin."
                    },
                )
                SelectionContainer {
                    Text(
                        "Paketleri yükleyin:\npkg install python nginx nodejs cloudflared\n\nDüzenlenecek dosya:\n~/.termux/termux.properties\nEklenecek satır:\nallow-external-apps=true\n\nAyarı yeniden yükleyin:\ntermux-reload-settings",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Bu ayarı mevcut termux.properties dosyanıza ekleyin; Termux açık ve kurulumu tamamlanmış olmalıdır.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.capabilities?.let { it.termuxInstalled && !it.termuxCommandPermissionGranted } == true) {
                        Button(onClick = onPermission, modifier = Modifier.testTag("termux-permission")) { Text("Komut izni ver") }
                    }
                    TextButton(onClick = onRefresh) { Text("Yeniden kontrol et") }
                }
            }
        }
    }
}

@Composable
private fun TunnelCard(state: DashboardUiState, onRetry: () -> Unit, onCopy: (String) -> Unit) {
    val url = state.network.publicUrl
    if (url != null) {
        Card(
            onClick = { onCopy(url) },
            modifier = Modifier.fillMaxWidth().testTag("tunnel-url").semantics { contentDescription = "Tünel adresini kopyala: $url" },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("İNTERNETE AÇIK ADRES", style = MaterialTheme.typography.labelMedium, letterSpacing = 0.7.sp)
                Text(url, style = MaterialTheme.typography.titleMedium)
                Text("Adresi kopyalamak için dokunun. Projeler /sites/site-adı/ yolunda yayınlanır.", style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        InformationCard(
            "Cloudflare Tunnel",
            when (state.network.tunnelStatus) {
                NetworkServiceStatus.STARTING -> "Güvenli bağlantı kuruluyor. Genel adres hazır olduğunda burada görünecek."
                NetworkServiceStatus.FAILED -> "Tünel bağlantısı kurulamadı. Ağınızı kontrol edip yeniden deneyin."
                NetworkServiceStatus.RUNNING -> "Bağlantının genel adresi doğrulanıyor…"
                NetworkServiceStatus.STOPPED -> "Ana sunucuyu açtığınızda cihazınız için bir genel adres oluşturulur."
            },
        ) {
            state.network.lastError?.let { ErrorText(it) }
            if (state.serverRunning && (state.network.tunnelStatus == NetworkServiceStatus.FAILED || state.network.tunnelStatus == NetworkServiceStatus.STOPPED)) {
                TextButton(onClick = onRetry, enabled = !state.operationBusy, modifier = Modifier.testTag("retry-network")) {
                    Text("Bağlantıyı yeniden kur")
                }
            }
        }
    }
}

@Composable
private fun OperationCard(description: String?, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(description ?: "İşlem sürüyor…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel-operation")) { Text("İşlemi iptal et") }
        }
    }
}

@Composable
private fun SiteCard(site: Site, canManage: Boolean, onLogs: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    Card(onClick = onLogs, modifier = Modifier.fillMaxWidth().testTag("site-${site.id}")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(site.domainName, style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StateBadge(site.status.label, site.status == SiteStatus.RUNNING, site.status == SiteStatus.FAILED)
                    Text("${site.projectType.label} · :${site.internalPort}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
                }
            }
            Text(
                site.publicUrl ?: "/sites/${site.domainName}/",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SiteAction(site, canManage, onStart, onStop)
                TextButton(onClick = onLogs, modifier = Modifier.testTag("logs-${site.id}")) { Text("Canlı günlükler") }
            }
        }
    }
}

@Composable
private fun SiteAction(site: Site, canManage: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    if (site.status == SiteStatus.RUNNING || site.processId != null) {
        OutlinedButton(onClick = onStop, enabled = canManage, modifier = Modifier.testTag("stop-${site.id}")) { Text("Durdur") }
    } else if (site.status == SiteStatus.DEPLOYING) {
        Text("Kuruluyor…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
    } else {
        OutlinedButton(onClick = onStart, enabled = canManage, modifier = Modifier.testTag("start-${site.id}")) { Text("Başlat") }
    }
}

@Composable
private fun AddProjectSheet(
    state: DashboardUiState,
    onDismiss: () -> Unit,
    onDomainChanged: (String) -> Unit,
    onTypeChanged: (ProjectType) -> Unit,
    onChooseZip: () -> Unit,
    onDeploy: () -> Unit,
    onCancel: () -> Unit,
) {
    val draft = state.draft
    var expanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (!state.operationBusy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("add-project-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Yeni proje", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text("ZIP dosyanızı seçin. Kurulum, Yayına al düğmesine bastığınızda başlar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!state.serverRunning) {
                Text("Proje yayınlamak için önce ana sunucuyu açın.", color = MaterialTheme.colorScheme.primary)
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = !state.operationBusy,
                    modifier = Modifier.fillMaxWidth().testTag("project-type").semantics { stateDescription = draft.projectType.label },
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Proje türü", style = MaterialTheme.typography.labelSmall)
                        Text(draft.projectType.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Seç")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ProjectType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = { expanded = false; onTypeChanged(type) })
                    }
                }
            }
            OutlinedTextField(
                value = draft.domainName,
                onValueChange = onDomainChanged,
                modifier = Modifier.fillMaxWidth().testTag("project-domain"),
                enabled = !state.operationBusy,
                singleLine = true,
                label = { Text("Domain / site adı") },
                placeholder = { Text("my-app") },
                supportingText = { Text("Örnek yol: /sites/my-app/ — özel DNS kaydı gerekmez.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            OutlinedButton(
                onClick = onChooseZip,
                enabled = !state.operationBusy,
                modifier = Modifier.fillMaxWidth().testTag("choose-zip"),
            ) { Text(if (draft.zipUri == null) "ZIP seç" else "ZIP dosyasını değiştir") }
            draft.zipName?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("zip-name")) }
            draft.error?.let { ErrorText(it) }
            if (state.operationBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.operationDescription ?: "Proje kuruluyor…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel-deployment")) { Text("Kurulumu iptal et") }
            } else {
                Button(
                    onClick = onDeploy,
                    enabled = state.canManageSites && draft.domainName.isNotBlank() && draft.zipUri != null && draft.error == null,
                    modifier = Modifier.fillMaxWidth().testTag("deploy-project"),
                ) { Text("Yayına al") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Vazgeç") }
            }
        }
    }
}

@Composable
private fun LogsSheet(site: Site, canManage: Boolean, onDismiss: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    val scroll = rememberScrollState()
    var follow by remember(site.id) { mutableStateOf(true) }
    var copied by remember(site.id) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val logText = remember(site.logs) {
        site.logs.takeLast(16_384).let { tail -> if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail }
    }
    LaunchedEffect(site.id, logText, follow) {
        copied = false
        if (follow) snapshotFlow { scroll.maxValue }.collectLatest { scroll.scrollTo(it) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("logs-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(site.domainName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Canlı günlükler · ${site.status.label} · son 16.384 karakter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SiteAction(site, canManage, onStart, onStop)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("${site.domainName} günlükleri", logText)))
                            copied = true
                        }
                    },
                    enabled = logText.isNotEmpty(),
                    modifier = Modifier.testTag("copy-logs"),
                ) { Text(if (copied) "Kopyalandı" else "Günlükleri kopyala") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = follow,
                    onCheckedChange = { follow = it },
                    modifier = Modifier.testTag("follow-logs").semantics { contentDescription = "Canlı günlükleri otomatik takip et" },
                )
                Text("Yeni satırları takip et", style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 96.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                SelectionContainer {
                    Text(
                        text = logText.ifEmpty { "Henüz günlük kaydı yok." },
                        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp).testTag("live-logs"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Kapat") }
        }
    }
}

@Composable
private fun InformationCard(title: String, description: String, actions: @Composable () -> Unit = {}) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    val explanation = when (message) {
        "Server shutdown is incomplete. Retry Stop." -> "Sunucu tamamen durdurulamadı. Temizliği yeniden dene düğmesiyle kalan süreçleri durdurmayı yeniden deneyin."
        else -> message
    }
    SelectionContainer {
        Text(explanation, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun NetworkBadge(name: String, status: NetworkServiceStatus) {
    StateBadge("$name · ${status.label}", status == NetworkServiceStatus.RUNNING, status == NetworkServiceStatus.FAILED)
}

@Composable
private fun StateBadge(label: String, active: Boolean, failed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        failed -> colors.error
        active -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Surface(color = tint.copy(alpha = 0.10f), contentColor = tint, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(tint, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ServerMark() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(36.dp)) {
        val gap = size.height * 0.13f
        val height = (size.height - gap * 2) / 3
        repeat(3) { index ->
            val top = index * (height + gap)
            drawRoundRect(color.copy(alpha = 0.14f), topLeft = Offset(0f, top), size = Size(size.width, height), cornerRadius = CornerRadius(4.dp.toPx()))
            drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width * 0.22f, top + height / 2))
            drawLine(color, Offset(size.width * 0.46f, top + height / 2), Offset(size.width * 0.78f, top + height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun PlusMark() {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.size(22.dp)) {
        drawLine(color, Offset(size.width / 2, 3.dp.toPx()), Offset(size.width / 2, size.height - 3.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(3.dp.toPx(), size.height / 2), Offset(size.width - 3.dp.toPx(), size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private val ProjectType.label: String get() = when (this) {
    ProjectType.STATIC -> "Statik HTML"
    ProjectType.DJANGO -> "Python · Django / Flask"
    ProjectType.NODEJS -> "Node.js"
    ProjectType.REACT -> "React"
}
private val SiteStatus.label: String get() = when (this) {
    SiteStatus.RUNNING -> "Yayında"
    SiteStatus.STOPPED -> "Durdu"
    SiteStatus.FAILED -> "Hata"
    SiteStatus.DEPLOYING -> "Kuruluyor"
}
private val NetworkServiceStatus.label: String get() = when (this) {
    NetworkServiceStatus.RUNNING -> "Çalışıyor"
    NetworkServiceStatus.STARTING -> "Başlatılıyor"
    NetworkServiceStatus.STOPPED -> "Durdu"
    NetworkServiceStatus.FAILED -> "Hata"
}
private val RemoteServerStatus.label: String get() = when (this) {
    RemoteServerStatus.RUNNING -> "API hazır; Nginx ve tünel aşağıda izlenir"
    RemoteServerStatus.STARTING -> "Sunucu başlatılıyor…"
    RemoteServerStatus.STOPPING -> "Süreçler güvenle durduruluyor…"
    RemoteServerStatus.STOPPED -> "Sunucu kapalı"
    RemoteServerStatus.FAILED -> "İşlem tamamlanamadı; durdurup yeniden deneyin"
}

@Preview(name = "Dashboard", widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun DashboardPreview() {
    PocketPaaSTheme {
        DashboardScreen(
            state = DashboardUiState(
                runtime = RemoteServerState(RemoteServerStatus.RUNNING, "127.0.0.1"),
                network = NetworkState(NetworkServiceStatus.RUNNING, NetworkServiceStatus.RUNNING, publicUrl = "https://pocket-demo.trycloudflare.com"),
                sites = listOf(Site(1, "my-app", 9001, ProjectType.NODEJS, 321, null, SiteStatus.RUNNING, "Listening on 9001", 0)),
                capabilities = RuntimeCapabilities(true, true, listOf("arm64-v8a")),
                loadingSites = false,
                initializing = false,
            ),
            onMasterSwitch = {}, onLanAccessChanged = {}, onRetryNetwork = {}, onRefresh = {},
            onRequestTermuxPermission = {}, onAddProject = {}, onDismissAddProject = {}, onDomainChanged = {},
            onProjectTypeChanged = {}, onZipSelected = {}, onDeploy = {}, onSiteSelected = {}, onDismissLogs = {},
            onStartSite = {}, onStopSite = {}, onCancelOperation = {}, onCopyApiKey = {}, onNoticeConsumed = {},
        )
    }
}
