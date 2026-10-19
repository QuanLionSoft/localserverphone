package dev.pocketpaas.presentation.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketpaas.domain.model.NetworkServiceStatus
import dev.pocketpaas.domain.remote.RemoteServerStatus
import dev.pocketpaas.domain.setup.TermuxPermissionState
import dev.pocketpaas.domain.setup.TermuxPermissionStatus
import dev.pocketpaas.presentation.dashboard.DashboardUiState

/** Navigation is the only local state. Copying, granting and starting remain explicit actions. */
@Composable
fun SetupScreen(
    state: DashboardUiState,
    setupCommand: String,
    commandCopied: Boolean,
    onInstallTermux: () -> Unit,
    onOpenTermux: () -> Unit,
    onCopySetupCommand: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    verificationError: String? = null,
    onOpenPermissionSettings: () -> Unit = onRequestPermission,
    onOpenTermuxSettings: () -> Unit = onOpenTermux,
    versionLabel: String = "0.4.0-debug",
    permissionStatus: TermuxPermissionStatus? = null,
    inspectingPermission: Boolean = false,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showPermissionHelp by rememberSaveable { mutableStateOf(false) }
    var showCommand by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val installed = permissionStatus?.let { it.state != TermuxPermissionState.TERMUX_MISSING }
        ?: (state.capabilities?.termuxInstalled == true)
    val permissionGranted = permissionStatus?.state == TermuxPermissionState.GRANTED ||
        (permissionStatus == null && state.capabilities?.termuxCommandPermissionGranted == true)
    val permissionUnavailable = permissionStatus?.state == TermuxPermissionState.PERMISSION_UNAVAILABLE
    val canRequestPermission = !inspectingPermission && when (permissionStatus?.state) {
        TermuxPermissionState.GRANTED, TermuxPermissionState.DENIED -> true
        null -> installed
        else -> false
    }
    val running = state.serverRunning
    val starting = state.runtime.status == RemoteServerStatus.STARTING
    val stopping = state.runtime.status == RemoteServerStatus.STOPPING
    val canStart = !checking && (running || (installed && permissionGranted && !inspectingPermission &&
        state.setupReady && !state.initializing && !state.setupFailed && !state.operationBusy &&
        state.runtime.status == RemoteServerStatus.STOPPED))
    val pageTitles = listOf("Termux", "İzin", "Araçlar", "Kontrol")

    LaunchedEffect(page) { listState.scrollToItem(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pocket PaaS · Kurulum", style = MaterialTheme.typography.titleMedium)
                        Text(versionLabel, modifier = Modifier.testTag("setup-version"), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pageTitles.forEachIndexed { index, title ->
                            TextButton(
                                onClick = { page = index },
                                modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("setup-step-${index + 1}").semantics {
                                    contentDescription = "Adım ${index + 1} / 4: $title"
                                    selected = page == index
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        modifier = Modifier.size(26.dp),
                                        shape = CircleShape,
                                        color = if (page == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (page == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium) }
                                    }
                                    Text(title, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    LinearProgressIndicator(progress = { (page + 1) / 4f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Adım ${page + 1} / 4 · ${pageTitles[page]}", style = MaterialTheme.typography.labelMedium)
                    if (page == 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = onRequestPermission,
                                enabled = canRequestPermission,
                                modifier = Modifier.weight(1f).testTag("setup-grant-permission"),
                            ) { Text("İzin iste") }
                            OutlinedButton(
                                onClick = onOpenPermissionSettings,
                                modifier = Modifier.weight(2f).testTag("setup-open-permission-settings"),
                            ) { Text("Pocket PaaS ayarlarını aç") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.weight(1f).testTag("setup-previous")) { Text("Geri") }
                        if (page < 3) {
                            Button(onClick = { page++ }, modifier = Modifier.weight(2f).testTag("setup-next")) { Text("İleri · ${pageTitles[page + 1]}") }
                        } else {
                            Button(
                                onClick = if (running) onOpenDashboard else onStart,
                                enabled = canStart,
                                modifier = Modifier.weight(2f).testTag("setup-start"),
                            ) {
                                Text(when {
                                    checking -> "Kontrol ediliyor…"
                                    running -> "Paneli aç"
                                    starting -> "Başlatılıyor…"
                                    stopping -> "Durduruluyor…"
                                    else -> "Kontrol et ve başlat"
                                })
                            }
                        }
                    }
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("setup-list"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "page-heading-$page") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (page) {
                                0 -> "Önce Termux’u hazırlayın"
                                1 -> "Pocket PaaS’a bağlantı izni verin"
                                2 -> "Sunucu araçlarını yükleyin"
                                else -> "Bağlantıyı kontrol edin"
                            },
                            modifier = Modifier.testTag("setup-page-heading").semantics { heading() },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            when (page) {
                                0 -> "Termux, sunucu programlarını telefonunuzda çalıştırır. Root gerekmez. Bu ekranlardaki işlemleri bir kez yapacaksınız."
                                1 -> "Bu izin, Pocket PaaS’ın Termux içinde komut çalıştırmasını sağlar. Bildirim veya depolama izninden farklıdır."
                                2 -> "Bu bölümde komutu önce kopyalayacak, sonra Termux’a kendiniz yapıştırıp çalıştıracaksınız. İnternet bağlantısını açık tutun."
                                else -> "Düğmeye bastığınızda araçlar kontrol edilir, ardından sunucu başlatılır. Kopyalama veya İleri düğmesi sunucuyu başlatmaz."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                when (page) {
                    0 -> {
                        item(key = "termux-status") {
                            GuidanceCard(
                                title = "1. Termux’u yükleyin ve bir kez açın",
                                status = when {
                                    state.initializing && state.capabilities == null -> "Termux kontrol ediliyor…"
                                    permissionUnavailable -> "Termux bulundu; bu sürümün bağlantı desteği yok"
                                    installed -> "Termux telefonda bulundu"
                                    else -> "Termux henüz bulunamadı"
                                },
                            ) {
                                Text("Termux’u bir kez açın. İlk hazırlığın bitmesini ve komut yazabileceğiniz satırın görünmesini bekleyin. Kurulu sürümün bağlantı desteğini 2. adımda kontrol edeceğiz.")
                                if (!installed) Button(onClick = onInstallTermux, modifier = Modifier.fillMaxWidth().testTag("setup-install-termux")) { Text("Termux indirme sayfasını aç") }
                                OutlinedButton(onClick = onOpenTermux, enabled = installed, modifier = Modifier.fillMaxWidth().testTag("setup-open-installed-termux")) { Text("Termux’u aç") }
                                Text("Termux’u açtıktan sonra son uygulamalar ekranından Pocket PaaS’a dönün. Ardından alttaki İleri düğmesine dokunun.")
                            }
                        }
                        item(key = "termux-refresh") {
                            OutlinedButton(onClick = onRefresh, enabled = !state.initializing, modifier = Modifier.fillMaxWidth().testTag("setup-refresh")) { Text("Termux’u yeniden kontrol et") }
                        }
                    }
                    1 -> {
                        item(key = "permission-status") {
                            GuidanceCard(title = "2. Android komut izni", status = permissionStatusText(permissionStatus, installed, permissionGranted, inspectingPermission), statusTag = "setup-permission-status") {
                                permissionStatus?.versionName?.let { Text("Termux sürümü: $it", style = MaterialTheme.typography.bodySmall) }
                                Text(
                                    when {
                                        permissionUnavailable -> "Bu Termux sürümü Pocket PaaS bağlantısını desteklemiyor. Play Store sürümünde bu özellik bulunmayabilir. F-Droid veya resmi GitHub sürümünü kullanın. Ayarlarda bulunmayan bir izni açamazsınız."
                                        permissionStatus?.state == TermuxPermissionState.TERMUX_DISABLED -> "Termux Android ayarlarında devre dışı. Termux uygulama bilgisini açıp etkinleştirin, ardından durumu yeniden kontrol edin."
                                        permissionGranted -> "İzin zaten verilmiş. İzin iste düğmesine bastığınızda yeni bir pencere açılmaması normaldir; izni kapatıp yeniden açmanız gerekmez."
                                        !installed -> "İzin düğmeleri burada görünür kalır. Android’in komut iznini tanıması için önce 1. adımda Termux’u yükleyip açın."
                                        else -> "İzin iste düğmesine dokunun. Android bir izin penceresi açarsa İzin ver seçeneğini seçin. Pencere açılmazsa aşağıdaki Pocket PaaS ayarlarını aç düğmesini kullanın."
                                    },
                                    modifier = Modifier.testTag("setup-permission-explanation"),
                                )
                                Text("Alttaki İzin iste ve Pocket PaaS ayarlarını aç düğmeleri ekranda sabit kalır. Açılan ayarlar sayfası Pocket PaaS’a ait olmalı; bağlantı iznini Termux’un izinler sayfasında aramayın.", style = MaterialTheme.typography.bodySmall)
                                if (permissionUnavailable || permissionStatus?.state == TermuxPermissionState.TERMUX_DISABLED) {
                                    OutlinedButton(onClick = onOpenTermuxSettings, modifier = Modifier.fillMaxWidth().testTag("setup-open-termux-settings")) { Text("Termux uygulama bilgisini aç") }
                                }
                                if (permissionUnavailable) {
                                    Text("Sürüm değiştirmeden önce Termux dosyalarınızı yedekleyin. Uygulamayı kaldırmak verilerini silebilir. Farklı kaynakların Termux uygulama ve eklentilerini karıştırmayın.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = onInstallTermux, modifier = Modifier.fillMaxWidth().testTag("setup-compatible-termux")) { Text("Uyumlu Termux indirme sayfasını aç") }
                                }
                            }
                        }
                        item(key = "permission-settings-guide") {
                            GuidanceCard(title = if (permissionUnavailable) "Bu sürümle neden izin veremiyorum?" else "Sırada ne yapacağım?") {
                                when {
                                    permissionUnavailable -> {
                                        Text("Gerekli komut bağlantısını kurulu Termux sürümünün sunması gerekir. Android ayarlarından bu özelliği sonradan ekleyemezsiniz.")
                                        InstructionLine("1", "Termux dosyalarınızı Termux dışında erişebileceğiniz bir yere yedekleyin ve yedeği kontrol edin. Uygulamayı kaldırmak içindeki dosyaları siler; yedek hazır olmadan kaldırmayın.")
                                        InstructionLine("2", "Farklı kaynakların imzaları nedeniyle F-Droid sürümü mevcut Termux’un üzerine kurulmayabilir. Yedek hazırsa Android Ayarlar → Uygulamalar bölümünden mevcut Termux’u ve varsa Termux eklentilerini elle kaldırın.")
                                        InstructionLine("3", "Uyumlu Termux indirme sayfasını açın. F-Droid sayfasında önerilen sürümün altındaki Download APK bağlantısını seçin. F-Droid uygulamasını ayrıca kurmanız gerekmez.")
                                        InstructionLine("4", "İndirilen Termux APK’sını kurup Termux’u bir kez açın. Pocket PaaS’a dönüp izin durumunu yeniden kontrol edin. Eklenti kullanıyorsanız aynı kaynaktan kurun.")
                                    }
                                    permissionStatus?.state == TermuxPermissionState.TERMUX_DISABLED -> {
                                        InstructionLine("1", "Yukarıdaki Termux uygulama bilgisini aç düğmesine dokunun. Android’de Etkinleştir seçeneği varsa kullanın.")
                                        InstructionLine("2", "Termux’u bir kez açın ve Pocket PaaS’a dönün. İzin durumunu yeniden kontrol edin.")
                                    }
                                    !installed -> {
                                        Text("Üstteki 1 · Termux adımını açıp Termux’u yükleyin ve bir kez çalıştırın. Sonra bu adıma dönün. Termux yüklenmeden bu izin ayarlarda görünmeyebilir.")
                                    }
                                    else -> {
                                        InstructionLine("1", "Pocket PaaS ayarlarını aç düğmesine, ardından Android’de İzinler bölümüne dokunun.")
                                        InstructionLine("2", "Ek izinler / Diğer izinler varsa içine girin. Telefonunuza göre bölümün adı değişebilir.")
                                        InstructionLine("3", "Termux ortamında komut çalıştırma iznini bulun ve izin verin. Sonra Pocket PaaS’a dönün.")
                                    }
                                }
                                OutlinedButton(onClick = onRefresh, enabled = !state.initializing && !inspectingPermission, modifier = Modifier.fillMaxWidth().testTag("setup-refresh-permission")) { Text("İzin durumunu yeniden kontrol et") }
                                TextButton(onClick = { showPermissionHelp = !showPermissionHelp }, modifier = Modifier.testTag("setup-permission-help")) { Text(if (showPermissionHelp) "Yardımı kapat" else "İzin görünmüyor / pencere açılmıyor") }
                                if (showPermissionHelp) {
                                    Column(Modifier.testTag("setup-permission-help-content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("• İzin verilmiş yazıyorsa ek bir izin penceresine gerek yoktur. 3. adıma geçebilirsiniz.")
                                        Text("• Termux’u yeni yüklediyseniz bir kez açın; sonra buraya dönüp durumu yeniden kontrol edin.")
                                        Text("• İzin reddedildikten sonra Android yeniden pencere göstermeyebilir. Pocket PaaS’ın uygulama ayarlarındaki izinleri kontrol edin.")
                                        Text("• Android ve Termux sürümü izinlerin görünmesini etkileyebilir. Termux’un sürümünü ve yükleme kaynağını kontrol edin. Farklı kaynaklardan gelen Termux uygulama ve eklentilerini karıştırmayın.")
                                        Text("Son çare olarak önce dosyalarınızı yedekleyip uyumlu Termux kaynağı ve yükleme sırasını inceleyin. Termux’u veya Pocket PaaS’ı kaldırmak verileri silebilir; bu kurulum için veri silmeyin.", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        item(key = "tools") {
                            GuidanceCard(title = "3. Komutu Termux’ta çalıştırın", status = if (commandCopied) "Komut kopyalandı; henüz çalıştırıldığı doğrulanmadı" else "Komut henüz kopyalanmadı", statusTag = "setup-command-status") {
                                Text("Bu komut, kurulu tüm Termux paketlerini günceller; ardından Python, Nginx, Node.js, npm ve Cloudflare Tunnel aracını yükleyip gerçekten çalışabildiklerini kontrol eder. Termux’un uygulama bağlantı ayarını da hazırlar. İşlem internet hızına göre birkaç dakika sürebilir.")
                                InstructionLine("1", "Aşağıdaki düğmeyle komutu panoya kopyalayın.")
                                Button(onClick = onCopySetupCommand, enabled = installed && setupCommand.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("setup-copy-command")) { Text(if (commandCopied) "Komutu yeniden kopyala" else "Kurulum komutunu kopyala") }
                                InstructionLine("2", "Termux’u açın. Siyah terminal ekranında komut satırına uzun basın ve Paste / Yapıştır seçeneğine dokunun.")
                                OutlinedButton(onClick = onOpenTermux, enabled = installed, modifier = Modifier.fillMaxWidth().testTag("setup-open-command-termux")) { Text("Termux’u aç") }
                                InstructionLine("3", "Yapıştırdığınız komutu çalıştırmak için klavyedeki Enter tuşuna basın. Bu düğmeler komutu otomatik yapıştırmaz veya çalıştırmaz.")
                                InstructionLine("4", "Ekrandaki işlemler bitene kadar Termux’u kapatmayın. “Kurulum tamamlandı.” mesajını bekleyin; sonra Pocket PaaS’a dönüp İleri düğmesine dokunun.")
                                Text("“Kurulum tamamlanamadı” görürseniz üzerindeki hata satırlarını kontrol edin. İnternet bağlantısını düzelttikten sonra aynı komutu yeniden çalıştırabilirsiniz.", style = MaterialTheme.typography.bodySmall)
                                if (!installed) Text("Komutu kopyalayabilmek için önce 1. adımda Termux’u yükleyin.", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { showCommand = !showCommand }, modifier = Modifier.testTag("setup-show-command")) { Text(if (showCommand) "Komutu gizle" else "Kopyalanacak komutu göster") }
                                if (showCommand) SelectionContainer { Text(setupCommand, modifier = Modifier.testTag("setup-command-text"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                            }
                        }
                    }
                    else -> {
                        item(key = "check-summary") {
                            GuidanceCard(title = "4. Hazırlık durumunuz") {
                                StatusLine("Termux", if (installed) "Bulundu" else "Bulunamadı")
                                StatusLine("Android komut izni", if (permissionGranted) "Verilmiş" else if (permissionUnavailable) "Bu Termux sürümünde yok" else "Verilmemiş")
                                StatusLine("Komut", if (commandCopied) "Kopyalandı; çalışması ayrıca kontrol edilecek" else "Kopyalanmadı; daha önce kurulmuş olabilir")
                                Text("Termux’un bulunması, izin verilmesi veya komutun kopyalanması araçların kurulu olduğu anlamına gelmez. Gerçek kontrolü aşağıdaki başlat düğmesi yapar.", style = MaterialTheme.typography.bodySmall)
                                if (checking || state.initializing || state.operationBusy || starting || stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (!canStart) Text(
                                    when {
                                        permissionUnavailable -> "Başlatmak için 2. adımda uyumlu bir Termux sürümü hazırlayın."
                                        permissionStatus?.state == TermuxPermissionState.TERMUX_DISABLED -> "Başlatmak için 2. adımda Termux’u etkinleştirin."
                                        inspectingPermission -> "Android komut izni kontrol ediliyor. Bitmesini bekleyin."
                                        !installed -> "Başlatmak için önce 1. adımda Termux’u yükleyin ve açın."
                                        !permissionGranted && installed -> "Başlatmak için 2. adımda Pocket PaaS’a Termux komut iznini verin."
                                        else -> startBlockedReason(state, checking)
                                    },
                                    modifier = Modifier.testTag("setup-start-blocked").semantics { liveRegion = LiveRegionMode.Polite },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item(key = "live-status") {
                            GuidanceCard(title = "Şu an çalışan servisler") {
                                StatusLine("Yerel yönetim API’si", remoteStatusText(state.runtime.status), "setup-api-status")
                                StatusLine("Nginx site sunucusu", networkStatusText(state.network.nginxStatus), "setup-nginx-status")
                                StatusLine("İnternet tüneli", networkStatusText(state.network.tunnelStatus), "setup-tunnel-status")
                                Text("Yerel API’nin çalışması, Nginx’in veya internet tünelinin de çalıştığı anlamına gelmez. Tünel bağlantısı sonradan gelebilir; panelde her birinin durumunu ayrı görebilirsiniz.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (verificationError != null || state.setupFailed || state.runtime.status == RemoteServerStatus.FAILED) {
                    item(key = "error-$page") {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Bir kontrol tamamlanamadı", style = MaterialTheme.typography.titleMedium)
                                Text(verificationError ?: "Termux kurulumunu ve komut iznini kontrol edin. Çalışan veya hatalı bir sunucu varsa panelden durdurup yeniden deneyin.", modifier = Modifier.testTag("setup-verification-error").semantics { liveRegion = LiveRegionMode.Polite })
                                TextButton(onClick = onOpenDashboard, modifier = Modifier.testTag("setup-open-failed-dashboard")) { Text("Panelde durumu incele") }
                            }
                        }
                    }
                }
                state.notice?.let { notice ->
                    item(key = "notice-${notice.id}") {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                            Text(notice.text, modifier = Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
                item(key = "dashboard-link-$page") {
                    TextButton(onClick = onOpenDashboard, modifier = Modifier.fillMaxWidth().testTag("setup-open-dashboard")) { Text("Kurulumu sonra tamamla · Paneli aç") }
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(title: String, status: String? = null, statusTag: String = "setup-status", content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (status != null) Text(status, modifier = Modifier.testTag(statusTag).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun InstructionLine(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text("$number.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusLine(label: String, value: String, tag: String = "setup-summary-status") {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

private fun permissionStatusText(status: TermuxPermissionStatus?, installed: Boolean, granted: Boolean, inspecting: Boolean): String = when {
    inspecting -> "İzin kontrol ediliyor…"
    status?.state == TermuxPermissionState.PERMISSION_UNAVAILABLE -> "Bu Termux sürümünde komut izni bulunmuyor"
    status?.state == TermuxPermissionState.TERMUX_DISABLED -> "Termux devre dışı"
    status?.state == TermuxPermissionState.TERMUX_MISSING || !installed -> "Önce Termux yüklenmeli"
    granted -> "İzin verilmiş"
    else -> "Henüz izin verilmedi"
}

private fun startBlockedReason(state: DashboardUiState, checking: Boolean): String = when {
    checking -> "Araç kontrolü sürüyor. Bitene kadar ikinci bir başlatma yapılamaz."
    state.runtime.status == RemoteServerStatus.STARTING -> "Sunucu başlatılıyor. Durum bilgileri hazır oldukça güncellenecek."
    state.runtime.status == RemoteServerStatus.STOPPING -> "Sunucu durduruluyor. Bitmesini bekleyin."
    state.runtime.status == RemoteServerStatus.FAILED -> "Önce paneli açın. Ana sunucu anahtarıyla kalan süreçleri durdurup yeniden deneyin."
    state.initializing -> "Telefon ve izin bilgileri kontrol ediliyor. Kısa süre bekleyin."
    state.capabilities?.termuxInstalled != true -> "Başlatmak için önce 1. adımda Termux’u yükleyin ve açın."
    state.capabilities?.termuxCommandPermissionGranted != true -> "Başlatmak için 2. adımda Pocket PaaS’a Termux komut iznini verin."
    state.setupFailed -> "Kurulum bilgileri okunamadı. Hata açıklamasını kontrol edip durumu yeniden kontrol edin."
    state.operationBusy -> "Başka bir işlem sürüyor. Tamamlanmasını bekleyin veya panelden iptal edin."
    else -> "Kurulum durumunu yeniden kontrol edin."
}

private fun remoteStatusText(status: RemoteServerStatus): String = when (status) {
    RemoteServerStatus.STOPPED -> "Çalışmıyor"
    RemoteServerStatus.STARTING -> "Başlatılıyor"
    RemoteServerStatus.RUNNING -> "Çalışıyor"
    RemoteServerStatus.STOPPING -> "Durduruluyor"
    RemoteServerStatus.FAILED -> "Hata oluştu · Panelden inceleyin"
}

private fun networkStatusText(status: NetworkServiceStatus): String = when (status) {
    NetworkServiceStatus.STOPPED -> "Çalışmıyor"
    NetworkServiceStatus.STARTING -> "Bağlanıyor"
    NetworkServiceStatus.RUNNING -> "Çalışıyor"
    NetworkServiceStatus.FAILED -> "Hata oluştu · Panelden inceleyin"
}
