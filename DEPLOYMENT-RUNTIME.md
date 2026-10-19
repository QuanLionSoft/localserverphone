# Faz 5 — ZIP deployment pipeline

`DeploymentEngine`, Android `ContentResolver`/`Uri` adaptörüdür. Hilt singleton `DeploymentOrchestrator`, Android'den bağımsız deployment ve backend yaşam döngüsünü yönetir. Runtime aktarımı, çalışma alanı, proxy ve repository domain arayüzlerinden sağlanır. Application kendiliğinden proje kurmaz; bu API'yi çağıran uzak yönetim servisi Faz 6, Dashboard Faz 7 kapsamındadır.

## Hazırlık ve proje biçimleri

[TERMUX-RUNTIME.md](TERMUX-RUNTIME.md) izinlerini ve [NETWORK-RUNTIME.md](NETWORK-RUNTIME.md) ağ kurulumunu tamamlayın. Python projeleri için pip/venv desteği, Node/React için Node/npm gerekir. Termux paketleri:

```bash
pkg install python python-pip python-ensurepip-wheels nodejs-lts nginx cloudflared
python -m venv "$HOME/pocketpaas-venv-check"
"$HOME/pocketpaas-venv-check/bin/python" -m pip --version
node --version
npm --version
```

Bu kontrol dizini deployment runtime'ından ayrıdır. APK paketleri otomatik kurmaz; eksik executable, ensurepip veya native build bağımlılığı açık hata verir. Projenin Python/Node sürüm ve Android ABI uyumluluğu cihaz üzerinde doğrulanmalıdır. `npm install` veya pip gereksinimleri native extension derliyorsa ilave Termux derleme paketleri gerekebilir.

Python paketinin pip/ensurepip önerileri ve Node LTS paketi resmi kaynaklarda tanımlıdır. [Termux Python paketi](https://github.com/termux/termux-packages/blob/master/packages/python/build.sh), [Termux Node LTS paketi](https://github.com/termux/termux-packages/blob/master/packages/nodejs-lts/build.sh)

ZIP kökünde proje dosyaları bulunmalıdır. Bütün dosyaları kapsayan tek bir sarmalayıcı klasör varsa helper bunu kaldırır.

| Site türü | Gerekli giriş | Kurulum ve çalıştırma |
|---|---|---|
| STATIC | En az bir normal dosya | ZIP içeriği Nginx ile sunulur. Ana sayfa için `index.html` kullanın. |
| NODEJS | `package.json`, `index.js` | `npm install --no-audit --no-fund`, ardından `node index.js`. |
| DJANGO | `requirements.txt`, `manage.py` | Projeye özel `.venv`, pip gereksinimleri; loopback `runserver --noreload`. |
| DJANGO altında Flask | `requirements.txt`, `app.py`; `manage.py` yok | Aynı Python ortamı; Flask CLI `--app app run`, debugger/reloader kapalı. |
| REACT | `package.json` ve `scripts.build` | npm install + npm run build; `dist/index.html` veya `build/index.html` Nginx ile sunulur. |

Node uygulaması `process.env.PORT` değerini kullanmalıdır; `HOST=127.0.0.1` de aktarılır ve uygulama bu bind adresine uymalıdır. React build base path/router basename ve backend proxy/prefix ayarları `/sites/<domain>/` ile uyumlu olmalıdır. Nginx response gövdelerini yeniden yazmaz. Python requirements içinde Django veya Flask projenin istediği sürüme sabitlenmelidir. Flask CLI `app` modülündeki uygulama nesnesini/factory'yi bulabilmelidir.

İstenen Django `runserver` akışı korunur; Django ve Flask geliştirme sunucuları production WSGI sunucusu değildir. Bu sınırlama reverse proxy ile ortadan kalkmaz. Kalıcı üretim yayını için Gunicorn/uygun ASGI-WSGI runner entegrasyonu ve cihaz doğrulaması gerekir. [Django deployment](https://docs.djangoproject.com/en/5.2/howto/deployment/), [Flask deployment](https://flask.palletsprojects.com/en/stable/deploying/)

## API

```kotlin
val result = deploymentEngine.deployProject(
    site = site,
    zipUri = selectedDocumentUri,
    contentResolver = contentResolver,
)
```

Hem domain `Site` hem Room `SiteEntity` overload'u vardır. URI `content://` olmalıdır; dosya Android belge seçicisinden gelir. Adaptör provider stream'ini IO dispatcher'da APK'nın özel cache'ine, 64 MiB üst sınırıyla kopyalar; input stream ve geçici dosya kapanır/temizlenir. URI erişimi çağrı süresince geçerli olmalıdır. Faz 6 multipart endpoint'i için domain `Site` + özel `File` overload'u da vardır. Bu overload çağıranın dosyasını silmez; caller kendi spool dosyasını temizlemelidir.

| API | Sonuç |
|---|---|
| `deployProject(site, uri, resolver)` | Aktarma, güvenli açma, dependency/build, backend, Nginx apply. |
| `deployProject(site, archiveFile)` | Önceden hazırlanmış APK spool dosyasıyla aynı pipeline. |
| `startSite(id)` | Mevcut deployment dosyaları/dependency'leri üzerinden yeniden başlatır; tekrar install yapmaz. |
| `stopSite(id)` | Sahip olunan backend'i durdurur, route/URL/PID'yi kaldırır; proje dosyalarını korur. |
| `stopAll()` | Çalışan/sahip olunan veya rollback borcu olan siteleri durdurur. |
| `retryCleanup(id)` | Başarılı deployment sonrası kalan backup/arşiv temizliğini tekrar dener; warning listesi döner. |

Site önce Room repository'de kayıtlı olmalıdır. Kimlik/domain/type/port değişikliği reddedilir. Deployment yalnız `STOPPED` veya `FAILED`, PID'siz ve bu oturumda sahipsiz siteye uygulanır. Çalışan bir siteyi değiştirmeden önce `stopSite` çağırın. Aynı orchestrator tüm işlemleri mutex ile sıralar; CAS `DEPLOYING` claim'i başka işlemin kaydını ezmeyi önler. API/servis tüm deployment ve runtime değişikliklerini bu controller üzerinden yürütmelidir.

## İşlem sırası ve rollback

1. Site claim edilir, `DEPLOYING` kaydedilir. Mevcut proxy snapshot'ı alınır; eski route varsa kurulum süresince kaldırılır.
2. APK spool ZIP'i authenticated localhost üzerinden Termux `runtime/uploads/<UUID>.zip` alanına akışla aktarılır. JSON/Base64 büyük dosya aktarımı kullanılmaz.
3. ZIP `htdocs/.deploy-<UUID>` içinde açılır ve giriş dosyaları doğrulanır. `runtime/deployments/<UUID>.json` işlem günlüğü sahipliği kaydeder.
4. Önceki `htdocs/<domain>` dizini `htdocs/.backup-<UUID>` olur; doğrulanmış staging aktif dizine atomik rename ile taşınır. Ardından dependency'ler **son konumda** kurulur. Python venv'lerinin absolute interpreter yolları nedeniyle install sonrası venv taşınmaz. [Python venv sözleşmesi](https://docs.python.org/3/library/venv.html)
5. Node/Python backend `ManagedProcess` olarak başlatılır; PID `DEPLOYING` kaydına yazılır. Nginx ve tünel için iki daemon slot'u ayrılmıştır; varsayılan en fazla beş backend çalışır. STATIC/React backend süreci kullanmaz.
6. Sahip olunan backend hâlâ çalışırken loopback portunun kararlı bağlantı kabulü beklenir. Bu TCP readiness uygulamaya özgü bir HTTP health endpoint testi değildir. Önceden dolu port reddedilir; 30 saniye startup timeout varsayılandır.
7. Nginx başlatılır/doğrulanır, route uygulanır ve çalışan Nginx reload edilir. `RUNNING` CAS başarılı olduğunda deployment commit edilmiş olur. Tünel otomatik başlatılmaz; public URL çalışan tünelin durumuna bağlıdır.
8. Eski backup, ZIP ve journal temizlenir. Commit sonrası cleanup hatası çalışan deployment'i geri almaz; `cleanupWarnings` ve log'a yazılır. `retryCleanup` idempotent tekrar dener. Bu borç bitmeden yeni start/deploy engellenir; mevcut çalışan site durdurulabilir.

Install, build, readiness veya proxy apply hatasında önce sahip olunan yeni süreç durdurulur; işlem günlüğüyle eski dizin geri yüklenir ve proxy snapshot'ı yeniden uygulanır. Sonuç `FAILED`, PID doğrulanmış stop sonrası null olur. Loglar korunur. İptal de `NonCancellable` cleanup ile bu yolu izler. `RUNNING` commit'inden sonraki caller iptali başarılı deployment'i durdurmaz veya geri almaz.

Stop sonucu belirsizse dosyalar silinmez/değiştirilmez; process handle ve pending rollback tutulur. Tekrar `stopSite`, doğrulanmış durdurma sonrası rollback'i tamamlar. Rollback hatası da tekrar stop için borç olarak korunur. Eski watcher'lar yeni backend'i değiştiremez. Beklenmeyen backend çıkışı veya canlı log persistence hatası route'u kaldırmaya çalışır ve `FAILED` kaydeder.

## ZIP ve aktarım sınırları

| Sınır | Varsayılan |
|---|---:|
| ZIP upload | 64 MiB |
| Eşzamanlı binary upload | 1; diğer istek HTTP 429 |
| Agent'da kayıtlı upload | 8 dosya, en fazla 512 MiB |
| Aktarım deadline | 120 saniye |
| ZIP merkezi dizini | 4 MiB |
| ZIP entry | 10000 |
| Dosya başına açılmış veri | 64 MiB |
| Toplam açılmış veri | 512 MiB |
| Sıkıştırma oranı | En fazla 200 |
| ZIP path depth | 64 |
| Install/build timeout | Her biri 15 dakika |
| Toplam süreç / uzun süreç | 8 / 7; bir kontrol slot'u ayrılır |

EOCD ve gerçek central header'lar, `ZipFile` nesneleri ayrılmadan önce sınırlı okumalarla doğrulanır. ZIP64, multidisk, şifreleme, özel dosya/symlink, absolute/traversal/backslash/control path, tekrar/casefold çakışması ve file/directory çakışması reddedilir. Açılmış gerçek byte sayısı ve CRC ayrıca kontrol edilir. POSIX dosya işlemleri root'a bağlı no-follow descriptor'lar kullanır; özel dosya izinleri ve atomic/fsync yazımlar uygulanır. Temizlik yalnız journal'ın sahibi olduğu staging/backup/active yollarında yapılır; symlink hedefleri takip edilmez.

Sıkıştırma profili yalnız STORE ve DEFLATE kabul eder; LZMA/BZIP2 gibi yöntemler decompressor belleği ayrılmadan reddedilir. Agent, kaybolan HTTP yanıtının cleanup isteğini yalnız aynı upload sahiplik kimliğiyle eşleşirse uygular; çakışan mevcut arşiv korunur. Her yeni upload ayrı UUID kullanır. Termux Python hardlink desteğini kapattığı için son upload commit'i no-follow directory descriptor'larına bağlı atomic rename kullanır. Kimlik doğrulamalı agent yazıcıları aynı kilidi paylaşır; başka bir Termux UID sahibi process'in kasıtlı namespace değişimi tenant güvenlik sınırı değildir.

Dependency komutları terminal UI açmadan çalışır. stdout/stderr ayrı IO okuyucularından bounded capture'a alınır; install/build çıktı kuyrukları komut sonunda DB log'una yazılır. Uzun ömürlü backend logları `SharedFlow` üzerinden canlı DB'ye aktarılır. Yavaş tüketici nedeniyle eski live olaylar düşebilir; bu sistem kalıcı, kayıpsız audit log deposu değildir.

`npm install` lifecycle script'leri ve Python build/import kodu çalıştırabilir. Projeler owner-trusted Termux UID'sinde yürütülür; tenant izolasyonu sağlanmaz. ZIP path doğrulaması bu komutları sandbox'a dönüştürmez. [npm lifecycle scripts](https://docs.npmjs.com/cli/v11/using-npm/scripts/)

## Yaşam döngüsü sınırları

Yeni oturum, Foreground Service API'yi açmadan önce authenticated agent reset ve journal/DB reconciliation uygular. Kalıcı PID tek başına sahiplik kanıtı sayılmaz; bilinmeyen canlı PID ve belirsiz journal kimliği recovery'yi durdurur. Commit kanıtı olan proje korunur, yarım işlem abort edilir; ayrıntılar [REMOTE-API.md](REMOTE-API.md) içindedir. Cold restart eski süreç handle'larını tahminen devralmaz ve projeleri otomatik başlatmaz.

APK/agent hard kill, tamamen commit olmuş upload veya journal/backup bırakabilir. Upload alanı sekiz dosyayla sınırlıdır; dolduğunda yeni upload reddedilir. Sahip olunan yarım işlemler journal bilgisiyle incelenmeli ve güvenli cleanup yapılmalıdır; bu faz yaşa göre rastgele dosya silmez. Successful deployment cleanup uyarıları uygulama açıkken `retryCleanup` ile toparlanır.

Android document provider davranışı, Termux Python/Node uyumluluğu, pip/npm registry erişimi ve gerçek Django/Flask/React kurulumu cihaz üzerinde doğrulanmadı. Güncel Android derleme/test ve APK durumu [PHASE-7-VALIDATION.md](PHASE-7-VALIDATION.md) içindedir. Dashboard, URI seçimi ve uzak API aynı runtime koordinatörünü kullanır.

Faz 7 Dashboard, `LocalSiteOperations` ve kayıtlı `ProjectArchiveSource` üzerinden çağrı yapar. Android source edinimi/kopyalaması runtime mutasyonu içinde 64 MiB sınırıyla yapılır; Stop kopyayı ve deployment'ı iptal eder. Eski `DeploymentEngine` adaptörü doğrudan URI/File çağrıları için korunur; Dashboard bunu runtime admission dışında çalıştırmaz.
