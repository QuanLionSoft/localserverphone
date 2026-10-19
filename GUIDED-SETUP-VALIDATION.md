# Rehberli kurulum — 0.3.0-debug doğrulaması

18 Ekim 2026, Windows/JDK 17/Android API 36. APK **PocketPaaS-RehberliKurulum-debug.apk**, versionCode **3**, application ID `dev.pocketpaas.debug`. Kullanım [KOLAY-KURULUM.md](KOLAY-KURULUM.md); makine tarafından üretilen kanıt [verification/guided-setup-results.json](verification/guided-setup-results.json).

## Düzeltilen davranış

Kullanıcının Android 12 + Play Store Termux kurulumu için önceki izin yönlendirmesi yeterli değildi. İncelenen [Play Store dalının manifestinde](https://github.com/termux-play-store/termux-apps/blob/main/termux-app/src/main/AndroidManifest.xml) uygulamanın kullandığı `RUN_COMMAND` izni ve `RunCommandService` bulunmuyor. Bu durumda bir izin penceresi beklemek veya aynı ayarı yeniden aramak bağlantıyı kuramaz. [Resmî Termux bağlantı sözleşmesi](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) ve [kurulum kaynakları](https://github.com/termux/termux-app#installation) esas alındı.

- AndroidSystemManager gerçek paket, etkinlik durumu, izin tanımının sahibi/koruma seviyesi ve exported/protected komut servisini denetler. Tanımsız veya kullanılamayan bağlantı artık hazır sayılmaz. Eksik, devre dışı, uyumsuz, izin reddedilmiş ve izin verilmiş durumları ayrıdır.
- Kurulum dört sayfadır: Termux, İzin, Araçlar, Kontrol. Sayfa düğmeleri ve Geri/İleri gezinmesi sabittir. İzin iste ve Pocket PaaS ayarlarını aç, izin sayfasının altında görünür kalır. Eksik/devre dışı/uyumsuz Termux için izin isteme devre dışıdır; mevcut durumun çözümü açıklanır.
- Destek eksikliğinde F-Droid/resmî GitHub seçeneği gösterilir. Kaynak değişiminden önce Termux dışına yedekleme açıklanır. Otomatik kaldırma veya veri silme yapılmaz. Komut izninin Pocket PaaS'a verildiği, Termux ayarlarından farklı olduğu belirtilir.
- Termux'u açmak, komutu kopyalamak, yapıştırmak ve Enter ile çalıştırmak ayrı eylemlerdir. Araç kurulumu ve servislerin gerçekten çalışması ayrı doğrulanır. API, Nginx ve tünel durumları ayrı gösterilir. Ekranda sürüm numarası vardır.
- Araç kontrolünden hemen önce komut izni yeniden okunur. Denetim hatası veya iptal, başlatma isteği üretmez. Activity yalnız RESUMED durumunda tek kullanımlık isteği tüketir; ekrandan ayrılma bekleyen kontrolü/başlatmayı iptal eder. İzin ayarlarından dönüş yalnız yenilemedir.
- Kurulum tercihi, doğrulama + foreground API + Nginx çalıştıktan sonra kaydedilir. Ekran ancak kayıt başarılıysa otomatik kapanır. Önceki sürümden güncellemede yeni rehber yeniden gösterilir; projeler silinmez.

## Çalıştırılan kontroller

| Kontrol | Test | Geçen | Atlanan | Hata |
|---|---:|---:|---:|---:|
| Bağımsız Kotlin/JVM, gerçek Room/SQLite, HTTP/süreç/Nginx | 224 | 222 | 2 | 0 |
| Android modülü JVM birim testleri | 173 | 172 | 1 | 0 |
| Kurulum komutu Python/native shell | 20 | 18 | 2 | 0 |

İki JVM çalışmasında ortak **135 test** ikinci kez sayılmaz. Bu değişiklikte çalıştırılan tekil toplam **282: 278 geçti, 4 atlandı, 0 hata**. Faz 7'deki değişmeyen Python runtime yardımcılarının **131 testi (115 geçti, 16 atlandı)** tekrar çalıştırılmadı; üç üretim asset'inin SHA-256 değerlerinin önceki doğrulanmış içerikle aynı olduğu denetlendi. Önceki kanıtla toplam **413 tekil test: 393 geçti, 20 atlandı, 0 hata**.

Kuruluma özel **67 test: 65 geçti, 2 atlandı**. Yeni 24 JVM testi; 8 izin kanıtı çözümü, 8 foreground başlatma kapısı ve 8 SetupViewModel izin/lifecycle durumu kapsar. İzin eksikliği, desteklenmeyen Play Store bağlantısı, eski granted bilgisinin yeniden doğrulanması, izin iptali, inceleme hatası, geç sonuç, ekranın gizlenmesi ve otomatik başlatmayan yenileme denetlenir. Toplam 22 SetupViewModel testi gerçek Android lifecycle bağımlılıklarıyla geçti.

Native Windows BusyBox `sh`, teslim edilen kurulum komutunun aynısını çalıştırdı. `pkg` ve runtime komutları izole fixture'lardır; host'a paket kurulmadı. Beş tam pipeline senaryosu ve property dosyası koruma kontrolleri geçti. İki symlink testi Windows ayrıcalığı olmadığı için atlandı. Araç önceki doğrulanmış [dağıtıcı binary'si](https://frippery.org/files/busybox/busybox64.exe), SHA-256 `07bb1e5b095b00d68a695481f9240879f33c5724b40aa2308f999d54ed78f075`; APK veya kaynak arşivinde bulunmaz.

## Android derlemesi ve APK

`assembleDebug`, `testDebugUnitTest`, `lintDebug`, `assembleDebugAndroidTest` geçti. Gerçek Compose compiler, Hilt graph, Room KSP ve D8 kullanıldı. Kotlin `allWarningsAsErrors` ve lint hata denetimi açık kaldı. **Lint 0 hata, 37 uyarı**; gruplar makine raporundadır. Uyarılar bastırılmadı.

APK **17,790,588 bayt**. SHA-256:

`68c8d781dcb6220933c8807e47ae644ce61ca6f3a8799f22be76b2815b0db394`

Resmî apksigner debug imzasını, zipalign 4-byte/16-KiB hizalamayı, AAPT2 manifest ve kaynak metaverisini doğruladı. Önceki teslimle aynı debug sertifikası kullanıldı; versionCode 3 güncelleme olarak kurulabilir. ZIP CRC, kaynak tablosu, DEX bütünlüğü, yeni kurulum/izin/başlatma sınıfları, runtime asset byte'ları ve native ELF PT_LOAD hizalaması geçti. [Yeniden çalıştırılabilir APK araçları](tools/apk-verification/README.md) kaynaklardadır. Host AAPT2 ZIP crash çözümü ve standart AGP akışının ayrıntısı [Faz 7 raporunda](PHASE-7-VALIDATION.md) korunur.

113 üretim dosyasının hash'i raporda bulunur. Bağımsız modülün derlediği 88 gerçek Kotlin kaynak dosyası teslimdeki dosyalarla eşleştirildi.

## Cihazda doğrulanmayanlar

**35 instrumentation testi derlendi; cihazda çalıştırılmadı.** Bunların 17'si yeni kurulum Compose ekranı içindir. Eksik/granted/uyumsuz/devre dışı Termux, sabit izin düğmeleri, kaydırma, adım geçişleri, açık kullanıcı eylemleri, hata ve kısmi API/Nginx/tünel durumları kapsama dahil; bunlar derlenmiş test senaryolarıdır.

Gerçek Android 12 Play Store/F-Droid geçişi, sistem izin pencereleri, Termux'a yapıştırma, gerçek `pkg` indirmesi, foreground/Doze/OEM davranışı, npm/pip framework kurulumu ve Cloudflare edge bu ortamda cihaz üzerinde çalıştırılmadı. Debug APK'dır; release küçültme/imzası doğrulanmadı. Uyumlu Termux gereksinimi devam eder; bu sürüm Play Store dalına eksik komut servisini eklemez.

## Tekrar çalıştırma

JDK 17, SDK 36 ve uygun Python/Nginx yollarıyla proje kökünde:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat -p .\verification\room-jvm test
python -B -m unittest discover -s .\verification\simple-setup -v
```

Host bağımlılıkları ve ortam değişkenleri [Faz 7 raporunda](PHASE-7-VALIDATION.md#tekrar-çalıştırma) açıklanır. Shell kontrolleri için Windows'ta `POCKETPAAS_TEST_SH` native BusyBox yoluna ayarlanabilir; Linux/macOS'ta `sh` yeterlidir. Cihaz testleri için ayrıca `:app:connectedDebugAndroidTest` gerekir. Önceki faz ve 0.2.0 arşivleri korunmuştur; [önceki kolay kurulum raporu](EASY-SETUP-VALIDATION.md) tarihseldir.
