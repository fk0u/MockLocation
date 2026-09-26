# Panduan Kontribusi

Terima kasih sudah tertarik berkontribusi ke **Mock Location**! 🎉 Semua bentuk kontribusi disambut: laporan bug, ide fitur, perbaikan dokumentasi, maupun kode.

Dengan berpartisipasi, kamu setuju mengikuti [Kode Etik](CODE_OF_CONDUCT.md) proyek ini.

## Melaporkan bug

1. Cari dulu di [Issues](../../issues) apakah bug yang sama sudah pernah dilaporkan.
2. Kalau belum, buat issue baru dengan template **🐞 Laporan bug**, lalu sertakan:
   - merek, model HP, dan versi Android,
   - versi aplikasi (lihat *Setelan → Aplikasi → Mock Location*),
   - langkah untuk mereproduksi, hasil yang diharapkan, dan hasil sebenarnya,
   - screenshot atau log bila ada (`adb logcat -b crash`).

> 🔒 Untuk **celah keamanan**, jangan buat issue publik. Ikuti [Kebijakan Keamanan](SECURITY.md).

## Mengusulkan fitur

Gunakan template **✨ Usulan fitur**. Jelaskan *masalah* yang ingin diselesaikan, bukan hanya solusinya. Fitur yang bertujuan menyembunyikan deteksi mock atau memudahkan penipuan **tidak akan diterima**.

## Mengirim pull request

1. Fork repo, lalu buat branch dari `main`:
   ```bash
   git checkout -b perbaikan/nama-singkat
   ```
2. Siapkan lingkungan sesuai [docs/PENGEMBANGAN.md](docs/PENGEMBANGAN.md).
3. Buat perubahan dengan fokus: **satu PR, satu tujuan**.
4. Pastikan lolos:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
5. Uji manual di perangkat atau emulator. Untuk perubahan UI, sertakan screenshot sebelum/sesudah di PR.
6. Buka pull request ke `main` dan isi template-nya.

CI di GitHub Actions otomatis menjalankan test dan build untuk setiap PR.

## Gaya kode

- Ikuti gaya kode yang sudah ada (Kotlin idiomatik, Jetpack Compose).
- Logika yang tidak bergantung pada Android (perhitungan geo, parsing) letakkan di `RoutePlayer.kt` dan **tambahkan test** di `RoutePlayerTest.kt`.
- Teks UI dalam **Bahasa Indonesia**.
- Gunakan token warna yang sudah ada di `Ui.kt` (`Teal`, `Live`, `Amber`, …). Jangan menambah warna hardcode baru tanpa alasan.
- Jangan menambah dependensi baru bila bisa diselesaikan dengan API Android atau library yang sudah ada.

## Pesan commit

Tulis singkat dan jelas dalam bentuk kalimat perintah, boleh Bahasa Indonesia atau Inggris:

```
Tambah ekspor rute ke KML
Perbaiki crash saat GPX kosong
```

## Lisensi

Dengan mengirim kontribusi, kamu setuju kontribusimu dilisensikan di bawah [Lisensi MIT](LICENSE) yang sama dengan proyek ini.
