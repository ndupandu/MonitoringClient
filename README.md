# Monitoring Client (APK)

Pembungkus dashboard "Monitoring Client by Pandu" dalam WebView, dengan notifikasi e-tiket lewat FCM.
Fitur: unggah foto, kamera (foto bukti), lokasi (GPS), unduh CSV, buka tautan Maps/WhatsApp di aplikasi lain,
sesi login bertahan, halaman "tidak bisa terhubung" dengan tombol coba lagi, tap notifikasi membuka e-tiket.

## Secret GitHub (Settings > Secrets and variables > Actions)
| Nama | Isi | Wajib |
|---|---|---|
| `DASHBOARD_URL` | alamat dashboard, mis. `https://dashboard.contoh.id` (tanpa garis miring di akhir) | ya |
| `GOOGLE_SERVICES_JSON` | `base64 -w0 google-services.json` dari Firebase | untuk FCM |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | keystore rilis (di bawah) | disarankan |

Tanpa `DASHBOARD_URL`, APK mengarah ke alamat contoh dan tidak akan terhubung.

## Keystore rilis (sekali, simpan baik-baik)
```
keytool -genkeypair -v -keystore release.jks -alias monitoring -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # hasilnya jadi secret KEYSTORE_BASE64
```
Tanpa keystore ini, tiap build GitHub memakai kunci debug yang berbeda, sehingga APK baru tidak bisa menimpa yang lama
(harus uninstall dulu). Jangan commit `release.jks`, jangan hilangkan.

## Build
Push ke `main` atau Actions > Build APK > Run workflow. APK ada di Artifacts (`MonitoringClient-apk`).
Nomor versi naik otomatis tiap build.

## FCM
Lihat `PANDUAN-FCM.md` di paket server (`fcm-update`). Di APK ini tidak ada lagi layanan polling; semua notifikasi lewat FCM.
Package/applicationId: `id.pandu.monitoringclient` (daftarkan nama ini di Firebase).

## Catatan
- Izin yang diminta: notifikasi, kamera, lokasi (hanya saat dipakai), dan berjalan di latar belakang (dikecualikan dari hemat baterai, ditanya sekali).
- Kamera dan GPS di halaman web hanya jalan lewat HTTPS. Kalau dashboard hanya `http://`, foto bukti dan lokasi tidak bisa dipakai.
- Jika APK lama Anda memakai applicationId atau kunci tanda tangan berbeda, uninstall dulu APK lama sebelum memasang yang baru.
- Build lokal: `gradle assembleRelease -PDASHBOARD_URL=https://...` (butuh JDK 17 dan Android SDK).
