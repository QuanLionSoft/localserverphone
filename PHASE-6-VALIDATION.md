# Faz 6 — Ktor API, foreground service ve runtime recovery doğrulaması

Test çalışması: **13 Ekim 2026**, Windows/JDK 17. Teslim ve kaynak eşleşme denetimi: **18 Ekim 2026**. Toplam **310 test: 292 geçti, 18 atlandı, 0 hata**. Gradle sonucu `BUILD SUCCESSFUL`. Makine tarafından okunabilir sonuçlar ve üretim kaynaklarının SHA-256 değerleri [verification/phase6-results.json](verification/phase6-results.json) içindedir.

| Doğrulama | Test | Geçen | Atlanan |
|---|---:|---:|---:|
| Kotlin/JVM, gerçek Room/SQLite, subprocess, HTTP ve Nginx | 179 | 177 | 2 |
| Python authenticated Termux agent | 57 | 52 | 5 |
| Python runtime config/file helper | 26 | 19 | 7 |
| Python ZIP/deployment/journal helper | 48 | 44 | 4 |
| **Toplam** | **310** | **292** | **18** |

Atlanan testler başarılı sayılmadı. Altı yeni Android anahtar deposu instrumentation testi yazıldı; cihaz/emülatör bulunmadığı için çalıştırılmadı ve yukarıdaki çalıştırılmış test toplamına eklenmedi.

## Derlenen kaynaklar ve Android sınırı

Bağımsız `verification/room-jvm` modülü uygulamanın **71 gerçek üretim Kotlin dosyasını değiştirmeden** derledi. Derlenen kopyaların tamamı teslimdeki kaynaklarla SHA-256 üzerinden eşleştirildi. Room KSP kod üretimi, v3 şema kimliği, domain/repository, shell, bridge, network, deployment, remote API ve recovery kaynakları doğrulamaya dahildir.

Android `DeploymentEngine`, `RemoteServerService`, `RemoteServerController`, `AndroidApiKeyStore` adaptörleri gerçek **Android API 36**, **Hilt Android/core 2.57.2** jar'larıyla Kotlin/JVM 17 ve `allWarningsAsErrors=true` altında derlendi. Bu, Android sisteminin foreground servis çalıştırmasını veya Hilt'in tam uygulama graph'ını doğrulamaz. `AppModule` ve manifest bağlantıları kaynak incelemesiyle kontrol edildi; tam Android Hilt processing, lint ve APK paketleme başarılı sayılmaz.

## Faz 6 test kapsamı

**21 Ktor TestApplication testi** Bearer doğrulamasının body/cache erişiminden önce çalışmasını; eksik/yanlış/tekrarlanan credential reddini; bozuk/ulaşılamayan token deposunda fail-closed davranışını; sınırlı Unicode loglarını; katı JSON tip/kimlik kurallarını; hatalı UTF-8 ve multipart yapı reddini; metadata/ZIP/toplam/header boyut sınırlarını; sıradan bağımsız parça sırasını; binary içerik ve sınır işaretine benzeyen byte dizilerinin korunmasını; timeout/iptal/spool temizliğini; tek mutasyon admission ve eşzamanlı liste okumayı doğrular.

**11 gerçek Netty socket testi** içinde 10 test geçti, gerçek symlink dizini testi Windows'ta atlandı. Gerçek loopback HTTP üzerinde auth/list/binary deploy/start/stop, listener kapatma/yeniden başlatma, dolu portta startup cleanup, yalnız sahip olunan spool dosyalarının temizliği, yavaş upload deadline, aktif request iptali, tamamlanmamış header deadline, bağlantı kapasitesi ve uzun deployment yanıtının header timeout'undan etkilenmemesi doğrulandı. Gövdesi hiç gönderilmeyen 1 GiB Content-Length iddialı yetkisiz istekte 401 yanıtından sonra bağlantının kapanması ve slot'un geri verilmesi ayrıca test edildi.

Bu testler iki gerçek hatanın düzeltilmesini doğruladı: multipart parser'ın `CRLF--boundaryX` gibi bir binary diziyi erken sınır sayması ve Netty'nin reddedilmiş isteğin ilan edilen body’sini beklerken bağlantıyı açık tutması. Parser sınırın devamını kontrol eder; bağlantı koruyucusu son response yazıldıktan sonra flush/close yapar. 100 Continue yanıtı bu kapanışa dahil edilmez.

**16 runtime koordinatör testi** cold recovery'nin ilk startup öncesinde çalışmasını, başarıdan sonra aynı oturumda tekrarlanmamasını, startup hata/iptal temizliğini, başarısız HTTP cleanup owner'ının korunmasını, FAILED durumunda yeni startup'ın reddini, aktif ve bekleyen deployment işlerinin Stop ile iptalini, HTTP kapanışı sonrası rollback/süreç temizliğini, tipli hata eşlemesini ve 64 kayıtlı site sınırını kapsar.

**7 recovery testi**, **3 yeni gerçek bridge/reset testi**, **1 yeni gerçek workspace journal testi**, **6 yeni Python agent testi** ve **4 yeni Python journal testi** eklendi. Bilinmeyen canlı PID'ye sinyal gönderilmeden recovery'nin reddi, eski authenticated agent süreçlerinin temizliği, mevcut oturumun kendi çocuklarını cold reset ile kaybetmesinin engellenmesi, commit kanıtının korunması, yarım deployment abort'u, bilinmeyen journal/config reddi ve response-loss sonrası idempotent retry doğrulandı.

Önceki fazların testleri aynı tam çalışmada tekrar geçti. Üç native Nginx 1.30.5 testi gerçek routing/reload kullanır. Windows fixture yalnız POSIX yollarını host'a eşler ve host'un desteklemediği `disable_symlinks` direktifini fixture'da çıkarır; üretim yapılandırması değiştirilmez. npm/pip komutları orchestration testlerinde sahte handle'larla temsil edilir; gerçek registry install ve framework deployment çalıştırılmadı.

## Yeniden çalıştırma

Proje kökünde, yerel araç yollarını kendi ortamınıza göre ayarlayın:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:POCKETPAAS_TEST_PYTHON = 'C:\path\to\python.exe'
$env:POCKETPAAS_TEST_ANDROID_JAR = 'C:\path\to\android-sdk\platforms\android-36\android.jar'
$env:POCKETPAAS_TEST_HILT_JAR = 'C:\path\to\hilt-android-2.57.2-classes.jar'
$env:POCKETPAAS_TEST_HILT_CORE_JAR = 'C:\path\to\hilt-core-2.57.2.jar'
$env:POCKETPAAS_TEST_NGINX = 'C:\path\to\nginx.exe'
.\gradlew.bat --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' -p .\verification\room-jvm test
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\termux-agent\test_agent.py
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\runtime-files\test_files.py
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\deployment\test_deploy.py
```

Hilt Android jar'ı gerçek AAR'ın `classes.jar` dosyası veya Gradle'ın dönüştürdüğü runtime jar'dır; core jar ayrı Maven artifact'idir. Android/Hilt jar'ları verilmezse bazı Android adaptörleri bağımsız derlemeye alınmaz. Python/Nginx executable yolu verilmezse ilgili entegrasyon testleri skip edilir; bu test çalışmasında tüm yollar sağlandı. Native executable, SDK, cache ve yerel imzalama dosyaları kaynak arşivine dahil değildir. Linux/macOS'ta uygun executable yollarıyla `sh ./gradlew` kullanın. Sandbox host'unda gerekirse `TEMP`/`TMP` değerlerini yazılabilir bir çalışma dizinine ayarlayın.

Gerçek cihaz testleri ayrıca `:app:connectedDebugAndroidTest` ile çalıştırılmalıdır. Android anahtar deposunun 6 testi; 256 bit format/persistence, eşzamanlı erişim, bozuk/aşırı büyük dosyanın korunması, kesilmiş AtomicFile yazımının kurtarılması ve okunamayan mevcut anahtarın değiştirilmemesini hedefler. Bu testler henüz çalıştırılmadı.

## Atlamalar, çalışma sınırları ve APK

18 host kontrolü Windows'ta atlandı: Kotlin POSIX shell/layout; Netty gerçek symlink-root; agent'ın beş POSIX process-group/shell/port/symlink/no-follow kontrolü; runtime file helper'ın yedi POSIX/FIFO/symlink kontrolü; deployment helper'ın dört POSIX/symlink kontrolü. Bu güvenlik davranışları gerçek Linux/Termux üzerinde ayrıca doğrulanmalıdır.

Android foreground service bildirimi/izinleri, lifecycle/hard-kill davranışı, RUN_COMMAND IPC, cihaz portları, ContentResolver/provider davranışı, gerçek Termux Python/venv/npm uyumluluğu, Cloudflare edge, tam Hilt graph, lint ve release imzası çalıştırılmadı. Foreground service CPU uyanıklığı veya kesintisiz çalışma garantisi vermez. Runtime cold recovery kaynakları ve host testleri tamamlandı; cihazda başarılı recovery iddiası yoktur. Detaylar [REMOTE-API.md](REMOTE-API.md) içindedir.

**APK henüz üretilmedi.** Önceki fazlarda resmi AGP/SDK AAPT2'nin native ZIP/APK yazma adımı bu Windows host'unda `0xC0000005` ile çöktü. Bu faz aynı native adımı yeniden denemedi; Kotlin/Python/HTTP/Nginx doğrulamaları bundan bağımsız yürütüldü. Kaynak ZIP'i APK değildir. Önceki kanıtlar [VALIDATION.md](VALIDATION.md) ve faz raporlarında korunur. Compose Dashboard Faz 7 onayını bekler.
