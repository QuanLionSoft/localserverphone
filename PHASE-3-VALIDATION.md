# Faz 3 doğrulama kaydı

Tarih: 2026-10-13. Çalıştırma ortamı: Windows, JDK 17.0.12, Gradle 8.13, Kotlin 2.2.21, Coroutines 1.10.2, Python 3.12. Paketlenmiş agent yalnızca Python standart kütüphanesini kullanır.

**71 test geçti, 0 hata, 5 kontrol atlandı.** Kotlin/JVM çalıştırması `BUILD SUCCESSFUL` ile tamamlandı. Kaynakların uyarıları hata olarak değerlendirilir. Sonuç özeti [verification/phase3-results.json](verification/phase3-results.json) içinde teslim edilir.

| Kontrol | Geçti | Atlandı |
|---|---:|---:|
| Önceki Room/repository/domain testleri | 22 | 0 |
| Gerçek JVM subprocess ve eşzamanlı stream testleri | 14 | 0 |
| Kotlin bridge → gerçek Python HTTP agent entegrasyonu | 6 | 1 |
| Python agent, HTTP, kuyruk ve süreç testleri | 29 | 4 |
| Toplam | 71 | 5 |

## Doğrulanan davranışlar

- Üretim Kotlin kaynakları bağımsız JVM projesine `Sync` ile aynen alınır. Son çalıştırmadaki süreç kaynakları, teslim edilen dosyalarla SHA-256 karşılaştırmasında eşleşti.
- `AndroidTermuxAgentBootstrap` ve `AndroidTermuxCredentialsStore` gerçek Android API 36 `android.jar` dosyasına karşı derlendi. Bu, Android API imzalarının sınırlı compile kontrolüdür; Android sistem davranışı emüle edilmez.
- Android projesinin Gradle DSL/plugin yapılandırması `help` göreviyle başarılı yüklendi. Bu görev resource/APK paketlemesini çalıştırmaz.
- Gerçek Java çocuk süreçte stdout ve stderr kanal başına 2 MiB çıktı üretir. Ayrı gobbler'lar kilitlenmeden son çıktıları yakalar. Ayrı thread'lerde eşzamanlı okuma, iki okuyucunun birlikte başlamasını gerektiren bariyerle ayrıca doğrulanır.
- Satır sonu olmayan büyük çıktı, UTF-8 okuma ve surrogate-safe sınırlı capture kontrol edilir. Env ve boşluk içeren working directory korunur; exit code ve stderr doğru döner.
- Timeout, komut iptali, application scope iptali, başlatma sırasındaki iptal, semaphore beklerken iptal, kapasitenin yeniden kullanılması, idempotent stop ve canlı log replay doğrulanır. Testlerin ortaya çıkardığı cleanup pipe kilitlenmesi ve kapasitesi dolan wait havuzunda stdin kapatma kilitlenmesi düzeltildi.
- Kotlin taşıması gerçek Python agent'a HTTP üzerinden bağlanır; PID/env aktarımı, büyük stdout/stderr, canlı log, stop, hazır agent'ın yeniden kullanılması, yanlış token ve transport kaybı test edilir. Poll coroutine'i başlamadan scope iptal edilirse uzak çocuğun yine sonlandırıldığı doğrulanır.
- Python testleri tüm rotalarda auth, localhost bind, JSON/gövde sınırları, yanlış env/cwd/komutlar, binary alias önceliği, ayrı sınırlı kuyruklar, EOF, backpressure, hata kodu, idempotent stop, process/record limitleri, kayıt TTL'si, shutdown, global idle watchdog ve per-process lease'i kapsar. Beklenmeyen stream arızası başarılı exit olarak gizlenmez.
- Windows satır sonu dönüşümü yalnız test beklentilerinde normalize edilir; üretim capture gerçek çıktıyı korur.

## Çalıştırma

Proje kökünde:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:POCKETPAAS_TEST_PYTHON = 'C:\path\to\python.exe'
$env:POCKETPAAS_TEST_ANDROID_JAR = 'C:\path\to\android-sdk\platforms\android-36\android.jar'
.\gradlew.bat --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' -p .\verification\room-jvm test
python -B .\verification\termux-agent\test_agent.py
```

Android jar ortam değişkeni isteğe bağlıdır; verilmezse Android adapter'ları JVM modülüne alınmaz. Python yolu verilmezse bridge entegrasyon testleri açıkça skip edilir. Bu doğrulamada ikisi de sağlandı. Yerel sandbox Kotlin incremental backup taşımasını engellediği için compiler in-process ve incremental=false kullanıldı; APK projesinin compiler ayarları değiştirilmedi.

## Sınırlar ve APK durumu

Bu Windows host üzerinde toplam beş kontrol atlandı: Kotlin POSIX shell/layout/binary entegrasyonu; Python POSIX shell, process group/TERM-ignoring descendant, symlink kaçışı ve sabit portta POSIX yeniden başlatma. Windows fixture'ları, sandbox'ın `Path.resolve(strict=True)` reddini yalnız kendi mevcut dizinlerinde uyarladı. Paketlenen agent strict canonical containment doğrulamasını korur. POSIX'e özgü davranışlar Android/Termux veya Linux üzerinde hâlâ doğrulanmalıdır.

Gerçek Android cihaz/emülatör kullanılmadı. `RUN_COMMAND` permission/IPC, foreground/background kısıtları, Termux runtime, tam Hilt graph derlemesi, Android lint ve release imzası doğrulanmadı. Faz 6 Foreground Service ve Faz 7 Dashboard henüz uygulanmadı.

**APK üretilmedi.** Önceki fazda resmi AGP/SDK AAPT2 ZIP/APK yazımı native `0xC0000005` ile çöktü; `.flat` derlemesi çalışırken ZIP çıktısı bozuk kaldı. Faz 3'te aynı native adım tekrar denenmedi; JVM/HTTP testleri bu araçtan bağımsız çalıştırıldı. Kaynak arşivi APK olarak sunulmaz. Önceki kanıtlar [VALIDATION.md](VALIDATION.md) ve [PHASE-2-VALIDATION.md](PHASE-2-VALIDATION.md) içinde korunur.

## İncelenen resmi sözleşmeler

- [Termux RUN_COMMAND izin ve intent sözleşmesi](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux intent extra sabitleri](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)
- [RunCommandService integer log level okuması](https://github.com/termux/termux-app/blob/master/app/src/main/java/com/termux/app/RunCommandService.java)
- [Dispatchers.IO ve bağımsız limitedParallelism görünümleri](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-i-o.html)

Kaynaklar sabittir; Faz 4 Nginx/Cloudflare motoru kullanıcı onayını bekler.
