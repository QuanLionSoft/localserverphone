# Debug APK doğrulaması

Bu araç Pocket PaaS debug APK'sını ve aynı kaynaktaki Python yardımcılarını karşılaştırır. APK'yı değiştirmez, imzalamaz veya cihaza yüklemez. Windows SDK Build Tools, JDK ve Python 3 gerekir; Python için ek paket gerekmez.

Proje kökünde PowerShell örneği:

```powershell
.\tools\apk-verification\verify.ps1 `
    -ApkPath '.\app\build\outputs\apk\debug\app-debug.apk' `
    -BuildToolsDirectory 'C:\Android\Sdk\build-tools\35.0.0' `
    -JavaHome 'C:\Program Files\Java\jdk-17' `
    -PythonExecutable 'python'
```

SDK ve JDK yollarını bilgisayarınızdaki kurulumlara göre verin. `JavaHome` verilmezse mevcut `JAVA_HOME`/`PATH` kullanılır. `ProjectRoot` varsayılan olarak bu proje köküdür; `OutputDirectory` varsayılan olarak `work/apk-verification/` dizinidir. `ApkPath` başka bir konumdaki teslim APK'sını da gösterebilir.

Kontroller:

- Resmi `apksigner verify --verbose --print-certs`: imza doğrulaması ve sertifika özeti.
- Resmi `zipalign -c -P 16 -v 4`: APK girdi hizalaması.
- Resmi `aapt2 dump badging`: derlenmiş manifest ve kaynak meta verisinin okunabilmesi.
- Python: ZIP CRC ve yinelenen girdi kontrolü, sıkıştırılmamış `resources.arsc`, ikili manifest, DEX dosya boyutları/SHA-1 değerleri ve beklenen uygulama sınıfları.
- APK içindeki üç Python dosyasının proje kaynaklarıyla aynı baytları taşıması, beklenmeyen keystore/`.env`/`.pyc` girdilerinin bulunmaması.
- Yerel `.so` kitaplıklarındaki ELF `PT_LOAD` segmentlerinin en az 16 KiB hizalanması.

Raporlar `apksigner.log`, `zipalign.log`, `badging.log` ve APK SHA-256 değerini içeren `contents.json` dosyalarına yazılır. Bir kontrol başarısız olursa komut hata verir. `verify_apk.py` içindeki `pathlib.absolute()` kullanımı doğrulama bilgisayarının Windows dosya erişim katmanıyla uyumludur; dosyalar doğrudan açılarak okunur.

Beklenen sınıf adları debug çıktısına göredir. R8 ile küçültülmüş release APK'sında adlar değişebileceğinden bu sınıf kontrolü aynı biçimde kullanılamaz. Bu kontroller Android üzerinde çalıştırmayı, Compose cihaz testlerini, Termux/Nginx/Cloudflare entegrasyonunu veya release anahtarıyla imzalamayı doğrulamaz.
