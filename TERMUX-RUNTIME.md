# Faz 3 — rootsuz Android ve Termux süreç motoru

Android APK'nın varsayılan `ProcessLauncher` uygulaması `TermuxProcessLauncher` olarak Hilt'e bağlandı. APK, `RUN_COMMAND` intent'iyle paketlenmiş Python agent'ını Termux UID'sinde başlatır. Agent yalnızca `127.0.0.1:8766` üzerinde dinler. Her istek, APK'nın `noBackupFilesDir` içinde atomik kaydettiği rastgele 256 bit bearer token ile doğrulanır. Bu dahili süreç bağlantısı, Ktor yönetim API'sinden ayrıdır.

## Cihaz hazırlığı

Önerilen telefon akışı [KOLAY-KURULUM.md](KOLAY-KURULUM.md) içindedir. Aşağıdaki adımlar elle kurulum alternatifidir.

1. Termux'u kurup bir kez açın. Bu adapter `com.termux` paketini ve resmi `RUN_COMMAND` sözleşmesini kullanır; farklı paket adları bu fazda desteklenmez.
2. Termux içinde `pkg update -y`, ardından `pkg install -y python nginx nodejs cloudflared` çalıştırın.
3. `~/.termux/termux.properties` dosyasında `allow-external-apps=true` ayarını etkinleştirin; ardından `termux-reload-settings` çalıştırın.
4. Android Ayarlar → Uygulamalar → Pocket PaaS → İzinler / Ek izinler altında **Run commands in Termux environment** iznini verin. Debug ve release APK'lar ayrı uygulama kimliklerine sahiptir.

Bu şartlar [Termux'un resmi RUN_COMMAND belgesinde](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) tanımlanır. Yanlış token ile kullanılan bir port, uyumsuz protokol veya eksik izin açık hata üretir. Agent ilk shell isteğinde hazırlanır; mevcut açılış ekranı süreç başlatmaz. Sunucu düğmeleri Faz 7 kapsamındadır.

## Çalışma dizini ve binary çözümleme

`ShellExecutor.runtimeLayout()` Termux içindeki gerçek dizinleri döndürür. Varsayılan kök `$HOME/.pocket-paas`; projeler `htdocs`, Nginx ayarları `nginx`, process çalışma alanı `runtime`, log dizini `logs` altındadır. `AndroidSystemManager` tarafından hazırlanan APK içi çalışma alanı ayrı bir yerel hazırlık alanıdır. Termux'a verilen `workingDir` agent'ın bildirdiği çalışma alanı içinde, mevcut bir mutlak dizin olmalıdır. APK, Termux dizinini `File.exists()` ile denetlemez; gerçek erişimi agent denetler.

`resolveBinary("nginx")`, `resolveBinary("python")`, `resolveBinary("pip")` ve `resolveBinary("node")` önce Termux `PREFIX/bin`, sonra global `PATH` üzerinde arar. Python/python3, pip/pip3 ve node/nodejs alias'ları desteklenir. Eksik executable için tahmini bir yol döndürülmez. Bu faz Nginx, Node veya cloudflared paketlerini otomatik kurmaz.

## Süreç sözleşmesi

```kotlin
val layout = shellExecutor.runtimeLayout()
val result = shellExecutor.executeCommand(
    command = "python --version",
    envVars = emptyMap(),
    workingDir = File(layout.rootPath),
)
check(result.isSuccessful) { result.stderr }
```

Dosya adları, kullanıcı girdileri ve executable parametreleri için argv API'si shell yorumlamasını önler:

```kotlin
val python = shellExecutor.resolveBinary("python")
val process = shellExecutor.startProcess(
    arguments = listOf(python, "-u", "server.py"),
    envVars = mapOf("PORT" to "10000"),
    workingDir = File(layout.projectsPath, "example"),
)
val pid = process.processId
val completion = process.await()
```

`startProcess` timeout koymaz; başlatan coroutine tamamlandıktan sonra uygulama scope'u süreci izler. `stop()` aynı sonucu tekrar döndürebilir. `execute` varsayılan 15 dakika timeout kullanır; `execute(request, timeoutMillis)` bunu değiştirebilir. İptal durumunda cleanup tamamlanır ve coroutine iptali tekrar fırlatılır. `EXITED`, `TIMED_OUT`, `STOPPED` sonuçları birbirinden ayrılır; yalnızca `EXITED` ve exit code `0` başarıdır. Transport veya stream arızası `ProcessIOException` üretir.

Stdout ve stderr, `bufferedReader(UTF_8)` ile **ayrı `Dispatchers.IO` coroutine'lerinde** okunur. Bekleme işi üçüncü bir IO havuzunda çalışır. Her okuyucu sabit boyutlu parçalar okur; satır sonu gelmeyen büyük çıktı belleği sınırsız büyütmez. Varsayılan sonuç yakalaması kanal başına son 65536 UTF-16 karakteri tutar, surrogate çiftlerini bölmez ve truncation flag verir. Canlı `SharedFlow` replay 128 / ek tampon 256 olay tutar; yavaş UI tüketicisi için eski olaylar düşebilir. Sıralı sequence numaraları eksik olayları görünür kılar.

Agent kanal başına 64 KiB kuyruk, APK adapter'ı bağımsız sınırlı pipe ve kuyruk kullanır. Normal taşıma sırasında backpressure uygulanır. Açık durdurmada agent önceden kuyruklanan çıktıyı korur; henüz kuyruklanamayan son çıktılar temizliğin kilitlenmemesi için bırakılabilir. En fazla sekiz eşzamanlı süreç çalışır. POSIX'te çocuklar ayrı process group içinde başlatılır ve durdurma TERM → KILL → reap uygular. Süreç lease'i ve agent'ın boşta kalma süresi terk edilen süreçlerin temizlenmesini sınırlar; bu mekanizma Android foreground service'in yerini almaz.

`LocalProcessLauncher` gerçek Java `ProcessBuilder` backend'idir; erişilebilir aynı UID executable'ları ve masaüstü testleri için kullanılır. Stdout/stderr birleştirilmez. Eski Android'de public PID / ProcessHandle API'si yoksa yerel PID nullable kalır ve yerel alt süreç ağacı garantisi verilmez. Rootsuz Termux production backend'i gerçek PID ve POSIX process group yönetimini agent üzerinden sağlar.

Projeler Termux UID'sinin yetkileriyle çalışır; bu runtime sahibi tarafından güvenilen projeler içindir ve birbirinden izole tenant sandbox'ları sağlamaz. ZIP aktarma ve güvenli açma Faz 5'te bu backend içinde uygulanacaktır.

## Tekrar çalıştırılabilir doğrulama

Proje kökünde JDK 17 ile:

```powershell
$env:POCKETPAAS_TEST_PYTHON = 'C:\path\to\python.exe'
.\gradlew.bat -p .\verification\room-jvm test
python -B .\verification\termux-agent\test_agent.py
```

Linux/macOS'ta Python path'ini aynı ortam değişkenine verip `sh ./gradlew` kullanın. Python yolu verilmezse Kotlin bridge integration testleri açıkça skip edilir; yerel process ve Room testleri çalışır. Android adapter'larını ayrıca gerçek SDK API jar'ına karşı derlemek için `POCKETPAAS_TEST_ANDROID_JAR` ortam değişkenini `platforms/android-36/android.jar` dosyasına ayarlayabilirsiniz. Bu sınırlı derleme APK paketleme, Hilt graph, lint veya cihaz testlerini doğrulamaz.

Windows sandbox ortamında `Path.resolve(strict=True)` erişimi engellendiğinde test fixture'ı yalnızca kendi mevcut, symlink/junction içermeyen dizinlerine bir adapter uygular. Paketlenen agent strict canonical path denetimini korur. POSIX shell, process group ve symlink kaçışı kontrolleri Linux/Android üzerinde ayrıca çalıştırılmalıdır.
