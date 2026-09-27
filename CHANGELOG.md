# Changelog

## v2.1.0 (2026-09-27)

### Fitur
- **Singgah di titik**: rute bisa berhenti 5 dtk–1 mnt di setiap waypoint. Estimasi waktu ikut menghitungnya.
- **Riwayat lokasi**: 20 lokasi Teleport/Joystick terakhir muncul di pencarian.
- **Tile Quick Settings**: teleport ke titik terakhir atau hentikan mock langsung dari panel notifikasi.
- **Setelan & Tentang**: ketinggian yang bisa diatur, buka ulang panduan setup, hapus riwayat dan cache peta, info versi dan lisensi.

### Lainnya
- Ketinggian kini ikut bervariasi saat *gerak natural* aktif.
- Toolchain diperbarui: Gradle 9.7, Android Gradle Plugin 9.4, Kotlin 2.4, Compose BOM 2026.09.

## v2.0.0 (2026-09-27)

Rilis publik pertama.

### Fitur
- Tiga mode: **Teleport**, **Rute** (sekali / ulangi / bolak-balik), dan **Joystick** (di dalam aplikasi dan **melayang** di atas aplikasi lain).
- Waypoint bisa diseret, rute bisa disimpan/dimuat, serta impor & ekspor **GPX**.
- Pencarian tempat (OpenStreetMap Nominatim) dan tempel koordinat.
- Lokasi favorit.
- Preset kecepatan dan slider 1–150 km/j yang bisa diubah saat berjalan.
- Opsi **gerak natural**: variasi kecepatan, posisi, dan akurasi.
- Tiga gaya peta tanpa API key: Gelap, Jalan, Satelit.
- Marker live dengan interpolasi halus, efek denyut, arah hadap, dan jejak perjalanan.
- Notifikasi dengan tombol Jeda/Lanjut dan Stop.
- Checklist setup terpandu (izin, Opsi developer, aplikasi lokasi palsu, overlay).

### Perbaikan dari prototipe
- Izin lokasi kini diminta saat runtime. Sebelumnya menyebabkan crash di Android 14.
- Provider `network` ikut dipalsukan agar lokasi asli tidak bocor lewat Fused Location.
- Status UI tersinkron dengan service.
- `SecurityException` saat izin mock dicabut di tengah jalan kini ditangani.
