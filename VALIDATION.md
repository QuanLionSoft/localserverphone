# Faz 1 doğrulama kaydı

Tarih: 12 Ekim 2026, Europe/Istanbul.

## Geçen kontroller

- Gradle 8.13 Wrapper JAR, resmi dağıtımın SHA-256 değeriyle doğrulandı: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Gradle 8.13 dağıtım SHA-256: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- `gradlew help`: `BUILD SUCCESSFUL`. Project/App Gradle Kotlin DSL dosyaları ve plugin yapılandırması değerlendirildi.
- Android API 36 için AAR metadata kontrolü geçti; uygulama manifest'i ve kaynak birleştirme adımları geçti.
- Domain, `ServerConfig`, ayar entity/DAO ve `DefaultPlatformRepository` kaynaklarının **aynı dosyaları**, Android kaynak paketlemesine ihtiyaç duymayan geçici bir Kotlin/JVM Gradle kontrolünde Kotlin 2.2.21, Room common 2.8.3 ve Coroutines 1.10.2 ile derlendi. Repository test dosyası değiştirilmeden kullanıldı. `test`: `BUILD SUCCESSFUL`; **3 test, 0 hata, 0 başarısızlık**.
- Testler: 32 eşzamanlı başlangıçta tek kurulum kimliği; repository yeniden yaratıldığında kimliğin korunması; çalışma dizini hatasından sonra mutex'in bırakılması ve başarılı yeniden deneme.
- Kaynaklarda eksik işlem yorumları veya `NotImplementedError` bulunmadı.

## APK derlemesindeki engel

Şu Android görevleri denendi:

```text
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Derleme `processDebugResources` / `processDebugAndroidTestResources` adımlarında durdu:

```text
AAPT2 aapt2-8.13.2-14304508-windows Daemon: Unexpected error during link
Caused by: java.io.IOException: AAPT2 process unexpectedly exit. Error output:
```

AGP'nin AAPT2 aracı ve resmi SDK Build Tools 35.0.0 AAPT2 aracıyla bağımsız en küçük manifest/kaynak denemelerinde native ZIP/APK yazma işlemi aynı Windows access violation koduyla sona erdi: `0xC0000005` (`-1073741819`). Android framework JAR okuma ve ZIP kullanmadan `.flat` dosyası oluşturma çalıştı. Kısa çalışma yolu, daha az Gradle worker ve farklı geçici dizinle deneme aynı hatayı verdi. Çöken aracın ürettiği kısmi ZIP dosyaları geçerli APK sayılmadı; teslim arşivine dahil edilmedi.

Bu bulgular, hatanın AAPT2 native ZIP yazma yolu ve çalışma ortamıyla ilişkili olduğuna işaret eder. Kesin kök neden belirlenmedi. Kaynak kodlarının tamamen hatasız olduğunu kanıtlamaz.

Android SDK yalnızca çalışma klasörüne indirildi. Kullanıcının mevcut SDK, Java kurulumları ve sistem ayarları değiştirilmedi. APK'nın derlenmesi için README'deki komutları çalışan bir Android Studio/SDK ortamında tekrar çalıştırmak gerekir.

## Tamamlanmayan doğrulama

- Uygulamanın tamamının Kotlin/Hilt/KSP derlemesi ve Room schema üretimi.
- Android lint'in tamamlanması.
- Debug APK'nın oluşturulması, imza ve kurulum kontrolü.
- İki gerçek Room instrumentation testi ve Android cihaz/emülatör üzerinde başlangıç testi.
- Release imzası, R8 küçültme ve release cihaz doğrulaması.

JVM testleri gerçek SQLite/Room transaction uygulamasını, Android DI graph'ını veya Netty'nin cihazda başlatılmasını doğrulamaz. Room eşzamanlılık ve yeniden açılış testleri kaynaklarda vardır; cihazda çalıştırılmamıştır.

Faz 2–7 kodları bu teslimde yazılmadı. Henüz Nginx, Cloudflare, runtime binary'leri veya dağıtım endpoint'leri içeren bir APK yoktur.
