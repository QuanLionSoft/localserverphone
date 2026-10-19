# Nginx ve Cloudflare yönetim motoru

`NetworkManager`, Hilt singleton olarak süreç motoru, runtime dosya deposu ve Room repository'lerine bağlıdır. Uzun ömürlü Nginx ve cloudflared süreçlerini sahip olduğu `ManagedProcess` nesneleriyle izler. Faz 4'te oluşturulan motor güncel uygulamada ZIP deployment, Foreground Service ve Dashboard ile bağlıdır. Bu belge 0.4.0 çalışma sözleşmesini anlatır; tarihsel Faz 4 raporu değiştirilmez.

## Termux hazırlığı

[TERMUX-RUNTIME.md](TERMUX-RUNTIME.md) içindeki `RUN_COMMAND` iznini ve `allow-external-apps=true` ayarını tamamlayın. Termux terminalinde:

```bash
pkg update -y
pkg upgrade -y
pkg install -y python nginx nodejs cloudflared
python --version
nginx -v
node --version
npm --version
cloudflared --version
```

Nginx ve cloudflared resmi [Termux paket ağacında](https://github.com/termux/termux-packages/tree/master/packages) bulunur. APK bu executable'ları kendi sandbox'ına kopyalamaz; çözümleme ve çalışma Termux UID'sinde yapılır. Kurulu sürümleri cihaz üzerinde kontrol edin. Python agent yalnız localhost üzerinde bearer token doğrulamasıyla çalışır.

Güncel kurulum sihirbazı bu tam paket yükseltmesini ve sürüm komutlarını tek kopyalanabilir komut içinde uygular. `nginx -v` bile `CANNOT LINK EXECUTABLE` / `cannot locate symbol SSL_set_quic_tls_cbs` ile biterse sorun Nginx'in Termux kitaplıklarıyla yüklenme aşamasındadır. APK içindeki HTTP portunu veya Cloudflare protokolünü değiştirmek bu native yükleme hatasını onarmaz. Paket yükseltmesinin ve sürüm komutlarının başarılı bitmesi gerekir; bu kontrol Cloudflare edge bağlantısını doğrulamaz. Kurulu Termux'u tekrar kaldırmak bu düzeltmenin bir adımı değildir.

Varsayılan çalışma ağacı agent tarafından `$HOME/.pocket-paas` altında oluşturulur:

```text
htdocs/<canonical-domain>/
nginx/nginx.conf
nginx/conf.d/<canonical-domain>.conf
runtime/nginx.pid
runtime/cloudflared-quick.yml
runtime/{client-body,proxy-temp,fastcgi-temp,uwsgi-temp,scgi-temp}/
logs/nginx-access.log
logs/nginx-error.log
```

Runtime dizini `ProcessExecutor.runtimeLayout()` ile alınır. APK içindeki `filesDir` yolu Termux proje root'u olarak kullanılamaz. Dosya işlemleri Python helper'ına argv ile gönderilir; shell interpolasyonu kullanılmaz. Helper config başına 32 KiB UTF-8 sınırı ve liste başına 128 dosya sınırı uygular. Bu kanal ZIP veya büyük proje dosyası transferi için tasarlanmamıştır.

## Motor API'si

| Çağrı | Davranış |
|---|---|
| `startNginx()` | Config'i test eder, sahip olunan Nginx'i başlatır ve HTTP readiness bekler. |
| `startCloudflareTunnel()` / `startAll()` | Önce Nginx, ardından Quick Tunnel başlatır ve doğrulanmış public URL'yi kaydeder. |
| `stopCloudflareTunnel()` | Tüneli durdurur, main/site public URL'lerini temizler; Nginx kalır. |
| `stopNginx()` / `stopAll()` | Tüneli ve Nginx'i durdurur. |
| `generateVirtualHost(site, rootDir)` | Kayıtlı site için aday config'i test eder, uygular ve çalışan Nginx'i reload eder. |
| `state` | `StateFlow<NetworkState>`; iki servisin durumu, PID'leri, URL ve son hata. |
| `logs` | İki servisin stdout/stderr olaylarını yayınlayan sınırlı `SharedFlow<ProcessOutput>`. |

`generateVirtualHost` hem domain `Site` hem Room `SiteEntity` kabul eder. Site önce repository'de bulunmalıdır. ID, canonical domain ve internal port config değişikliğinin öncesinde/sonrasında DB ile karşılaştırılır. Ana URL `ServerStateRepository.publicUrl`, site URL'leri `SiteRepository.sites` üzerinden gözlenebilir. Room v3 `2→3` migration ana URL'yi nullable ekler; mevcut kurulum kimliği, siteler ve loglar korunur.

Aşağıdaki suspend yardımcı, DB'de mevcut bir sitenin önceden hazırlanmış Termux dizinini sunar; ZIP dağıtımı gerçekleştirmez:

```kotlin
suspend fun exposePreparedSite(
    siteId: Long,
    siteRepository: SiteRepository,
    processes: ProcessExecutor,
    network: NetworkManager,
) {
    val site = requireNotNull(siteRepository.getSite(siteId)) {
        "The site must exist before it can be exposed."
    }
    val layout = processes.runtimeLayout()
    val rootDir = "${layout.projectsPath}/${site.domainName}"
    network.generateVirtualHost(site, rootDir)
    network.startAll()
}
```

STATIC root'u hazırlanmış HTML dizini, REACT root'u hazırlanmış build dizini olmalıdır. Django/Node backend'i site portunda ayrıca çalışıyor olmalıdır; Faz 4 uygulama backend'i başlatmaz ve site `RUNNING` durumunu kendi başına değiştirmez. Config başarılı olsa da backend yoksa proxy isteği 502 döner.

## Routing sözleşmesi

Nginx **127.0.0.1:8081**, dahili process agent **127.0.0.1:8766**, cloudflared metrics **127.0.0.1:8767** kullanır. Faz 6 yönetim API'si için **127.0.0.1:8080** ayrılmıştır. Rootsuz seçime uygun olarak ayrıcalıklı port 80 kullanılmaz.

Tek ana Nginx `server` bloğu, `conf.d/*.conf` location parçalarını içerir. `domainName` bu modda routing kimliğidir; DNS kaydı oluşturulmaz. Örnek site `example.com` için adres:

```text
http://127.0.0.1:8081/sites/example.com/
https://<assigned-name>.trycloudflare.com/sites/example.com/
```

Son slash eksikse 308 yönlendirmesi query parametrelerini korur. STATIC dosyaları `alias` ile sunulur; dizin listesi kapalıdır, gizli dosyalar 403 döner ve POSIX Nginx'te symlink takibi kapatılır. REACT, bulunamayan client route'larını `index.html` üzerinden sunar; giriş dosyası yoksa sonsuz yönlendirme yerine 404 verir. React derleme base/public path ve router basename'i `/sites/example.com/` ile uyumlu olmalıdır.

DJANGO/NODEJS için `proxy_pass http://127.0.0.1:<internalPort>/` routing prefix'ini backend isteğinden çıkarır. Host, forwarded IP/protocol/host/prefix ve WebSocket upgrade header'ları taşınır; cookie root path'i site prefix'ine çevrilir. Backend'in ürettiği absolute URL, asset path ve redirect'ler uygulamanın proxy/prefix ayarlarına bağlıdır; bütün yanıt gövdeleri yeniden yazılmaz. Django'da `ALLOWED_HOSTS`, script prefix ve güvenilen proxy ayarları; Flask/Node'da karşılık gelen prefix/proxy ayarları proje sahibi tarafından yapılandırılmalıdır. Django production sunucusu ve Flask dağıtımı Faz 5 kararıdır.

## Config uygulama ve süreç sahipliği

Domain ve dosya yolları canonical/normalize edilmelidir. Config metni ekleyebilen karakterler, traversal, sibling proje root'u, platform port çakışması ve kayıtlı olmayan site reddedilir. Varsayılan en fazla 64 virtual host'tur.

Yeni host ve main config önce ayrı aday dosyalara yazılır. Gerçek `nginx -p <runtime-root>/ -c <candidate> -t` başarılı olmadan aktif dosyalar değiştirilmez. Uygulama atomik dosya değişimi ve `nginx -s reload` sonrası `/_pocketpaas/health` yanıtındaki yeni revision'ı bekler. Eski Nginx worker'ın 200 cevabı tek başına başarı sayılmaz. Hata durumunda önceki host/main içerikleri geri yüklenir, tekrar test/reload edilir ve eski revision doğrulanır. Rollback başarısızsa servisler durdurulmaya çalışılır ve hata yayınlanır.

Nginx `daemon off;` ile sahip olunan process olarak çalışır. Başlatma/durdurma/config işlemleri `Mutex` ile sıraya alınır. Eski process watcher'ları yeni oturumun state'ini değiştiremez. Kalıcı PID'ler yeni uygulama oturumunda process sahipliği kanıtı sayılmaz; rastgele PID öldürme veya çalışan eski süreci otomatik devralma yapılmaz. Durdurma doğrulanamazsa `FAILED` ve sahiplik referansı korunur; işlem başarılı durdurulmuş gibi gösterilmez. Agent lease'i terk edilen süreçleri temizlemeyi sınırlar.

0.4.0'da Termux stdout/stderr adaptörü Java `PipedInputStream` kullanmaz. Her akışın 64 KiB sınırlı byte tamponu ve bağımsız IO üreticisi vardır; akışın yaşamı coroutine'in son çalıştığı worker thread'ine bağlı değildir. Normal EOF, hata, tüketici kapatması ve tampon doluluğu ayrı ele alınır. APK stdout/stderr gobbler'ları yine ayrı IO coroutine'lerinde çalışır.

`ManagedProcess.await()` bir okuma/supervision hatasıyla tamamlandıysa sonraki başarılı Stop o geçmiş sonucu değiştirmez. `stop()` ayrıca sahip olunan sürecin fiziksel sonlandırmasını doğrular; bu onay alınırsa ağ motoru PID/sahiplik referansını temizleyebilir. Onay alınamazsa sahiplik korunur ve açık Stop tekrarı yeniden dener. Yalnız başarısız `await()` sonucu veya Java adaptörünün kapanması, uzak sürecin öldüğüne kanıt sayılmaz. `RemoteServerRuntimeManager` bütün child cleanup işlemleri başarılı olmadan genel durumu `STOPPED` yapmaz.

Room'daki URL son bilinen metadata'dır; canlı tünel kanıtı değildir. Kullanıcının açık başlatma isteğinde `TermuxRuntimeRecovery`, önce authenticated process reset ve journal/URL/config uzlaştırmasını yapar. Kurtarma belirsizse başlangıç durur ve sahiplik tahmin edilmez. Önceki Termux süreçleri transport kaybından sonra lease süresi boyunca kalabilir; hemen yeniden başlatmada port çakışması bildirilebilir. Yeni `NetworkManager.state` yalnız bu oturumun sahip olduğu süreçleri temsil eder; kurtarma projeleri kendiliğinden başlatmaz.

Sekiz process slot'u kısa komutlar ve uzun ömürlü süreçler arasında ortaktır. En fazla yedi uzun süreç kabul edilir; bir kontrol slot'u config helper/test/reload komutlarına ayrılır. Nginx ve tünel iki uzun süreç kullanır; `DeploymentPolicy.maximumBackendProcesses` varsayılan en fazla beş backend kabul eder.

Nginx access/error logları yukarıdaki dosyalara gider; `NetworkManager.logs` process stdout/stderr akışını verir. UI dosya loglarını otomatik tail etmez. Canlı flow tamponu sınırlıdır; yavaş tüketicide eski olaylar düşebilir.

## Cloudflare readiness ve public URL

Quick Tunnel, kullanıcı global config'ini kullanmamak için boş, uygulamaya ait config ile başlatılır:

```text
cloudflared tunnel --config <runtime>/cloudflared-quick.yml --no-autoupdate --url http://127.0.0.1:8081 --metrics 127.0.0.1:8767
```

Stdout ve stderr ayrı IO okuyucularından canlı izlenir. Parser Quick Tunnel oluşturma banner'ından sonra, parçalanmış logları da birleştirerek `https://[a-zA-Z0-9-]+\.trycloudflare\.com` adresini yakalar. Yanlış suffix, API endpoint'i ve tamamlanmamış URL reddedilir. Adresin logda görünmesi yeterli değildir: metrics `/ready` 200 dönmeli, `/quicktunnel` hostname'i logdaki hostname ile eşleşmeli ve sahip olunan process çalışıyor olmalıdır.

Başlangıç timeout'ları varsayılan Nginx 15 saniye, tünel 60 saniye, config testi 10 saniyedir. Ağ motorunun kendi readiness süresi aşılırsa `ProcessIOException`, `FAILED` ve anlamlı `lastError` üretilir: Nginx mesajı portu/süreyi, Cloudflare mesajı URL atanması ile edge readiness aşamasını ayırır. Bu hata sessiz `STOPPED` sayılmaz. Dış coroutine iptali ayrı kalır; iptal temizliği doğrulanırsa `STOPPED`, doğrulanamazsa `FAILED` ve sahiplik korunur.

Canlı sağlık her iki saniyede kontrol edilir. Çalışan tünel edge bağlantısını kaybederse durum `STARTING` olur ve kalıcı public URL'ler temizlenir; aynı oturum tekrar hazırsa yeniden yayınlanır. Process exit veya kalıcı güncelleme hatası `FAILED` üretir. Nginx kaybı tüneli de durdurur. Sadece config'i bulunan sitelere `/sites/<domain>/` URL'si atanır. Bu local readiness kontrolü internette uçtan uca istek doğrulaması değildir.

İstenen Quick Tunnel modu geçici adres üretir. Cloudflare bunu geliştirme/test için tanımlar, uptime garantisi vermez; 200 eşzamanlı istek sınırı ve SSE kısıtı vardır. Kalıcı production adresi için hesap/DNS ile named tunnel entegrasyonu gerekir. Bu faz Quick Tunnel uygular. [Cloudflare resmi Quick Tunnels belgesi](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)

`RemoteServerService` görünür bildirim altında sunucu, tünel ve projelerin yaşam döngüsünü yönetir; Dashboard Master Switch bu servisi çağırır. Panel üstünde sürüm görünür, hata metinleri seçilip kopyalanabilir. Android/OEM uygulamayı yine de öldürebilir. WorkManager kalıcı web sunucusu yaşam döngüsü sağlamaz. Fiziksel Android/Termux ve dış Cloudflare bağlantısı cihazda doğrulanmadı; güncel sonuçlar [RUNTIME-FIX-VALIDATION.md](RUNTIME-FIX-VALIDATION.md), tarihsel Faz 7 raporu [PHASE-7-VALIDATION.md](PHASE-7-VALIDATION.md) içindedir.

## Resmi sözleşmeler

- [Nginx komut seçenekleri ve config testi](https://nginx.org/en/docs/switches.html)
- [Nginx reload ve graceful shutdown](https://nginx.org/en/docs/control.html)
- [Nginx alias](https://nginx.org/en/docs/http/ngx_http_core_module.html#alias)
- [Nginx proxy_pass](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)
- [cloudflared metrics rotaları](https://github.com/cloudflare/cloudflared/blob/master/metrics/metrics.go)
- [cloudflared readiness kontrolü](https://github.com/cloudflare/cloudflared/blob/master/metrics/readiness.go)
