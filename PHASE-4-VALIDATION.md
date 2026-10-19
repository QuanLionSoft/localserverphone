# Faz 4 doğrulama kaydı

Tarih: 2026-10-13. Host: Windows; JDK 17.0.12, Gradle 8.13, Kotlin 2.2.21, Room/KSP 2.8.3 / 2.2.21-2.0.5, Coroutines 1.10.2, Python 3.12, native Nginx 1.30.5.

**145 test vakası: 133 geçti, 12 atlandı, 0 hata.** Kotlin/JVM çalıştırması `BUILD SUCCESSFUL` ile tamamlandı. Sayılar JUnit XML raporları ve Python unittest sonuçlarından toplandı; [verification/phase4-results.json](verification/phase4-results.json) suite sayıları, kaynak SHA-256 değerleri ve sınırları içerir.

| Kontrol | Geçti | Atlandı |
|---|---:|---:|
| Room, site/platform repository ve domain | 23 | 0 |
| Ana sunucu URL repository | 3 | 0 |
| Gerçek JVM subprocess/bağımsız stdout-stderr | 14 | 0 |
| Kotlin bridge → gerçek Python HTTP agent | 6 | 1 |
| Cloudflare log parser | 6 | 0 |
| Nginx config oluşturma/doğrulama | 12 | 0 |
| NetworkManager lifecycle, rollback, HTTP readiness | 16 | 0 |
| Gerçek native Nginx config/routing/reload | 3 | 0 |
| Kotlin RuntimeFileStore → gerçek Python helper | 2 | 0 |
| Python agent HTTP/queue/process testleri | 29 | 4 |
| Python runtime dosya helper testleri | 19 | 7 |
| Toplam | 133 | 12 |

## Doğrulanan davranışlar

- JVM modülü üretim kaynaklarını `Sync` ile alır; test çalıştırmasında kullanılan **43 Kotlin dosyası** teslimdeki kaynaklarla SHA-256 karşılaştırmasında eşleşti. Seçili config/domain/Room/repository/process/network/runtime kaynakları uyarılar hata kabul edilerek derlendi. Application/Compose/Hilt Android projesinin tamamı bu modülün kapsamı değildir.
- Android Termux bootstrap ve credential adapter'ları gerçek API 36 `android.jar` dosyasına karşı derlendi. Bu sınırlı API imza kontrolüdür; Android davranışını çalıştırmaz.
- Room v1→v3 ve v2→v3 migration gerçek SQLite ve Room'un üretilmiş şema doğrulamasıyla çalıştırıldı. Kurulum kimliği, siteler ve loglar korundu; main URL'nin set/clear işlemleri Flow'a yansıdı. Immutable eski şema fixture'larının identity hash'leri eski v1/v2 export ile aynı kaldı.
- Room/KSP üretimi v3 şema `identityHash`: `ade800917250d7186a5b5a2d0d2f5376`. Bu export `app/schemas` içine alındı; eski v1/v2 şemaları korunur. `DatabaseMigrations.kt` Android adapter'ı JVM modülüne alınmaz; aynı SQL sabitleri gerçek migration ile kullanılır. `SiteMigrationTest` cihaz testi teslimde vardır, çalıştırılmadı.
- Gerçek ProcessBuilder stdout/stderr büyük çıktı, eşzamanlı ayrı okuyucu thread'leri, UTF-8/surrogate-safe bounded capture, canlı log, env/working directory, timeout, iptal ve stop davranışlarını doğruladı. Önceki subprocess testleri korundu.
- Log parser iki stream ve parçalanmış chunk'ları işler. Quick Tunnel banner'ı, tam URL delimiter'i, suffix ve API hostname reddi kontrol edildi.
- NetworkManager testleri sahte process handle'ları ile **gerçek localhost HTTP sunucuları** kullanır. URL görülüp edge hazır değilken yayınlamama; başka hostname'in metrics cevabını reddetme; reconnect'te URL temizleme/yeniden yayınlama; unexpected exit; cancellation cleanup; stop retry sahipliği; stale watcher; config validation, rollback ve yeni revision readiness test edildi. Kayıtsız site mevcut host'u değiştiremez.
- Native Nginx gerçek executable ile çalıştırıldı: STATIC index/missing file/hidden file; slash redirect ve query; React route fallback ve eksik index; Django/Node proje tiplerinin prefix kaldıran proxy config'i ve forwarded header'lar; invalid candidate config testi ve canlı reload revision'ı. Upstream test sunucuları JVM `HttpServer` fixture'larıdır; Node/Django framework kurulumu veya uygulama çalıştırma testi değildir.
- Gerçek Nginx testi varsayılan çalışma/temp dizinlerine bağımlılığı ortaya çıkardı. Master config tüm client-body/proxy/FastCGI/uWSGI/SCGI temp yollarını runtime alanına taşır ve manager bunları hazırlar.
- Kotlin dosya deposu gerçek Java→Python subprocess üzerinden Unicode config round-trip, read/list/delete, boyut sınırı, invalid UTF-8 ve root dışı yol reddini doğruladı. Base64 script aktarımı Java'nın Windows argüman serializasyonu altında da çalıştı.
- Python helper testleri config boyutu, strict Base64/UTF-8, normalized containment, atomik replacement, korunmuş eski içerik, dizin/liste limitleri ve açık hata cevaplarını doğruladı. POSIX descriptor/symlink/private mode/FIFO kontrolleri aşağıdaki skip kapsamındadır.
- Python agent testleri bu fazda yeniden geçti: localhost auth, body/record/process limitleri, bağımsız stdout/stderr kuyrukları, backpressure, lease/watchdog, stop/shutdown, PID/env ve EOF. Windows sandbox'ın varsayılan temp alanına erişim reddi nedeniyle test temp dizini workspace altına ayarlandı; üretim davranışı değiştirilmedi.

## Native Nginx kaynağı ve host adapter'ı

Executable resmi [Nginx 1.30.5 Windows dağıtımından](https://nginx.org/download/nginx-1.30.5.zip) HTTPS ile alındı; version banner kontrol edildi. ZIP SHA-256:

```text
e5afe28b6a50bec92c478bfe1a4d3758206b80fb77159277bc5c4e88955c2a35
```

Nginx executable/ZIP teslim edilen APK kaynak arşivine eklenmez. Production APK Termux paketini çalıştıracaktır. Native host fixture'ı yalnız kendi `/runtime` yollarını Windows fiziksel test yollarına eşler. Windows Nginx POSIX `openat` desteğiyle derlenmediği için **`disable_symlinks on;` yalnız host test config'inden çıkarılır**. Production config'te direktif korunur. Bu üç native test Linux/Android symlink engelini doğrulamaz.

## Yeniden çalıştırma

Proje kökünde JDK 17 ile; path'leri kendi kurulumunuza göre verin:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:POCKETPAAS_TEST_PYTHON = 'C:\path\to\python.exe'
$env:POCKETPAAS_TEST_ANDROID_JAR = 'C:\path\to\android-sdk\platforms\android-36\android.jar'
$env:POCKETPAAS_TEST_NGINX = 'C:\path\to\nginx.exe'
.\gradlew.bat --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' -p .\verification\room-jvm test
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\termux-agent\test_agent.py
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\runtime-files\test_files.py
```

Linux/macOS'ta executable path'lerini ortam değişkenlerine verip `sh ./gradlew` kullanın. Nginx/Python ortam değişkenleri verilmezse ilgili integration testleri açıkça skip edilir; bu doğrulamada ikisi de sağlandı. Android jar değişkeni verilmezse Android adapter'ları bağımsız JVM derlemesine dahil edilmez. Offline çalıştırma yalnız bağımlılıklar önceden cache edilmişse mümkündür. Sandbox Kotlin incremental backup taşımasını engellediği için doğrulama komutunda in-process compiler/incremental=false kullanıldı; APK compiler ayarları değişmedi.

Windows sandbox'ın `Path.resolve(strict=True)` reddini uyarlayan kod yalnız kendi mevcut test fixture dizinlerinde kullanılır. Agent/helper üretim POSIX containment denetimleri korunur. Test temp erişimi engellenirse `TEMP`/`TMP` ortam değişkenleri yazılabilir bir workspace dizinine ayarlanabilir.

## Atlanan kontroller ve APK durumu

Toplam 12 kontrol atlandı: Kotlin POSIX shell/layout/binary entegrasyonu; agent POSIX shell, TERM'e dirençli alt process group, symlink working directory ve sabit port yeniden başlatma; dosya helper'ının POSIX private mode, FIFO, parent-swap ve dört symlink kontrolü. Linux/Android üzerinde descriptor/symlink ve process group testleri hâlâ çalıştırılmalıdır.

Gerçek Android cihaz/emülatör ve gerçek Cloudflare edge tüneli kullanılmadı. `RUN_COMMAND` permission/IPC, Termux paket uyumluluğu, cihaz port uygunluğu, Android lifecycle, tam Hilt graph, lint ve release imzası doğrulanmadı. Local `/ready` fixture'ı internette uçtan uca erişim kanıtı değildir. Cold APK restart'ta eski süreçlere otomatik attach ve kalıcı URL uzlaştırması bu fazda yoktur; ayrıntılar [NETWORK-RUNTIME.md](NETWORK-RUNTIME.md) içindedir.

**APK üretilmedi.** Önceki fazda resmi AGP ve SDK AAPT2 ZIP/APK yazımı native `0xC0000005` ile çöktü; `.flat` derlemesi çalışırken ZIP çıktısı bozuk kaldı. Faz 4'te aynı native adım tekrar denenmedi. JVM/native/HTTP testleri bu araçtan bağımsız yürütüldü. Kaynak ZIP'i APK olarak sunulmaz. Eski kanıtlar [VALIDATION.md](VALIDATION.md), [PHASE-2-VALIDATION.md](PHASE-2-VALIDATION.md) ve [PHASE-3-VALIDATION.md](PHASE-3-VALIDATION.md) içinde korunur.

İstenen Quick Tunnel entegrasyonu geçici adres içindir; Cloudflare uptime garantisi vermez. Production için named tunnel, kalan Faz 5–7, cihaz/lifecycle testleri ve başarılı release APK doğrulaması gerekir. [Cloudflare Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

Faz 4 tamamlandı. Faz 5 deployment pipeline kullanıcı onayını bekler.
