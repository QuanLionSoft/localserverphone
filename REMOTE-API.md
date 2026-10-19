# Pocket PaaS — uzak API ve servis sözleşmesi

`RemoteServerService`, kullanıcı tarafından başlatılan Android foreground service'dir. `RemoteServerController.start()` API'yi varsayılan olarak **127.0.0.1:8080** üzerinde başlatır. `start(enableLanAccess = true)` açık seçimle **0.0.0.0:8080** üzerinde dinler. Dashboard bu controller'ı kullanır; sunucu kullanıcı Ana sunucu anahtarını açtığında başlar. Yerel API anahtarı yalnız açık kopyalama eylemiyle hassas panoya aktarılır.

Servis başlangıcı cold recovery'yi ve anahtar kontrolünü tamamladıktan sonra Netty'yi açar. `RemoteServerRuntime.start(host)` yalnız API'yi açar. Faz 7 controller varsayılan `startNetwork=true` ile ardından aynı Foreground Service içinde Nginx/tünel başlatmasını ister. `start(startNetwork=false)` API-only kullanımını korur. Ağ başlangıcı başarısız olursa API RUNNING kalır; bağlantı Dashboard üzerinden yeniden denenebilir. Servis durdurulması API'yi, devam eden deployment'ı, çalışan projeleri, Nginx'i ve tüneli kapsar.

## Yetkilendirme ve erişim

Her `/api` isteği **Authorization: Bearer <64 küçük harf hex karakter>** gerektirir. Anahtar 256 bit `SecureRandom` ile oluşturulur, `noBackupFilesDir/remote-api.key` içinde `AtomicFile` ile saklanır. Termux bridge token'ından ayrıdır. Eksik/yanlış/tekrarlanan Authorization başlığı 401; okunamayan/bozuk anahtar deposu 503 üretir. Anahtar hiçbir yanıt gövdesine, URL'ye veya bildirime yazılmaz. Yerel UI `RemoteServerController.apiKey()` ile alabilir; bu faz anahtar döndüren ağ endpoint'i sağlamaz.

Doğrulama gövde okunmadan, özel upload dosyası oluşturulmadan ve deployment başlatılmadan yapılır. Token karşılaştırması `MessageDigest.isEqual` kullanır. Yanıtlar `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` ve `Connection: close` taşır. Hatalar sınırlı kodlarla döner; ham exception, dosya yolu ve credential mesajları istemciye taşınmaz. İstek loglama eklentisi kurulmaz.

API HTTP kullanır. LAN seçeneği token'ı ağ üzerinde şifrelemez; yalnız güvenilen özel ağ/VPN üzerinde kullanın. Güvenli varsayılan, loopback ve USB `adb forward tcp:8080 tcp:8080` erişimidir. Cloudflare Quick Tunnel mevcut **Nginx:8081** origin'ini yayınlar; yönetim API'sini yayınlamaz. Bu fazda API TLS sonlandırması veya internete açık yönetim tüneli yoktur.

## Endpointler

| İstek | Başarılı yanıt | İşlem |
|---|---|---|
| `GET /api/sites` | 200, JSON liste | Room repository'deki siteler; logların son en fazla 4096 Unicode kod noktası |
| `POST /api/deploy` | 201, site JSON | Aşağıdaki multipart ZIP'i doğrular ve pipeline bitene kadar bekler |
| `POST /api/sites/{id}/start` | 200, site JSON | Mevcut deployment'ı başlatır |
| `POST /api/sites/{id}/stop` | 200, site JSON | Sahip olunan backend'i durdurur ve proxy'yi kaldırır |

Site JSON alanları: `id`, `domainName`, `internalPort`, `projectType`, `processId`, `publicUrl`, `status`, `logs`, `createdAt`. Nullable alanlar JSON'da null olarak bulunur. ID pozitif Long, zaman epoch milisaniyesidir. Stop/start gövdeleri boş olmalıdır. Domain dizin adı ve routing kimliğidir; DNS kaydı oluşturulmaz.

Deployment tam iki multipart parça kullanır:

- `metadata`: UTF-8 JSON; yeni site için `{"domainName":"demo.example","projectType":"NODEJS"}`, mevcut siteye yeniden deployment için yalnız `{"siteId":1}`.
- `archive`: filename içeren ZIP dosyası; istemcinin filename'i dosya yolu olarak kullanılmaz. İçerik türü `application/zip` veya `application/octet-stream` olabilir.

Parça sırası serbesttir. Tekrarlanan/bilinmeyen alanlar, JSON null, hatalı UTF-8, kesirli/string ID, belirsiz site kimliği, fazladan multipart parçaları ve bilinmeyen parça başlıkları reddedilir. Metadata içerik türü verilirse `application/json`, charset verilirse UTF-8 olmalıdır. Proje türleri `STATIC`, `DJANGO`, `NODEJS`, `REACT`; Django tipi `app.py` Flask fallback'i de destekler. Arşiv biçimi [DEPLOYMENT-RUNTIME.md](DEPLOYMENT-RUNTIME.md) içindedir.

POSIX shell örneği; `POCKETPAAS_API_TOKEN` değerini yerel uygulamadan alın ve shell tracing'i kapalı tutun:

```bash
curl --fail-with-body --max-time 2400 \
  -H "Authorization: Bearer $POCKETPAAS_API_TOKEN" \
  -F 'metadata={"domainName":"demo.example","projectType":"NODEJS"};type=application/json' \
  -F 'archive=@project.zip;type=application/zip' \
  http://127.0.0.1:8080/api/deploy

curl --fail-with-body \
  -H "Authorization: Bearer $POCKETPAAS_API_TOKEN" \
  http://127.0.0.1:8080/api/sites

curl --fail-with-body -X POST \
  -H "Authorization: Bearer $POCKETPAAS_API_TOKEN" \
  http://127.0.0.1:8080/api/sites/1/stop
```

Bu senkron API uzun dependency kurulumlarında uzun süre açık kalır. Deployment deadline 35 dakika; istemci timeout'u buna göre seçilmelidir. İstek iptali pipeline'a yayılır; commit öncesi rollback yapılır. Yanıt kaybı commit sonucunu belirsiz bırakabilir: otomatik tekrar yüklemek yerine önce `GET /api/sites` ile durumu kontrol edin. Bu API idempotency key veya job queue uygulamaz.

## Kaynak ve eşzamanlılık sınırları

- ZIP en fazla 64 MiB; multipart toplamı 66 MiB; metadata 8192 byte; parça başlıkları toplam 4096 byte; HTTP başlıkları toplam 8192 byte.
- Upload deadline 120 saniye. ZIP akışı 16 KiB tamponla uygulamanın özel cache dizinine yazılır; tüm dosya RAM'e alınmaz. ZIP pipeline tamamlandıktan/iptal temizliği bittikten sonra spool silinir. Başlangıç temizliği yalnız kendi `remote-upload-<number>.zip` dosyalarını siler, symlink takip etmez ve en fazla 128 giriş tarar.
- API üzerinde aynı anda yalnız bir deploy/start/stop kabul edilir; diğer mutasyonlar 409 döner. Liste okuma devam eder. Runtime ayrıca yerel çağrıları da serialize eder; Stop hem çalışan hem sırada bekleyen mutasyonları iptal eder.
- Netty için toplam en fazla 32 bağlantı, bağlantı başına tek istek ve ilk HTTP başlıkları için sabit 30 saniyelik deadline. Yavaş başlık gönderme deadline'ı yenilemez. Gövde ve dependency kurulumları ayrı süre sınırlarına sahiptir.
- En fazla 64 kayıtlı site oluşturulur; önceki süreç/deployment sınırları korunur. ZIP'teki kod, npm lifecycle script'leri ve pip bağımlılıkları kullanıcının Termux UID'siyle çalışır. Bu uygulama birbirine güvenmeyen tenant'lar için izolasyon sağlamaz.

Hata örneği: `{"error":"operation_conflict"}`. Durum kodları: 400 malformed istek/kimlik, 401 credential, 404 site, 408 upload timeout, 409 mutasyon/aktif site çakışması, 413 boyut, 415 içerik türü, 500 işlem/cleanup hatası, 503 runtime/credential erişilemez, 504 deployment deadline.

## Cold recovery ve servis durdurma

Yeni uygulama oturumu ilk listener'dan önce authenticated Termux agent'i `/runtime/reset` ile çağırır. Agent yalnız kendi izlediği process group'ları durdurur; veritabanından gelen PID'lere öldürme sinyali göndermez. Bilinmeyen veya yeniden kullanılmış canlı PID, durdurma belirsizliği, uyumsuz eski agent, bilinmeyen site/journal/config durumları recovery'yi durdurur ve dosyaları/PID'leri korur. Uyumsuz eski agent için Termux oturumunu açıkça yeniden başlatmak gerekir.

İşlem günlükleri kimlik/tür kontrolünden sonra uzlaştırılır: `ACTIVE` journal + DB `RUNNING` kaydı commit kanıtıdır ve yeni proje korunup backup temizlenir; `COMPLETING` commit tamamlanır; diğer yarım işlemler abort edilir. Kanıt olmadan tahmini dosya silme yapılmaz. Recovery başarılı olursa URL'ler temizlenir, önceki RUNNING kayıtları STOPPED olur, yarım DEPLOYING kayıtları FAILED olur, PID'ler null yapılır. Eski projeler otomatik başlatılmaz. Recovery hatasında eski site statüsü korunabilir; genel runtime FAILED gösterir ve API kapalı kalır. Bu, sonraki denemede commit kanıtını korur.

Service hemen foreground bildirimi açar, ardından IO başlatır; `START_NOT_STICKY` kullanır. Bildirimde Durdur eylemi vardır. Cleanup belirsizse owner bırakılmaz, FAILED bildirimi tekrar Stop imkânı verir. Ekranı son uygulamalardan kaldırmak sunucuyu durdurmaz; servis bildirimin Durdur eylemine kadar arka planda çalışır. `onDestroy` temizliği best effort'dür; Android callback vermeden process'i öldürebilir. Bu durumda Termux agent'in 300 saniyelik lease'i son cleanup mekanizmasıdır. Foreground service kesintisiz çalışma veya CPU uyanıklığı garantisi değildir.

Manifest Android 14+ `specialUse` türünü, subtype açıklamasını ve foreground izinlerini içerir. Servis non-exported; boot/background autostart eklenmez. Bildirim izni ve görünür UI başlatma akışı MainActivity/Dashboard'a bağlıdır. Google Play'e gönderimde bu service use case'i ayrıca değerlendirilebilir. [Android foreground service türleri](https://developer.android.com/develop/background-work/services/fgs/service-types), [servis başlatma](https://developer.android.com/develop/background-work/services/fgs/launch), [bildirim izni](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [Ktor Netty yapılandırması](https://ktor.io/docs/server-engines.html).

Gerçek Android foreground davranışı, bildirim izni, RUN_COMMAND ve cihaz/Termux yaşam döngüsü henüz çalıştırılmadı. Güncel Android derleme, test ve APK sonuçları [PHASE-7-VALIDATION.md](PHASE-7-VALIDATION.md) içindedir; Faz 6'nın tarihsel kaydı ayrıca korunur.
