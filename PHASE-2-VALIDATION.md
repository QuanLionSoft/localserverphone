# Faz 2 — Room veritabanı doğrulaması

Tarih: 13 Ekim 2026, Europe/Istanbul.

## Sonuç

**22 test, 0 başarısızlık, 0 hata.** Kotlin/JVM kaynak derlemesi ve Room/KSP DAO üretimi başarılı. Android APK, Hilt graph'ının tamamı ve cihaz doğrulaması henüz tamamlanmış değildir.

| Test grubu | Sayı | Doğrulanan davranış |
|---|---:|---|
| Mevcut platform repository | 3 | Eşzamanlı başlangıç, kurulum kimliğinin korunması, hata sonrası tekrar deneme |
| Site repository | 9 | StateFlow, hata sonrası toparlanma, iptal, reserved portlar, PID/CAS, log sınırı ve güvenli silme |
| Site giriş doğrulaması | 4 | Domain/IDN normalizasyonu, zararlı domainler, URL doğrulaması, Unicode/NUL log sınırı |
| Gerçek Room/SQLite | 6 | 64 eşzamanlı port tahsisi, port tükenmesi/yeniden kullanımı, 64 atomik log ekleme, durum/public URL sorguları, duplicate rollback ve migration |

## Yeniden çalıştırma

Proje kökünde JDK 17 ile:

```powershell
.\gradlew.bat -p .\verification\room-jvm test
```

`verification/room-jvm`, production domain/config/qualifier, Room entity/DAO/database ve repository kaynaklarının aynı dosyalarını derler. Room 2.8.3 KSP gerçek DAO implementasyonlarını üretir. JVM testlerinde SQLite 2.6.1 bundled sürücüsü kullanılır; Android APK'nın Room bağlantısı Android'in framework SQLite sürücüsünü kullanmaya devam eder.

## Şema ve migration

`app/schemas/dev.pocketpaas.data.local.PocketPaaSDatabase/1.json`, değişmeyen Faz 1 ZIP arşivindeki özgün v1 source dosyaları kullanılarak Room/KSP ile yeniden üretildi. `2.json`, güncel production database/entity/DAO kaynaklarıyla üretildi. Şema JSON dosyaları elle yazılmadı.

- v1 identity hash: `850511882e313926069fc200b3d66259`
- v2 identity hash: `48dd9f2418e4cc941de74109c1cf0202`

Migration testinin v1 fixture'ının identity hash'i özgün v1 ile aynıdır. Test, v1 veritabanında kurulum kimliği/tarihini kaydeder, production `SiteSchema.statements` SQL'ini Room'un `1→2` migration callback'iyle uygular ve güncel production v2 schema doğrulamasından geçer. Kurulum kimliği ve tarihi korunur; `sites` tablosu boş başlar ve yeni kayıt alabilir.

Android `DatabaseMigrations.MIGRATION_1_2`, aynı SQL listesi üzerinde `SupportSQLiteDatabase.execSQL` çağırır. AppModule bu migration'ı Room builder'a kaydeder. Destructive fallback yoktur.

## Port ve log doğrulaması

Room'un ürettiği DAO, `createWithAllocatedPort` işlemini transaction içinde çalıştırır. MAX sorgusu, kullanılan/rezerve portları ayıklama ve ABORT insert aynı transaction içindedir. 64 eşzamanlı çağrının ID ve portları farklıdır; rezerve portlar kullanılmaz. Port aralığı doluyken hata hiçbir kayıt/port tüketmez; silinen port tekrar seçilebilir.

SQL `substr(logs || :text, -:maxChars)` tek bir UPDATE olduğundan 64 eşzamanlı log eklemesinde veri kaybı oluşmadı. Repository, SQLite karakter sayımıyla uyumlu Unicode code point sınırı uygular; NUL karakterini replacement karakterine dönüştürür.

Bu port garantisi veritabanındaki site rezervasyonlarını kapsar. Başka Android süreçlerinin socket kullanımı ve süreçlerin gerçekten ayağa kalkması süreç/sunucu fazlarında doğrulanacaktır.

## Tamamlanmamış Android kontrolleri

- `SiteMigrationTest`, Android üzerinde gerçek `DatabaseMigrations.MIGRATION_1_2` adapter'ını çalıştırır. v1/v2 şemaları androidTest assets'e bağlandı. Test bu ortamda çalıştırılmadı ve Android test APK'sı henüz derlenmedi.
- Diğer Room instrumentation testleri cihaz/emülatörde çalıştırılmadı.
- Faz 1'deki AAPT2 ZIP yazma çökmesi giderilmiş değildir; bu fazda APK derlemesi yeniden başarıyla doğrulanmadı.
- Hilt graph'ının tamamının Android derlemesi, lint, R8/release imzası ve cihaz kurulum/başlangıç kontrolü bekliyor.

Faz 3–7 kodları bu teslimde yazılmadı. Veritabanındaki durum/PID güncellemesi bir Linux sürecini başlatmaz veya durdurmaz.
