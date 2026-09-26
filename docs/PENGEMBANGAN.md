# 🛠️ Pengembangan

## Prasyarat

| Alat | Versi |
|---|---|
| JDK | 17 |
| Android SDK | Platform 34, Build-Tools 34 |
| Gradle | 8.9 (otomatis lewat `./gradlew`) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 (dengan plugin Compose compiler) |

Cara termudah: buka folder proyek di **Android Studio**, yang akan mengunduh SDK yang dibutuhkan. Kalau lewat terminal, set `ANDROID_HOME` ke lokasi SDK atau buat `local.properties`:

```properties
sdk.dir=/Users/nama/Library/Android/sdk
```

## Struktur proyek

```
MockLocation/
├── app/
│   ├── build.gradle.kts           # konfigurasi modul, signing, dependensi
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/kou/mocklocation/
│       │   │   ├── MainActivity.kt   # Activity + AppVm
│       │   │   ├── Ui.kt             # UI Compose
│       │   │   ├── MapScene.kt       # overlay peta + sumber ubin
│       │   │   ├── MockService.kt    # foreground service mock
│       │   │   ├── JoystickOverlay.kt
│       │   │   ├── RoutePlayer.kt    # logika geo murni + GPX
│       │   │   └── Store.kt          # persistensi
│       │   └── res/                  # ikon, tema
│       └── test/…/RoutePlayerTest.kt
├── docs/                          # dokumentasi + ilustrasi
├── keystore.properties            # (tidak di-commit)
└── release.jks                    # (tidak di-commit)
```

## Perintah umum

```bash
./gradlew testDebugUnitTest   # unit test (logika geo, loop, GPX, format)
./gradlew assembleDebug       # APK debug → app/build/outputs/apk/debug/
./gradlew installDebug        # pasang ke perangkat/emulator yang terhubung
./gradlew assembleRelease     # APK rilis (minify + signing) → app/build/outputs/apk/release/
```

### Menguji di emulator tanpa menyentuh Setelan

```bash
P=com.kou.mocklocation
adb shell pm grant $P android.permission.ACCESS_FINE_LOCATION
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell appops set $P android:mock_location allow   # = memilih aplikasi lokasi palsu
adb shell dumpsys location | grep "last mock location" # cek lokasi yang sedang dipalsukan
```

## Signing rilis

Build rilis membaca `keystore.properties` di root proyek:

```properties
storeFile=release.jks
storePassword=…
keyAlias=mocklocation
keyPassword=…
```

Membuat keystore baru:

```bash
keytool -genkeypair -v -keystore release.jks -alias mocklocation \
  -keyalg RSA -keysize 4096 -validity 10000
```

> ⚠️ **Simpan `release.jks` dan kata sandinya di tempat aman (backup di luar laptop).** Update aplikasi harus ditandatangani dengan kunci yang sama. Kalau kunci hilang, pengguna harus uninstall versi lama sebelum bisa memasang versi baru. Kedua file sudah masuk `.gitignore`.

Kalau `keystore.properties` tidak ada, `assembleRelease` tetap berjalan tapi menghasilkan APK yang belum ditandatangani.

## Merilis versi baru

1. Naikkan `versionCode` dan `versionName` di `app/build.gradle.kts`.
2. Tambahkan catatan di `CHANGELOG.md`.
3. Build dan verifikasi:
   ```bash
   ./gradlew testReleaseUnitTest assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
4. Commit, beri tag, lalu buat rilis GitHub:
   ```bash
   git tag v2.1.0 && git push --tags
   cp app/build/outputs/apk/release/app-release.apk MockLocation-v2.1.apk
   gh release create v2.1.0 MockLocation-v2.1.apk --title "v2.1.0" --notes-file catatan.md
   ```

## Konvensi kode

- Logika yang bisa dites tanpa Android diletakkan di `RoutePlayer.kt` dan wajib punya test di `RoutePlayerTest.kt`.
- Teks UI berbahasa Indonesia.
- Warna mengikuti token di atas `Ui.kt` (`Teal`, `Live`, `Amber`, `Card`, `Track`, `Muted`).
