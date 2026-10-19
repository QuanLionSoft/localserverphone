# Kolay kurulum — 0.2.0-debug doğrulaması

18 Ekim 2026, Windows/JDK 17/Android API 36. Yeni APK: **PocketPaaS-KolayKurulum-debug.apk**, versionCode **2**, application ID `dev.pocketpaas.debug`. İlk açılışta üç adımlı kurulum ekranı gösterilir. Telefonda kullanım [KOLAY-KURULUM.md](KOLAY-KURULUM.md) içindedir.

## Değişen kurulum

- Uygulama Termux indirme/açma düğmelerini, tek komut kopyalamayı ve Android izin adımını aynı ekranda sunar. Dosya düzenlemek ve ayrı ayrı paket komutları yazmak gerekmez. Termux'a ilk kurulumda bir kez yapıştırma gerekir.
- Komut `pkg update` ve paket kurulumunu yürütür; Python/Nginx/Node/cloudflared araçlarını kurar, mevcut Termux ayarlarını koruyarak `allow-external-apps` ayarını açar ve ayarları yeniler. Paket veya reload hatasında başarı mesajı verilmez. Komut yeniden çalıştırılabilir; internetten shell script indirmez ve root istemez.
- `Kontrol et ve başlat`, gerçek authenticated Termux bağlantısını ve Python/Nginx/Node/npm/cloudflared binary'lerini kontrol eder. Eksik araçları bildirir; kimlik doğrulama/transport hatalarını eksik paket gibi göstermez. Kontrol toplam 30 saniye ile sınırlıdır; çalışan projeleri sıfırlamaz veya durdurmaz.
- Yalnız açık kullanıcı eylemi sunucu başlatma isteği oluşturur. İptal edilmiş/gecikmiş doğrulama sonucu başlatamaz. Kurulum tamamlandı tercihi ancak gerçek doğrulama, foreground API ve Nginx çalıştıktan sonra kaydedilir. Sonraki açılışlar paneli gösterir; panelde `Kolay kurulum` düğmesi kalır.
- Android komut izni kalıcı reddedilmişse düğme uygulama ayarlarına yönlendirir. Notification izni ve mevcut foreground servis kontrolleri korunur. USB hata ayıklaması/ADB kurulum için gerekli değildir. Termux'un zorunlu izin/ayar sözleşmesi [resmî belgede](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) açıklanır.

## Çalıştırılan kontroller

| Kontrol | Test | Geçen | Atlanan | Hata |
|---|---:|---:|---:|---:|
| Bağımsız Kotlin/JVM, gerçek Room/SQLite, HTTP/süreç/Nginx | 208 | 206 | 2 | 0 |
| Android modülü JVM birim testleri | 149 | 148 | 1 | 0 |
| Yeni kurulum komutu Python/native shell testleri | 20 | 18 | 2 | 0 |

İki JVM çalışmasındaki **119 ortak test** ikinci kez sayılmaz. Bu değişiklikte çalıştırılan tekil toplam **258 test: 254 geçti, 4 atlandı, 0 hata**. Faz 7'de çalıştırılan üç Python yardımcı paketinin **131 testi (115 geçti, 16 atlandı)** tekrar çalıştırılmadı; üretim asset dosyalarının önceki doğrulanmış SHA-256 değerleriyle aynı olduğu kontrol edildi. Bu önceki kanıt da dahil edildiğinde **389 tekil test: 369 geçti, 20 atlandı, 0 hata**. Ayrım ve her suite sonucu [verification/simple-setup-results.json](verification/simple-setup-results.json) içindedir.

Yeni kuruluma özel **43 testin 41'i geçti, 2'si atlandı**: 2 Kotlin komut testi, 7 Termux doğrulama testi, 14 SetupViewModel testi ve 20 Python/native shell testi. Testler gerçek hata türü/mesajı/iptal korunmasını, yanlış kimlik bilgisi sızdırmama, tek kullanımlık başlatma isteklerini, geç sonuçların reddini ve tercih saklama hatalarını kapsar. Coroutine stack recovery hata nesnesini kopyalayabildiğinden testler aynı nesne yerine tür/mesaj/özgün neden korunmasını doğrular; üretim hata akışı değiştirilmedi.

Kurulum komutunun aynı byte içeriği native Windows BusyBox `sh` ile çalıştırıldı. Beş tam shell testi başarı, update/install/reload hatalarını ve komutun doğru shell quoting davranışını doğrular. `pkg` ve runtime executable'ları fixture'dır; bilgisayara paket kurulmadı, gerçek Termux deposundan indirme yapılmadı. Python testleri yorumlar, yinelenen/escaped/continued property anahtarları, CRLF, atomik değişim, UTF-8 ve boyut sınırlarında mevcut ayarları korumayı kapsar. İki symlink kontrolü Windows yetkisi olmadığından atlandı.

Shell test aracı yalnız bu host doğrulamasında kullanıldı; APK/kaynak ZIP'ine binary eklenmedi. Araç [dağıtıcısından](https://frippery.org/files/busybox/busybox64.exe) indirildi; SHA-256: `07bb1e5b095b00d68a695481f9240879f33c5724b40aa2308f999d54ed78f075`.

## APK ve Android derlemesi

Gerçek Android kaynakları/Compose compiler/Room KSP/Hilt graph/D8 derlemesi ve `assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` görevleri geçti. Kotlin `allWarningsAsErrors` ve lint hata denetimi açık kaldı. **Lint 0 hata, 38 uyarı**: 29 sürüm önerisi, 3 Termux sabit yolu, 4 KTX stil ve 2 Compose modifier parametresi önerisi. Uyarılar bastırılmadı; bağımlılıkların en yeni sürümler olduğu iddia edilmez.

APK **17.677.671 bayt**. SHA-256:

`8e00fc8625e3da8d3837ac0d3f7307218389976846257db28d5d94f18c8f9225`

Resmî apksigner APK v2 debug imzasını, resmî zipalign 4-byte/16-KiB hizalamayı, resmî AAPT2 manifest/kaynak metaverisini doğruladı. Sertifika önceki teslimle aynıdır; versionCode 2 güncelleme olarak kullanılabilir. ZIP CRC, sıkıştırılmamış kaynak tablosu, 19 DEX bütünlüğü, yeni kurulum ekranı/ViewModel/verifier/komut sınıfları, üç Python asset'inin kaynaklarla aynı byte'ları taşıması ve dört ABI'nin ELF PT_LOAD 16 KiB hizalaması geçti. Yeniden çalıştırılabilir [APK araçları](tools/apk-verification/README.md) kaynaklardadır.

111 üretim dosyasının hash'leri makine raporundadır. Bağımsız modülün derlediği 86 gerçek Kotlin dosyası teslimdeki kaynaklarla hash üzerinden eşleştirildi. Windows AAPT2 ZIP crash çözümü ve normal AGP derleme akışı önceki [Faz 7 raporunda](PHASE-7-VALIDATION.md) açıklanır.

## Sınırlar ve tekrar çalıştırma

24 instrumentation testi derlendi ve test APK'sı üretildi; **cihazda çalıştırılmadı**: 6 yeni kurulum Compose testi, 9 Dashboard testi, 6 Android anahtar deposu, 3 Room testi. Gerçek Android/Termux kurulumu, Android izin ekranları, clipboard→Termux yapıştırma, gerçek `pkg` kurulumu, foreground/Doze/OEM davranışı, npm/pip framework kurulumu ve Cloudflare edge bu değişiklikte cihaz üzerinde doğrulanmadı. Debug sürümüdür; release küçültme/imzası test edilmedi. Quick Tunnel ve geliştirme runner sınırları korunur.

JDK 17, SDK 36 ve uygun Python/Nginx executable yollarıyla proje kökünde:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat -p .\verification\room-jvm test
python -B -m unittest discover -s .\verification\simple-setup -v
```

Son komutta `sh` bulunmalıdır; Windows'ta test için `POCKETPAAS_TEST_SH` native BusyBox executable yoluna ayarlanabilir. Linux/macOS'ta standart `sh` kullanılır. Python/Nginx/Android/Hilt jar değişkenleri için [Faz 7 tekrar çalıştırma](PHASE-7-VALIDATION.md#tekrar-çalıştırma) açıklaması geçerlidir. Cihaz testleri için ayrıca `:app:connectedDebugAndroidTest` gerekir. Faz 1–7 kaynak arşivleri ve tarihsel raporlar değiştirilmedi.
