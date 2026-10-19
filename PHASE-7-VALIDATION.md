# Faz 7 — Compose Dashboard ve gerçek debug APK doğrulaması

Doğrulama: **18 Ekim 2026**, Windows, JDK 17, Android SDK 36, Build Tools 35.0.0. Yedi fazın kodları tamamlandı ve **gerçek, imzalı debug APK üretildi**. Kurulum ve kullanım [DASHBOARD.md](DASHBOARD.md), tam kaynak ve sürüm bilgisi [README.md](README.md) içindedir.

## Test sonuçları

**Tekil test toplamı: 346; 328 geçti, 18 atlandı, 0 hata.** Atlananlar başarılı sayılmaz. Android modülü testleri host JVM'de çalışır; cihaz testi değildir.

| Çalışma | Test | Geçen | Atlanan | Hata |
|---|---:|---:|---:|---:|
| Bağımsız Kotlin/JVM, Room/SQLite, gerçek süreç/HTTP/Nginx | 199 | 197 | 2 | 0 |
| Android modülü JVM birim testleri | 126 | 125 | 1 | 0 |
| Python Termux agent | 57 | 52 | 5 | 0 |
| Python dosya/config helper | 26 | 19 | 7 | 0 |
| Python ZIP/journal helper | 48 | 44 | 4 | 0 |

İki JVM çalışmasındaki **110 ortak test** tekil toplama ikinci kez eklenmedi. Android modülü çalışması gerçek Lifecycle/ViewModel bağımlılıklarıyla 16 MainViewModel testini içerir. Masaüstü JDK HttpServer/ProcessHandle, Python executable veya native Nginx isteyen yedi entegrasyon test sınıfı `verification/room-jvm/src/test/kotlin` altında çalışır; testler silinmedi veya devre dışı bırakılmadı.

Makine tarafından okunabilir test/suite sonuçları, APK SHA-256 ve **104 üretim kaynak dosyasının** hash'i [verification/phase7-results.json](verification/phase7-results.json) içindedir. Bağımsız JVM modülünün derlediği **81 gerçek Kotlin kaynağı**, teslimle byte/hash üzerinden eşleştirildi.

## Android derlemesi ve APK

Gradle'ın `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebugAndroidTest` görevleri geçti. Gerçek Android API 36 ile Compose compiler, Room/KSP, Hilt graph/component üretimi, Java/Kotlin, D8 ve normal APK paketleme/imzalama tamamlandı. Kotlin uyarıları hata olarak ele alınmaya devam eder; lint hataları kapatılmadı. **Lint: 0 hata, 34 uyarı**; gruplar makine raporundadır. Uyarılar sabitlenmiş bağımlılık sürümlerinin güncelleme önerileri, Termux sözleşmesinin sabit özel yolları ve iki KTX stil önerisidir. En güncel bağımlılık sürümleri iddia edilmez. Yerel SDK yolu dosyasının PropertyEscape hatası düzeltilip analiz önbelleksiz yeniden çalıştırıldı; bu makineye özgü dosya kaynak ZIP'ine girmez.

| APK kontrolü | Sonuç |
|---|---|
| Teslim dosyası | `PocketPaaS-Faz7-debug.apk` |
| Boyut | 17,687,805 bayt |
| Application ID | `dev.pocketpaas.debug` |
| Minimum / hedef SDK | 26 / 36 |
| Resmî apksigner | Geçerli APK Signature Scheme v2; debug RSA-2048 imzası |
| Resmî zipalign | 4 byte ve 16 KiB kontrolleri geçti |
| Resmî AAPT2 dump badging | Derlenmiş manifest/kaynak metaverisi okundu |
| ZIP/kaynak tablosu | CRC, tekil girdiler, binary manifest, sıkıştırılmamış resources.arsc geçti |
| DEX | 19 dosyanın boyut/SHA-1 ve beklenen uygulama sınıfları geçti |
| Python asset'leri | Üç yardımcı kaynakla byte düzeyinde aynı |
| Native kitaplıklar | Dört ABI'deki AndroidX graphics ELF PT_LOAD segmentleri 16 KiB hizalı |

SHA-256: `333f1548e2af9d0588f1bd5309df9b783fdc5d494a1afcb2e9e2a7384a36dd18`.

Bu host'ta resmî AAPT2'nin native ZIP yazma adımı `0xC0000005` ile çöküyordu. Kaynak kodu teslim edilen [isteğe bağlı Windows aracı](tools/windows-aapt2-wrapper/README.md), **resmî AAPT2'nin kaynak derleme/link işlemlerini korur**; `--output-to-dir` dosyalarını aynı byte'larla ZIP'e yazar. AGP'nin kalan Kotlin/KSP/Hilt/D8/paketleme/imza işlemleri normal yürütülür. Elle kaynak tablosu, stub kaynak veya sahte APK üretilmedi. Global SDK/cache değiştirilmedi. Araç küçültülmüş release/AAB için doğrulanmadı. [APK doğrulama araçları](tools/apk-verification/README.md) de teslimdedir.

## Faz 7 kapsamında doğrulanan davranış

MainViewModel'in 16 testi; reaktif site ve canlı log güncellemeleri, kuruluma bağlı açık kullanıcı başlatması, Stop erişimi, LAN tercihi, form/domain/tür doğrulaması, içerik URI metadata yarışları, aşırı büyük ZIP reddi, aktif siteyi koruma, durdurulmuş siteyi yeniden deploy, işlem iptali ve yalnız açık eylemle hassas anahtar kopyalamayı kapsar. Tünel yeniden bağlanırken yerel deployment kullanılabilir kalır; bildirim tüketiminde eski sonuç yeni mesajı silemez.

18 yeni çekirdek testi; runtime mutasyon kapısı içinden yerel ZIP edinme, Stop'un etkin/bekleyen URI edinmesini iptal etmesi, gerçek IO üzerinde 64 MiB sınırı/boş ZIP/provider hatası/deadline/iptal/spool temizliği, uygulama scope'unda işlem sahipliği, dispatch öncesi iptal ve yeni owner sonucunun eski işlem tarafından ezilememesini doğrular. Kabul edilmiş işler Activity/ViewModel ömrüne bağlı değildir.

İki ek deployment regresyon testi; commit sonrası geçici temizlik borcunun site Stop ile tamamlanıp yeniden Start edilebilmesini ve PID'siz FAILED statik sitede borcun global Stop ile yeniden denenmesini doğrular. Commit edilmiş proje abort edilmez. ZIP deadline testinde kapanan stream'in IOException'ının TimeoutCancellationException'ı maskelemesi düzeltildi; gerçek IO hataları korunur.

Önceki fazların Room transaction/migration, bounded log, bağımsız stdout/stderr, gerçek loopback Netty/API auth/upload, süreç lease/ownership, Nginx routing/reload, recovery ve ZIP/journal testleri tekrar geçti. Python agent testinin Windows fixture'ı, kendi sonlandırılmış process handle'ının kernel'de sinyallenmesini okuyarak bekler ve reset'i tekrar dener; beklenmeyen PID'yi kabul etmez. POSIX ve üretim ownership denetimi değiştirilmedi.

## Tekrar çalıştırma

JDK 17/SDK 36 kurulu proje kökünde:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat -p .\verification\room-jvm test
python -B .\verification\termux-agent\test_agent.py
python -B .\verification\runtime-files\test_files.py
python -B .\verification\deployment\test_deploy.py
```

Host entegrasyon testleri için `POCKETPAAS_TEST_PYTHON` ve `POCKETPAAS_TEST_NGINX` executable yollarını sağlayın. Bağımsız JVM derlemesinde Android adaptörlerini de derlemek için `POCKETPAAS_TEST_ANDROID_JAR`, `POCKETPAAS_TEST_HILT_JAR`, `POCKETPAAS_TEST_HILT_CORE_JAR` değişkenlerini sağlayın; [önceki komut açıklaması](PHASE-6-VALIDATION.md) korunur. Bu çalışmada tüm yollar verildi. Varsayılan derleme resmî AAPT2 kullanır; yalnız ZIP crash yaşayan Windows host için araç kılavuzundaki override kullanılmalıdır. Release anahtarı kaynak ZIP'ine dahil değildir.

## Çalıştırılmayan kontroller ve sınırlar

ADB cihaz listesi boştu; emülatör de yoktu. **18 instrumentation testi derlendi ve test APK'sı üretildi, çalıştırılmadı:** 9 Compose UI, 6 Android anahtar deposu, 3 Room persistence/migration. Bunlar yukarıdaki çalıştırılmış test toplamına eklenmedi. Cihazla `:app:connectedDebugAndroidTest` çalıştırılmalıdır. UI piksel/ekran görüntüsü doğrulaması yapılmadı.

18 host kontrolü Windows/POSIX farkı nedeniyle atlandı: Kotlin shell/layout ve Netty symlink-root, Python agent'ın beş, file helper'ın yedi, ZIP helper'ın dört POSIX/process-group/symlink/FIFO kontrolü. Gerçek Linux/Termux doğrulaması gerekir.

Gerçek Android kurulumu, RUN_COMMAND IPC, ContentResolver sağlayıcısı, bildirim izni, foreground service/Doze/OEM hard-kill, Termux npm/pip registry ve framework kurulumu, Cloudflare edge ve release küçültme/imzası çalıştırılmadı. Bir sağlayıcı iptal/stream kapatmayı yok sayarsa IO deadline onu zorla sonlandıramaz. APK Nginx/Python/Node/cloudflared binary'lerini içermez; [Termux kurulumu](DASHBOARD.md) gerekir. Quick Tunnel ve Django/Flask geliştirme runner'ları üretim SLA'sı sağlamaz. Debug APK, release veya cihaz doğrulaması yerine geçmez.

Faz 1–6 kaynak ZIP'leri ve tarihsel raporlar değişmeden korundu.
