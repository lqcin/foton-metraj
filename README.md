# FOTON Crawler Metraj Android

Android tablette crawler kamera videolarından günlük metraj çıkarmak için hazırlanmış ilk saha sürümüdür.

## Hesap mantığı

Her video için yalnızca iki sayaç değeri kullanılır:

`Metraj = |Son geçerli metre sayacı - İlk geçerli metre sayacı|`

Video ortasındaki sayaç değerleri metraj hesabına dahil edilmez.

Uygulama ayrıca videonun sol üst OSD alanından şu bilgileri okumaya çalışır:
- Parsel
- Konum / hat adı
- Çap
- İnceleme yönü

Firma adı uygulama ekranından girilir.

## APK'yı GitHub Actions ile üretme

1. GitHub'da boş bir repository oluştur.
2. Bu ZIP'in **içindeki tüm dosyaları** repository kök dizinine yükle. `.github` klasörü de yüklenmeli.
3. Varsayılan branch adı `main` olsun.
4. GitHub'da `Actions` sekmesine gir.
5. `Android APK Oluştur` workflow'unu aç.
6. `Run workflow` düğmesine bas. `main` seçili kalsın.
7. İşlem bitince aynı sayfanın altındaki `Artifacts` bölümünden `FOTON-Crawler-Metraj-v0.1` dosyasını indir.
8. İnen ZIP'in içindeki `FOTON-Crawler-Metraj-v0.1-debug.apk` dosyasını Android tablete kur.

İlk push da workflow'u otomatik başlatır.

## Android kurulumu

APK'ya dokun. Android izin isterse ilgili dosya yöneticisi / tarayıcı için `Bilinmeyen uygulamaları yükle` iznini aç ve kuruluma devam et.

## Kullanım

1. Firma adını yaz.
2. Tarihi kontrol et.
3. `VİDEO KLASÖRÜ SEÇ` ile crawler video klasörünü seç.
4. `ANALİZ ET` düğmesine bas.
5. Sonuçları ve toplam metrajı kontrol et.
6. `EXCEL OLUŞTUR` ile `.xlsx` dosyasını Downloads klasörüne kaydet.
7. `PAYLAŞ` ile son oluşturulan Excel'i paylaş.

## OCR notu

İlk sürüm, gönderilen crawler görüntülerindeki sabit kırmızı OSD düzenine göre ayarlanmıştır:
- Sol üst: parsel / konum / yön / çap
- Sağ alt: metre sayacı

Sayaç için videonun tamamı taranmaz. İlk 6 saniye içinde ilk okunabilir sayaç ve son 6 saniye içinde son okunabilir sayaç aranır.

Desteklenen video uzantıları: MP4, MOV, MKV, AVI, M4V, 3GP.

## Sürüm

v0.1.0 - İlk Android saha prototipi.
