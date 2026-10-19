# Süreç düzeltmesi — 0.4.0-debug doğrulaması

18 Ekim 2026, Windows/JDK 17/Android API 36. APK **PocketPaaS-SurecDuzeltmesi-debug.apk**, versionCode **4**, application ID `dev.pocketpaas.debug`. Makine kanıtı [verification/runtime-fix-results.json](verification/runtime-fix-results.json); telefon kurulumu [KOLAY-KURULUM.md](KOLAY-KURULUM.md).

## Kullanıcının bildirdiği sorun

Ekran görüntülerinde `Reading STDERR for <session> failed` ve ardından `Server shutdown is incomplete. Retry Stop.` görünüyor. Nginx durmuşken tünel hata durumunda kalmış. Bu mesajlar tek başına Cloudflare ağının nedenini kanıtlamıyor; ilk sürümde alttaki okuma hatasının nedeni üst mesajda görünmüyordu. Telefonun orijinal iç exception'ı alınmadığı için bu cihazdaki ilk kopuşun kesin nedeni hâlâ doğrulanmış değildir.

Kaynak incelemesi iki yeniden üretilebilir kusur ortaya çıkardı. Termux adapter'ı coroutine üreticileri arasında Java `PipedInputStream` kullanıyordu. Bu API son yazan gerçek iş parçacığının ömrünü izler; coroutine üreticisi beklerken iş parçacığı sonlanırsa açık bir akış için de `Write end dead` oluşabilir. Bu davranış JDK üzerinde yeniden üretildi ve [Android API sözleşmesiyle](https://developer.android.com/reference/java/io/PipedInputStream) doğrulandı. İkinci kusur, `ManagedProcess.stop()` çağrısının eski başarısız deferred sonucu tekrar beklemesi; gerçek durdurmayı yeniden doğrulayamamasıydı. Bu da NetworkManager'ın sahibi tutmasına ve kapanışın sürekli eksik kalmasına yol açabiliyordu.

## Düzeltmeler

- Java Piped akışları yerine iki ayrı 64 KiB sabit ring tamponu kullanılır. stdout/stderr bağımsız IO okuyucu ve üreticilerini korur; sabit kapasiteli kuyruklar geri basınç uygular. Üretici iş parçacığı değişimi veya sessizlik sahte kopuş oluşturmaz. EOF ve gerçek hata, bekleyen veriden sonra aktarılır; close/failure bekleyen okuma/yazmaları uyandırır.
- `await()` orijinal işlem hatasını korur. `stop()` bunun dışında sahip olunan gerçek termination işlemini seri olarak yeniden doğrular. Doğrulanmış durdurma `STOPPED` sonucudur; başarılı çalıştırma sayılmaz. Başarılı onay saklanır, eşzamanlı/tekrarlı durdurmalar aynı onayı kullanır. Onay alınmazsa sahiplik korunur ve sonraki deneme gerçekten yeniden yapılır.
- Termux stop yanıtında HTTP başarısı yanında geçerli, null olmayan `exitCode` istenir. Adapter başarısız stop sonucunda owned ticket'ı bırakmaz. Zamanı dolan process lease'i son korumadır; bilinmeyen PID'ler öldürülmez.
- Nginx ve Cloudflare'ın kendi başlangıç süreleri dolduğunda durum `FAILED` ve aşamaya özgü açıklama olur. Kullanıcının veya dış scope'un iptali ayrı kalır. Tünelde URL bekleme ve edge readiness bekleme hataları ayrıdır.
- Okuma hata mesajı artık exception türünü ve sınırlandırılmış nedenini içerir. Panel hata metinleri seçilip kopyalanabilir; kapanış açıklaması Türkçedir. Panelde uygulama sürümü görünür.
- Önceki OpenSSL/Nginx linker hatası için kurulum `pkg update → pkg upgrade → pkg install` sırasını kullanır. Python, Nginx, Node, npm ve cloudflared gerçekten sürüm komutlarıyla çalıştırılır. Mevcut ama linklenemeyen Nginx için kurulum başarılı sayılmaz. Tüm kurulu Termux paketlerinin yükseltildiği komut ekranında açıklanır; komut yalnız kullanıcı kopyalayıp Termux'ta çalıştırırsa uygulanır. [Termux tam paket güncelleme sözleşmesi](https://github.com/termux/termux-packages/wiki/Termux-execution-environment#dynamic-library-linking-errors).

## Çalıştırılan testler

| Kontrol | Test | Geçen | Atlanan | Hata |
|---|---:|---:|---:|---:|
| Bağımsız Kotlin/JVM, Room/SQLite, HTTP, süreç, Nginx | 247 | 245 | 2 | 0 |
| Android modülü JVM testleri | 185 | 184 | 1 | 0 |
| Kurulum komutu Python/native shell | 23 | 21 | 2 | 0 |

JVM çalışmaları arasındaki **147 ortak test** yalnız bir kez sayılır. Bu turda **308 tekil test: 304 geçti, 4 atlandı, 0 hata**. Önceki 0.3.0 teslimine göre test sayısı 26 arttı. Faz 7'deki değişmemiş üç Python runtime asset'inin 131 testi (115 başarılı, 16 atlanan) yeniden çalıştırılmadı; SHA-256 değerlerinin önceki doğrulanmış içerikle ve APK byte'larıyla aynı olduğu doğrulandı. O önceki kanıt dahil toplam **439 tekil test: 419 başarılı, 20 atlanan, 0 hata**.

Süreç ve ağ regresyonları:

| Suite | Test | Geçen | Atlanan | Hata |
|---|---:|---:|---:|---:|
| NetworkManagerTest | 23 | 23 | 0 | 0 |
| ShellExecutorTest | 21 | 21 | 0 | 0 |
| BoundedProcessInputStreamTest | 11 | 11 | 0 | 0 |
| RemoteProcessOwnershipTest | 3 | 3 | 0 | 0 |

Bu gruplardaki 22 yeni test; biten/değişen üretici thread, sessiz bekleme, ring sarımı/kapasite/geri basınç, gerçek hata, bekleyen tüketici/üreticinin kapanışı, HTTP stop onayı/yeniden deneme, eski başarısız await'in korunması, eşzamanlı Stop, Nginx/Cloudflare deadline ayrımı ve başarısız cleanup sonrası owner korunmasını kapsar. 11 tampon ve 3 HTTP adapter testi ayrıca CLI JUnit ile de geçti; bu ikinci çalışma toplama tekrar eklenmedi.

Kurulum komutu 23 host testiyle kontrol edildi: 21 geçti, iki Windows symlink ayrıcalık testi atlandı. Gerçek teslim komutu native BusyBox `sh` içinde çalıştırıldı; `pkg` ve runtime binary'leri fixture'dır. Update, tam upgrade, install ve beş executable smoke kontrolünde hata, ayar değişimi ve başarı mesajından önce durur. Bildirilen `SSL_set_quic_tls_cbs` linker hatası fixture ile yeniden üretildi. Host'a gerçek paket kurulmadı; gerçek Termux deposu veya telefon kütüphanesi bu host testinde değiştirilmedi.

## APK ve derleme

`assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` geçti. Gerçek Compose compiler, Hilt graph, Room KSP ve D8 kullanıldı; Kotlin `allWarningsAsErrors` açık kaldı. Lint **0 hata, 37 uyarı**; gruplar makine raporundadır. Uyarılar bastırılmadı.

APK **17,726,823 bayt**, SHA-256:

`4f5d2f2a505f9a87b60ddfb826f11123e90bc862999af4456b995a1666510deb`

Resmî apksigner APK v2 debug imzasını, zipalign 4-byte/16-KiB hizalamayı, AAPT2 manifest/kaynak metaverisini doğruladı. Önceki APK ile aynı debug sertifikası kullanılır; versionCode 4 mevcut uygulama üzerine güncelleme olarak kurulabilir. ZIP CRC, DEX checksum'ları, yeni bounded stream/adapter/executor sınıfları, Python asset byte'ları ve native ELF PT_LOAD hizalamaları kontrol edildi. [APK doğrulama araçları](tools/apk-verification/README.md) ve [Windows AAPT2 host çözümü](PHASE-7-VALIDATION.md) kaynaklarda korunur.

114 üretim dosyasının hash'i rapordadır; portable modülün derlediği 89 Kotlin dosyası gerçek teslim kaynaklarıyla eşleştirildi. Kaynak arşivi build/cache, yerel SDK yolları, keystore veya token dosyaları içermez. Önceki 0.3.0 APK/arşivi ile diğer faz arşivleri hash denetimiyle korunur.

## Sınırlar ve kullanım

**35 instrumentation testi derlendi, cihazda çalıştırılmadı.** Android 12 cihazda güncelleme sonrası gerçek Termux/coroutine/Doze davranışı, sistem izin ekranı, paket indirmeleri ve Cloudflare edge bağlantısı bu ortamda doğrulanmadı. Testler stream ve cleanup kusurlarını yeniden üretir; her gerçek ağ kopuşunun ortadan kalktığı iddia edilmez. Gerçek stop onayı gelmiyorsa hata durumu korunur. Debug APK'dır; release küçültme/imzası doğrulanmadı.

Güncellemeden sonra panelde **0.4.0-debug** sürümünü kontrol edin. **Kolay kurulum → Araçlar** üzerinden güncel komutu Termux'ta çalıştırıp **Kontrol et ve başlat** adımını kullanın. Bu komut tüm kurulu Termux paketlerini yükseltir. Hata tekrarlanırsa metne basılı tutup seçerek yeni hata nedenini paylaşın. [Kurulum rehberi](KOLAY-KURULUM.md).

Tekrar çalıştırma:

```powershell
.\gradlew.bat -p .\verification\room-jvm test
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
python -B -m unittest discover -s .\verification\simple-setup -v
```

Host ortam değişkenleri [Faz 7 raporunda](PHASE-7-VALIDATION.md#tekrar-çalıştırma) açıklanır. Native shell için Windows'ta `POCKETPAAS_TEST_SH` BusyBox yoluna ayarlanabilir; Linux/macOS'ta `sh` kullanılır. Gerçek cihaz testleri ayrıca `:app:connectedDebugAndroidTest` gerektirir. [Önceki 0.3.0 raporu](GUIDED-SETUP-VALIDATION.md) kendi tesliminin tarihsel kanıtıdır.
