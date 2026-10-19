# Pocket PaaS — Telefonda adım adım kurulum

Bu rehber **0.5.0-debug** sürümü içindir. [Yeni APK'yı indirin](../PocketPaaS-SurecDuzeltmesi-debug.apk). Telefon üzerinde kurulur; bilgisayar, USB, ADB veya root gerekmez. Android 8.0 ve üzeri, internet ve uyumlu Termux gerekir. APK'yı kurup açın; ekranın sağ üstünde **0.5.0** yazdığını kontrol edin. Eski sürümü kullanıyorsanız yeni APK'yı mevcut Pocket PaaS'ın üzerine güncelleyin; kurulum için uygulama verilerini silmeyin (Room v3 → v4 migration mevcut verilerinizi korur).

Kurulum dört ayrı sayfadır: **1 · Termux → 2 · İzin → 3 · Araçlar → 4 · Kontrol**. Üstteki adlara dokunabilir veya alttaki **Geri / İleri** düğmelerini kullanabilirsiniz. İleri düğmesi yalnız sayfayı değiştirir. Aşağıdaki adımlar bitmeden sunucu kendiliğinden başlamaz.

## 1 · Termux

**Termux indirme sayfasını aç**, önerilen [F-Droid Termux sayfasına](https://f-droid.org/packages/com.termux/) gider. Alternatif kurulum kaynağı [resmî Termux GitHub sayfasıdır](https://github.com/termux/termux-app#installation). Termux'u kurun, **Termux’u aç** düğmesine dokunun ve ilk hazırlık bitip komut satırı görünene kadar bekleyin. Son uygulamalar ekranından Pocket PaaS'a dönün. **Termux’u yeniden kontrol et** ve ardından **İleri · İzin** seçin.

**Android 12+ ve Play Store'dan kurulmuş Termux kullanıyorsanız:** Play Store dalının 18 Ekim 2026'da incelenen [manifestinde](https://github.com/termux-play-store/termux-apps/blob/main/termux-app/src/main/AndroidManifest.xml) Pocket PaaS'ın kullandığı `RUN_COMMAND` izni ve `RunCommandService` servisi bulunmuyor. Bu durumda Android ayarlarında o izni görememeniz beklenir. Pocket PaaS bunu **Bu Termux sürümünde komut izni bulunmuyor** olarak gösterir; eksik özelliği bir Android izni açarak ekleyemezsiniz.

Play Store sürümünden F-Droid sürümüne geçiş sırası:

1. Önce Termux dosyalarınızı **Termux'un özel depolaması dışında** erişebildiğiniz bir yere yedekleyin. Yedeğin mevcut olduğunu ve dosyaların açılabildiğini kontrol edin. Yedek doğrulanmadan kaldırma adımına geçmeyin.
2. Farklı kaynakların imzaları uyuşmayabildiği için mevcut Termux'u ve varsa Termux eklentilerini **Android Ayarlar → Uygulamalar** bölümünden elle kaldırmanız gerekebilir. **Kaldırma, ilgili uygulamanın özel alanındaki dosya ve verileri siler.** Yalnız doğrulanmış yedekten sonra ilerleyin; Pocket PaaS otomatik kaldırma veya veri temizleme yapmaz.
3. [F-Droid Termux sayfasını](https://f-droid.org/packages/com.termux/) açın. **Önerilen / Suggested** kararlı sürüm bölümündeki **Download APK** bağlantısını kullanın. F-Droid uygulamasını ayrıca kurmanız gerekmez. [Resmî kurulum ve kaynak değiştirme açıklaması](https://github.com/termux/termux-app#installation).
4. İndirilen Termux APK'sını açıp kurun. Android isterse APK'yı açtığınız tarayıcıya veya dosya uygulamasına kurulum izni verin. Termux eklentilerini daha sonra kuracaksanız aynı kaynaktan alın.
5. Termux'u bir kez açıp ilk hazırlığın bitmesini bekleyin. Pocket PaaS'a dönün, **2 · İzin** sayfasını açın ve **İzin durumunu yeniden kontrol et** düğmesine dokunun.

## 2 · İzin

Bu sayfanın altındaki **İzin iste** ve **Pocket PaaS ayarlarını aç** düğmeleri kaydırmadan erişilebilir. İzin, **Pocket PaaS'a** verilir; Termux'un uygulama izinlerinde aranmaz. Bildirim veya depolama izninden ayrıdır.

1. Durum **Henüz izin verilmedi** ise **İzin iste** düğmesine dokunun. Android bir pencere açarsa **İzin ver** seçin.
2. Pencere açılmazsa **Pocket PaaS ayarlarını aç** seçin. Açılan Android sayfasının adı **Pocket PaaS** olmalıdır. **İzinler → Ek izinler / Diğer izinler → Termux ortamında komut çalıştırma / Run commands in Termux environment** yolunu kontrol edin. Bölüm adı telefona göre değişebilir.
3. Pocket PaaS'a geri dönüp **İzin durumunu yeniden kontrol et** düğmesine dokunun. **İzin verilmiş** görünce **İleri · Araçlar** seçin.

| Ekrandaki durum | Yapılacak işlem |
|---|---|
| İzin verilmiş | İzin zaten açıktır; yeni pencere açılması gerekmez. 3. adıma geçin. |
| Bu Termux sürümünde komut izni bulunmuyor | **Uyumlu Termux indirme sayfasını aç** seçin ve 1. adımdaki yedekleme/kaynak açıklamasını izleyin. Bu durumda İzin iste devre dışıdır. |
| Termux devre dışı | **Termux uygulama bilgisini aç** ile etkinleştirin; Termux'u açıp tekrar kontrol edin. |
| Önce Termux yüklenmeli | 1. adıma dönün. Termux kurulmadan bu izin Android'de görünmeyebilir. |
| İzin kontrol ediliyor | Kontrolün bitmesini bekleyin; sonuç okunamazsa yeniden kontrol edin. |

**İzin görünmüyor / pencere açılmıyor** düğmesi aynı sayfada ek açıklama açar. Android, daha önce reddedilmiş bir izin için yeni pencere göstermeyebilir; bu nedenle Pocket PaaS'ın uygulama ayarlarına doğrudan giden düğme de vardır. [Resmî Termux izin açıklaması](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

## 3 · Araçlar

1. **Kurulum komutunu kopyala** düğmesine dokunun.
2. **Termux’u aç** seçin. Siyah terminal ekranındaki komut satırına uzun basın ve **Paste / Yapıştır** seçin.
3. Komutu çalıştırmak için klavyedeki **Enter** tuşuna basın. **Termux’u aç**, komutu otomatik yapıştırmaz veya çalıştırmaz.
4. Termux'u kapatmadan **Kurulum tamamlandı.** mesajını bekleyin. İndirmeler birkaç dakika sürebilir. Son uygulamalardan Pocket PaaS'a dönüp **İleri · Kontrol** seçin.

**Bu komut, kurulu tüm Termux paketlerini günceller.** Önce paket listesi yenilenir (`pkg update`), sonra tam paket güncellemesi yapılır (`pkg upgrade`); ardından Python, Nginx, Node.js/npm ve cloudflared yüklenir. Yalnız komut dosyalarının varlığına bakılmaz: `python --version`, `nginx -v`, `node --version`, `npm --version` ve `cloudflared --version` gerçekten çalıştırılır. Bu kontrollerden biri hata verirse komut **Kurulum tamamlandı** yazmaz ve bağlantı ayarını değiştirme adımına geçmez.

Kontroller geçtikten sonra mevcut Termux ayarları korunarak `allow-external-apps=true` bağlantı ayarı hazırlanır. Bu ayar, 2. adımdaki Android izninin yerine geçmez. **Komut kopyalandı** yazısı, komutun çalıştığını veya araçların kurulduğunu kanıtlamaz. [Resmî Termux paket yönetimi açıklaması](https://github.com/termux/termux-packages/wiki/Package-Management).

**Kurulum tamamlanamadı** görürseniz mesajın üstündeki hata satırlarını okuyun. İnternet veya paket hatasını giderdikten sonra aynı komutu yeniden çalıştırabilirsiniz. Kopyalanacak içeriği incelemek için **Kopyalanacak komutu göster** seçeneği vardır.

### Nginx için `SSL_set_quic_tls_cbs` / `CANNOT LINK EXECUTABLE` hatası

Bu hata Nginx'in Termux içindeki kitaplıkları yüklerken oluşur; Nginx ve OpenSSL paket sürümleri uyumsuz olabilir. Termux'u açın ve aşağıdaki komutu yapıştırıp **Enter** tuşuna basın. Bu onarım da **kurulu tüm Termux paketlerini günceller**, ardından OpenSSL ve Nginx paketlerini yeniden kurar:

```sh
pkg update -y && pkg upgrade -y && pkg reinstall -y openssl nginx && nginx -v
```

`&&` nedeniyle bir adım hata verirse sonraki adım çalışmaz. Sonunda `nginx version: nginx/...` görülmeli ve bağlayıcı hatası kalmamalıdır. Hata devam ederse Termux'taki hata satırlarını kaydedin; başarı mesajı görmüş gibi ilerlemeyin. Nginx çalıştıktan sonra Pocket PaaS'a dönün ve **4 · Kontrol → Kontrol et ve başlat** ile yeniden deneyin. Kurulum komutu henüz tamamlanmadıysa **3 · Araçlar** sayfasından yeni komutu kopyalayıp Termux'ta yeniden çalıştırın.

## 4 · Kontrol

**Kontrol et ve başlat** düğmesine dokunun. Önce Android komut izni yeniden denetlenir; ardından Termux bağlantısı ve Python, Nginx, Node.js, npm, cloudflared araçları gerçekten kontrol edilir. Eksik araç varsa ekrandaki açıklamayı izleyin. Araç kontrolü geçince sunucu başlatılır. Android 13+ bildirim izni ayrıca sorulabilir.

Üç çalışma durumu ayrı gösterilir: **Yerel yönetim API’si**, **Nginx site sunucusu** ve **İnternet tüneli**. API'nin çalışması internetten erişim hazır demek değildir. Tünelin bağlanması daha uzun sürebilir; bağlantı hatasında panelde durumu inceleyip yeniden deneyebilirsiniz. **Paneli aç** ile panele geçin.

Kontrol sürerken başka uygulamaya geçerseniz başlatma isteği iptal edilir. Geri döndüğünüzde **Kontrol et ve başlat** düğmesine yeniden dokunun. İzin ayarlarından dönüş yalnız durumu yeniler; kendi başına sunucu başlatmaz.

Panelde **Yeni proje → ZIP seç → Yayına al** ile projenizi yükleyin. Sonraki kullanımlarda **Ana sunucu** anahtarı başlatır/durdurur. **Kolay kurulum** bu dört sayfayı yeniden açar. **Kurulumu sonra tamamla · Paneli aç**, hazırlık eksik olsa da paneli görüntüler; araç kontrolünü tamamlanmış saymaz.

## 5 · Servisler (SSH ve Veritabanları)

Ana sunucu çalıştıktan sonra paneldeki **Servisler** sekmesini açın:

- **SSH erişimi (`sshd`):** Varsayılan port `8022`'dir (`1024–65535`, `8080/8081/8767` hariç). **Yeni yetkili anahtar ekle** ile bilgisayarınızdaki `ssh-ed25519` / `ssh-rsa` / `ecdsa` public key satırını ekleyin; `SHA256:` parmak izi otomatik doğrulanır ve `~/.ssh/authorized_keys` dosyasına yazılır. Dilerseniz **LAN üzerinden erişilebilir kıl (0.0.0.0)** ve **Parola ile girişe izin ver** seçeneklerini açabilirsiniz. SSH anahtarı açıldığında ekranda beliren `ssh -p 8022 ...` komutuna dokunarak panoya kopyalayabilirsiniz.
- **Veritabanı servisleri:** **PostgreSQL** (`127.0.0.1:5432`), **Redis** (`127.0.0.1:6379`) ve **MariaDB** (`127.0.0.1:3306`) kartlarından önce **Kur** (`pkg install -y`), ardından **Başlat** düğmesine dokunun. İlk başlatmada veri dizini (`initdb` / `mariadb-install-db`) otomatik hazırlanır. Süreç bütçesini korumak için aynı anda en fazla 2 veritabanı servisi çalıştırılabilir.

## 6 · Kayıtlar, Arama ve İmzalı Yedekleme

Paneldeki **Kayıtlar** sekmesinde:

- **Arama ve filtreleme:** Metin kutusundan alan adı veya olay mesajı arayabilir; **Seviye filtresi** (`INFO`, `WARNING`, `ERROR`) ve **Kategori filtresi** (`SYSTEM`, `SITE`, `DEPLOYMENT`, `SSH`, `DATABASE`, `SECURITY`) çipleriyle kayıtları daraltabilirsiniz.
- **Log dışa aktarma:** **JSON dışa aktar** veya **CSV dışa aktar** düğmeleriyle filtrelenmiş yayın ve aktivite kayıtlarını panoya kopyalayabilirsiniz.
- **Yedekleme ve geri yükleme:** Sağ üstteki **Yedekleme** düğmesine dokunarak Room v4 veritabanındaki SSH ayarlarını, yetkili anahtarları, servis durumlarını ve site özetlerini **HMAC-SHA256 imzalı JSON zarfı** olarak panoya kopyalayabilir veya daha önce aldığınız yedeği doğrulayarak geri yükleyebilirsiniz.

Ayrıntılı panel kullanımı [DASHBOARD.md](DASHBOARD.md), elle kurulum alternatifi [TERMUX-RUNTIME.md](TERMUX-RUNTIME.md#cihaz-hazırlığı) içindedir. [Güncel kaynak kodları](../PocketPaaS-SurecDuzeltmesi-source.zip) ve [0.4.0 doğrulama raporu](RUNTIME-FIX-VALIDATION.md) birlikte sunulur. Bu sürüm debug APK'dır; gerçek Android/Termux cihaz çalıştırması ve release doğrulaması henüz yapılmadı. [Önceki 0.2.0 kolay kurulum raporu](EASY-SETUP-VALIDATION.md), bu arayüzün cihaz üzerinde doğrulandığı anlamına gelmez.
