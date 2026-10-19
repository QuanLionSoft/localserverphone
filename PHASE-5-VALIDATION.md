# Faz 5 doğrulama kaydı

Tarih: 2026-10-13. Host Windows; JDK 17.0.12, Gradle 8.13, Kotlin 2.2.21, Room 2.8.3, Coroutines 1.10.2, Python 3.12, native Nginx 1.30.5.

**241 test vakası: 224 geçti, 17 atlandı, 0 hata.** Güncel suite sayıları, üretim kaynak hash'leri ve sınırlar [verification/phase5-results.json](verification/phase5-results.json) içindedir. Kotlin/JVM son çalıştırması `BUILD SUCCESSFUL` ile tamamlandı; kaynak uyarıları hata kabul edilir.

| Kontrol | Geçti | Atlandı |
|---|---:|---:|
| Room, site/platform repository ve domain | 23 | 0 |
| Ana sunucu URL repository | 3 | 0 |
| Gerçek subprocess ve kontrol slot'u | 18 | 0 |
| Kotlin bridge → gerçek Python agent, ZIP HTTP transferi | 9 | 1 |
| Cloudflare parser + Nginx builder | 18 | 0 |
| NetworkManager lifecycle/config snapshot/reload | 18 | 0 |
| Gerçek native Nginx routing/reload | 3 | 0 |
| Kotlin config file store → Python helper | 2 | 0 |
| Deployment orchestration | 22 | 0 |
| Kotlin deployment workspace → gerçek Python/ZIP/disk | 3 | 0 |
| Python agent, HTTP, process, upload sahipliği | 46 | 5 |
| Python config file helper | 19 | 7 |
| Python ZIP/journal/rollback/metadata helper | 40 | 4 |
| Toplam | 224 | 17 |

## Derleme ve gerçek host entegrasyonları

- Bağımsız JVM modülü üretim kaynaklarını `Sync` ile alır. **53 Kotlin dosyası** test edilen kopyalarla SHA-256 karşılaştırmasında eşleşti; source listesi JSON raporundadır.
- Android API 36 jar ve gerçek Hilt Android 2.57.2 qualifier jar ile `DeploymentEngine` URI/ContentResolver adaptörü, Termux bootstrap ve credentials adaptörleri derlendi. Bu gerçek API imza kontrolüdür; Android `ContentProvider` veya cihaz lifecycle'ı çalıştırılmaz. Tam Android Hilt component graph bu modülde üretilmez.
- Gerçek Kotlin HTTP taşıması, gerçek Python agent'a ZIP binary body gönderdi; byte round-trip ve silme doğrulandı. Büyük upload sırasında süreç poll'ları devam eder. 32 MiB body yazısını bloke eden gerçek localhost fixture'ında caller iptali bağlantıyı kapatır; cleanup aynı upload sahiplik kimliğini taşır.
- Python HTTP testleri token, canonical UUID, body/quota/timeout sınırları, partial cleanup, mevcut hedef koruması, upload nonce/receipt, yanlış nonce ile cleanup reddi, ownership map sınırı ve aynı anda ikinci yüklemeye hızlı 429 cevabını doğruladı. Malformed küçük PUT body hata yanıtları sınırlı drain ile Windows socket reset'e karşı kontrol edildi.
- Termux'un resmi Python build'i hardlink desteğini kapatır. Final upload commit'i bu nedenle hardlink kullanmaz; private partial fsync sonrası kilit altında directory descriptor'a bağlı rename yapar. `os.link` kapalıyken upload ve aktarım sırasında hedefin ortaya çıkması test edildi. [Resmi Termux Python build](https://github.com/termux/termux-packages/blob/master/packages/python/build.sh)
- Windows host fixture'ının uzun temp path'i partial dosya açmayı engelledi. Partial ad kompakt hale getirildi ve yalnız test home prefix'i kısaltıldı. Fixture strict path resolver adaptörü yalnız mevcut, fixture altında, reparse/symlink içermeyen test yollarına uygulanır; production POSIX doğrulaması korunur. Tanı logları yalnız exception türü, errno ve basename/line/function verir; bearer veya request body yazılmaz.
- Gerçek ShellExecutor→Python helper testleri Unicode/sarmalayıcı ZIP, staging activation, önceki deployment'i koruyan rollback, finalize, ilgisiz dosya koruması, traversal, eksik metadata ve archive kimliği reddini doğruladı. File operations APK UID'sinde başka uygulamanın dizinine doğrudan erişmez.
- Gerçek native Nginx üç entegrasyon testi bu fazda tekrar geçti. STATIC/React, proxy header/prefix ve config/reload revision doğrulanır. Upstream fixture'ları JVM HTTP sunucularıdır; gerçek Django/Node framework kurulumu değildir. Windows native Nginx fixture'ında path eşleme ve `disable_symlinks` çıkarılması yalnız host'a özgüdür. Executable kaynağı/hash'i [PHASE-4-VALIDATION.md](PHASE-4-VALIDATION.md) içinde korunur; binary teslim arşivine dahil değildir.
- Room v3 ve önceki migration testleri korundu; bu faz DB schema değiştirmez. `DatabaseMigrations.kt` Android adapter'ı JVM'e alınmaz; aynı SQL sabitleri gerçek Room/SQLite migration ile doğrulanır. Android `SiteMigrationTest` cihaz testi henüz çalıştırılmadı.

## ZIP güvenliği ve rollback doğrulaması

Python helper 44 vaka çalıştırdı. EOCD/comment/central offset, gerçek central header sayısı ve 4 MiB merkezi dizin limiti **ZipFile nesneleri oluşturulmadan önce** kontrol edilir. Sahte düşük EOCD count ile çok sayıda entry yerleştirme, ZIP64, multidisk ve bozuk merkezi dizin constructor öncesinde reddedilir. STORE/DEFLATE dışındaki sıkıştırmalar decompressor çağrılmadan reddedilir.

ZIP Slip, absolute/backslash/control adlar, symlink/special/encrypted metadata, duplicate/casefold ve file-directory çakışmaları, wrapper klasör, entry/adet/depth/boyut/ratio limitleri, CRC/gerçek byte sayısı ve framework giriş dosyaları test edildi. Journal activation ve rollback'in kesintiden sonra tekrarları, eski dizin restore sonucunun belirsiz kalması ve cleanup borcu doğrulandı. Açılan dosyalar özel izinlerle yazılır; gerçek POSIX izin/symlink kontrolleri skip kapsamındadır.

## Orchestration ve süreç kapasitesi

22 orchestration testi sahte command/process handle'ları ile **gerçek localhost TCP/HTTP listener'ları** kullanır. npm install/pip işlemleri, exit/timeout/cancellation ve DB/proxy hataları deterministik olarak enjekte edilir. Gerçek registry download, native dependency compilation veya framework build çalıştırılmaz.

Testler Node PORT/HOST, Python venv→pip→runserver sırası, venv'in final dizinde oluşturulması, React install/build, STATIC PID'siz commit, readiness timeout, fail-fast backend kapasitesi, unknown PID reddi, aynı kaynaklarla stop/start, stale watcher, unexpected exit ve live log DB failure davranışlarını kapsar.

Belirsiz DB claim/commit, reddedilen CAS'in başka işlemi koruması, proxy snapshot restore, install/caller iptalinde rollback, stop failure sonrası sahiplik/dosya koruması ve retry stop→abort sırası doğrulanır. Commit sonrası caller iptali başarılı RUNNING deployment'i geri almaz. Backup cleanup warning'i çalışan PID'yi korur; idempotent `retryCleanup` sonrasında stop/start yapılabilir.

ShellExecutor toplam sekiz process slot'una ek olarak en fazla yedi uzun süreç permit'i kullanır. Uzun süreç kuyruğu toplam permit almadığı için kalan slot kısa kontrol komutlarına ayrılır. Dört yeni gerçek-child testi bekleyen daemon sırasında kontrol komutu, iki farklı kapasite bekleme noktasındaki iptal ve launch failure permit iadesini doğruladı. Stdout/stderr gobbler'ları ayrı IO dispatcher görünümlerinde kalır.

## Yeniden çalıştırma

Proje kökünde:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:POCKETPAAS_TEST_PYTHON = 'C:\path\to\python.exe'
$env:POCKETPAAS_TEST_ANDROID_JAR = 'C:\path\to\android-sdk\platforms\android-36\android.jar'
$env:POCKETPAAS_TEST_HILT_JAR = 'C:\path\to\hilt-android-2.57.2-classes.jar'
$env:POCKETPAAS_TEST_NGINX = 'C:\path\to\nginx.exe'
.\gradlew.bat --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' -p .\verification\room-jvm test
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\termux-agent\test_agent.py
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\runtime-files\test_files.py
& $env:POCKETPAAS_TEST_PYTHON -B .\verification\deployment\test_deploy.py
```

Hilt jar, gerçek Maven `hilt-android:2.57.2` AAR'ın `classes.jar` dosyası veya Android Gradle'ın dönüştürdüğü runtime jar olabilir. SDK/Hilt jar'ları sağlanmazsa Android deployment adaptörü bu bağımsız derlemeye alınmaz. Python/Nginx yolu verilmezse ilgili entegrasyonlar açıkça skip edilir; bu doğrulamada hepsi sağlandı. Native executable'lar arşive eklenmez. Linux/macOS'ta uygun executable yolları ve `sh ./gradlew` kullanın.

Gradle test input'larına üç Python asset'i eklendi; asset değişikliği test task'ını geçersiz kılar. Sandbox incremental backup taşıması reddi nedeniyle in-process/non-incremental compiler kullanıldı; APK compiler ayarları değişmedi. Windows test temp dizini yazılabilir workspace'e alınır; gerekirse `TEMP`, `TMP` ve `PPAAS_TEST_TMP` değişkenlerini buna göre ayarlayın.

## Sınırlar ve APK

17 kontrol Windows üzerinde atlandı: Kotlin POSIX shell/layout; agent POSIX process-group/shell/port/symlink ve upload no-follow testi; file helper'ın yedi POSIX/FIFO/symlink kontrolü; ZIP helper'ın üç gerçek symlink ve bir POSIX private-mode kontrolü. Bu davranışlar Linux/Termux üzerinde hâlâ doğrulanmalıdır. Host adaptörleri production sandbox garantisi değildir.

Gerçek Android cihaz/emülatör kullanılmadı. Document picker/provider iptali, RUN_COMMAND izin/IPC, Termux Python/venv/npm uyumluluğu, cihaz portları, arka plan davranışı, tam Hilt graph, lint ve release imzası doğrulanmadı. Gerçek Cloudflare edge kullanılmadı. Django/Flask geliştirme sunucuları ve Quick Tunnel production garantisi sağlamaz; bu sınırlar [DEPLOYMENT-RUNTIME.md](DEPLOYMENT-RUNTIME.md) içinde açıklanır.

Cold app restart'ta otomatik process/journal reconciliation bu fazda uygulanmaz. Agent lease ve disk journal'ları koruma sağlar; kaldığı yerden otomatik recovery yapıldığı iddia edilmez. Upload alanı sekiz dosyayla sınırlıdır. Backup'lar için toplam disk kotası yoktur; cleanup borcu uygulama açıkken API üzerinden retry edilebilir, hard kill sonrası lifecycle recovery Faz 6 kapsamındadır.

**APK üretilmedi.** Resmi AGP ve SDK AAPT2 ZIP/APK yazımı önceki fazda native `0xC0000005` ile çöktü. Bu faz aynı native adımı yeniden denemedi; bağımsız Kotlin, Python, HTTP ve Nginx doğrulamaları bu araçtan bağımsız yürütüldü. Önceki kanıtlar [VALIDATION.md](VALIDATION.md) ve faz raporlarında korunur. Kaynak ZIP'i APK olarak sunulmaz.

Faz 5 tamamlandı. Faz 6 Foreground Service ve authenticated Ktor API kullanıcı onayını bekler.
