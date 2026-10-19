# Windows AAPT2 ZIP yazma hatası için isteğe bağlı araç

Bu araç yalnızca AAPT2'nin Windows üzerinde ZIP yazarken `0xC0000005` ile kapandığı derleme bilgisayarları içindir. Normal Android Studio/Gradle derlemesinde gerekli değildir; uygulama içine eklenmez.

Resmi AAPT2 bütün kaynakları kendisi derler ve bağlar. Araç, `link` komutuna AAPT2'nin desteklediği `--output-to-dir` seçeneğini ekler ve ortaya çıkan dosyaların aynı baytlarını CRC doğrulamalı, sıkıştırmasız ZIP girdilerine yazar. `resources.arsc` sıkıştırılmaz. Ardından Gradle'ın normal Kotlin, KSP, Hilt, D8, APK paketleme ve imzalama görevleri devam eder. Kaynak tablosu veya manifest elle üretilmez. Ara ZIP'in hizalamasını son APK paketleme görevi sağlar.

Gereksinimler: Windows, .NET Framework C# derleyicisi ve projedeki AGP sürümüne uygun resmi `aapt2.exe`. Proje AGP 8.13.2 ile AAPT2 `8.13.2-14304508` kullanır. Gradle'ın indirdiği Windows AAPT2 dosyası tercih edilir. Kaynak bilgisayardaki yola bağlı hazır bir EXE dağıtılmaz.

Proje kökünde PowerShell ile, gerçek AAPT2 yolunu parametre vererek çalıştırın:

```powershell
.\tools\windows-aapt2-wrapper\build.ps1 -RealAapt2 'C:\Android\Sdk\build-tools\35.0.0\aapt2.exe'
$wrapper = Join-Path $PWD 'work\windows-aapt2-wrapper\aapt2.exe'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "-Pandroid.aapt2FromMavenOverride=$wrapper"
```

Örnekteki SDK yolu, bilgisayarınızdaki resmi AAPT2 yoluyla değiştirilir. AGP'nin daha yeni seçenekleri için Gradle önbelleğindeki `8.13.2-14304508` ikilisi kullanılır. `RealAapt2` hiçbir zaman üretilen sarmalayıcının kendisi olmamalıdır.

Dosyalar `work/windows-aapt2-wrapper/` altında oluşturulur. Araç çıktılarının bu proje ağacı içinde kalmasını denetler; temizlikte yalnızca kendi oluşturduğu geçici dizini kaldırır. Sarmalayıcıyı etkinleştirmek için gereken `-P` parametresi komuttan çıkarıldığında standart AAPT2 kullanılır. Global SDK, Gradle önbelleği ve sistem ayarları değiştirilmez.

AGP'nin `m`, `c`, `l` daemon kısaltmaları desteklenir. Geçici dosyalar kısa bir çalışma yolunda oluşturulur ve `.flat` girdilerinden gereken kaynak alt dizinleri önceden açılır. Bu, Windows `MAX_PATH` sınırını ve AAPT2 dizin çıktısının alt dizin gereksinimini karşılar. Bağlama komutları ve araç sonuçları yerel `trace/` dizinine yazılır. Bu seçenek, bu projenin debug APK derlemesi için doğrulanır; AAB veya küçültülmüş release çıktısı için ayrıca doğrulama gerekir.

Son APK için resmi `apksigner verify --verbose`, `zipalign -c -P 16 -v 4` ve `aapt2 dump badging` kontrolleri ayrıca çalıştırılmalıdır. Bu kontroller cihaz üzerinde çalıştırma veya Termux entegrasyon testi yerine geçmez.
