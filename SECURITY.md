# Kebijakan Keamanan

## Versi yang didukung

Perbaikan keamanan hanya diberikan untuk versi rilis terbaru.

| Versi | Didukung |
|---|---|
| 2.0.x (terbaru) | ✅ |
| < 2.0 | ❌ |

## Melaporkan celah keamanan

**Jangan melaporkan celah keamanan lewat issue publik.**

Gunakan fitur **[Report a vulnerability](../../security/advisories/new)** (tab *Security → Advisories*) di repo ini. Laporan akan bersifat privat dan hanya terlihat oleh pengelola.

Sertakan bila memungkinkan:

- jenis celah dan dampaknya,
- versi aplikasi serta perangkat dan versi Android,
- langkah reproduksi atau proof-of-concept,
- saran perbaikan (opsional).

Kami berusaha merespons dalam **7 hari** dan memberi kabar perkembangan sampai masalahnya selesai. Setelah perbaikan dirilis, pelapor akan dicantumkan di catatan rilis, kecuali memilih anonim.

## Cakupan

Contoh yang **termasuk** cakupan:

- eksekusi kode atau kebocoran data lewat file GPX yang dimuat pengguna,
- komponen aplikasi (service, activity) yang bisa disalahgunakan aplikasi lain,
- kebocoran data pribadi (favorit, rute tersimpan) ke pihak lain.

Contoh yang **tidak termasuk** cakupan:

- aplikasi lain yang bisa mendeteksi lokasi mock. Ini perilaku standar Android yang disengaja (`Location.isMock()`),
- masalah pada layanan pihak ketiga (ubin peta Esri, Nominatim).
