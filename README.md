# 🚀 Leuwipanjang - Remote Terminal Client

<p align="center">
  <img src="app/src/main/res/drawable/ic_terminal.xml" width="96" height="96" alt="Leuwipanjang" />
</p>

<p align="center">
  <strong>Aplikasi Remote SSH Android yang Ringan, Cepat, dan Tetap Profesional seperti Termius.</strong><br>
  Dibangun 100% Native dengan <strong>Kotlin</strong>, <strong>Material 3</strong>, <strong>Modern JSch 0.2+</strong>, dan <strong>Bouncy Castle Cryptography</strong>.
</p>

<p align="center">
  <a href="https://github.com/leuwipanjang/leuwipanjang/actions"><img src="https://img.shields.io/github/actions/workflow/status/leuwipanjang/leuwipanjang/release.yml?branch=main&label=Build%20%26%20Release" alt="CI Status"></a>
  <img src="https://img.shields.io/badge/Language-Kotlin%201.9-purple.svg" alt="Kotlin">
  <img src="https://img.shields.io/badge/Platform-Android%20API%2024%2B-green.svg" alt="Android">
  <img src="https://img.shields.io/badge/Architecture-100%25%20Native%20(No%20Flutter)-blue.svg" alt="Native">
  <img src="https://img.shields.io/badge/License-GPLv3-yellow.svg" alt="License">
</p>

---

## ✨ Fitur Utama (Key Features)

### 1. 🔑 Autentikasi SSH Public/Private Key (Key Vault)
- **Built-in Key Generator**: Buat pasangan kunci SSH (**Ed25519** - direkomendasikan & paling aman/ringan, **RSA 2048-bit**, atau **RSA 4096-bit**) langsung di dalam aplikasi!
- **Import Private Key**: Import atau paste private key yang sudah Anda miliki (format OpenSSH atau PEM/PKCS#8).
- **1-Tap Copy Public Key**: Salin public key dalam format OpenSSH standar (`ssh-ed25519 AAA... user@leuwipanjang`) untuk ditempelkan ke server remote (`~/.ssh/authorized_keys`).
- **One-Liner Generator**: Salin perintah otomatis `echo '...' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys` dengan satu sentuhan.
- **SHA256 Fingerprint**: Menampilkan sidik jari kunci standar OpenSSH untuk verifikasi integritas.
- **Dukungan Passphrase**: Mendukung private key yang dienkripsi dengan passphrase.

### 2. 🖥️ Interactive Terminal Emulator (Termius-Style)
- **High-Performance Terminal**: Ringan, responsif, hemat memori RAM (< 35 MB), dan ukuran APK sangat kecil (< 10 MB).
- **Accessory Keyboard Bar**: Toolbar tombol khusus di atas keyboard:
  - `ESC`, `TAB`, `CTRL`, `ALT`, `▲` (Up), `▼` (Down), `◀` (Left), `▶` (Right)
  - Simbol cepat: `|`, `/`, `~`, `-`, `:`
  - Pintasan kontrol: `^C` (SIGINT interrupt), `^D` (EOF / logout).
- **ANSI Color & Styling**: Dukungan kode warna ANSI 16 dan 256 warna.
- **Pilihan Tema Warna Terminal**:
  - *Termius Dark (Default)*
  - *Dracula Cyber*
  - *Matrix Hacker Green*
  - *Solarized Dark*

### 3. 🌐 Host Connection Manager
- Simpan konfigurasi server tak terbatas: Alias, Hostname/IP, Port (default 22), Username.
- Pilihan metode autentikasi: **SSH Public/Private Key** atau **Password**.
- Pengelompokan server berdasarkan tag/group (misal: *Production*, *Staging*, *Personal*, *Router*).
- Riwayat waktu koneksi terakhir (*Last Connected*).
- Tombol **Quick Connect (Play)** untuk koneksi instan dari daftar host.

### 4. ⚡ Snippets (Perintah Cepat)
- Simpan perintah shell favorit yang sering digunakan (contoh: `htop`, `docker ps`, `free -h`, `df -h`, `git pull`, `systemctl status nginx`).
- Jalankan snippet langsung ke dalam sesi terminal aktif hanya dengan memilih dari menu runner.

---

## 🛠️ Arsitektur & Teknologi

| Komponen | Spesifikasi / Library |
|---|---|
| **Bahasa & Platform** | 100% Native Kotlin (Android SDK API 24+ / Android 7.0 s/d Android 14+) |
| **SSH Core Engine** | `com.github.mwiede:jsch:0.2.20` (Modern JSch dengan Ed25519, SHA-2, OpenSSH key support) |
| **Kriptografi** | Bouncy Castle `bcprov-jdk18on:1.78.1` & `bcpkix-jdk18on:1.78.1` |
| **UI Design System** | Google Material 3 Dark Design (Termius Midnight Palette) |
| **Penyimpanan Lokal** | SharedPreferences dengan Gson JSON serialisasi |
| **Build System** | Gradle 8.7 & Android Gradle Plugin 8.4.2 |

---

## 🚀 CI/CD Otomatis: Auto-Tag, Build APK & AAB, dan Release

Repository ini dilengkapi dengan workflow GitHub Actions (`.github/workflows/release.yml`).

Setiap kali Anda melakukan `git push` ke branch `main`:
1. 🏷️ **Auto Semantic Tag**: Workflow otomatis mendeteksi riwayat git dan menaikkan versi tag (misal: `v1.0.0` -> `v1.0.1`).
2. ⚙️ **Setup JDK & Android**: Menggunakan Zulu JDK 17 dan Android Build Tools.
3. 📦 **Build APK & AAB**:
   - Menghasilkan Release APK (`leuwipanjang-vX.X.X.apk`)
   - Menghasilkan Release AAB (`leuwipanjang-vX.X.X.aab`)
4. 🔐 **Auto Signing**: Otomatis menandatangani APK/AAB (menggunakan secret keystore jika ada, atau auto-generate release keystore jika belum diatur sehingga build **selalu berhasil**).
5. 🚀 **Publish GitHub Release**: Otomatis membuat release baru di GitHub dan melampirkan file APK & AAB untuk diunduh langsung.

### Mengatur Signing Key Produksi (Opsional):
Jika Anda ingin menggunakan keystore pribadi Anda di GitHub Actions, tambahkan Secrets di GitHub Repo (**Settings > Secrets and variables > Actions**):
- `ANDROID_KEYSTORE_BASE64` (Base64 dari file `.keystore` / `.jks`)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

*(Jika secrets belum diatur, CI akan secara otomatis membuat release keystore secara dinamis sehingga workflow tetap berjalan 100% sukses).*

---

## 💻 Cara Menjalankan Secara Lokal

```bash
# Clone repository
git clone git@github.com:leuwipanjang/leuwipanjang.git
cd leuwipanjang

# Build Debug APK
./gradlew assembleDebug

# Build Release APK & AAB
./gradlew assembleRelease bundleRelease
```

File output APK akan berada di:
`app/build/outputs/apk/release/app-release.apk`

File output AAB akan berada di:
`app/build/outputs/bundle/release/app-release.aab`

---

## 📖 Cara Menggunakan Fitur Public Key SSH

1. Buka tab **Key Vault** di aplikasi Leuwipanjang.
2. Tap tombol **+ (New Key)** lalu pilih **Generate Key Pair** (pilih **Ed25519**).
3. Tap **Copy Public Key**.
4. Di server Linux Anda, tempelkan public key ke file `~/.ssh/authorized_keys`:
   ```bash
   mkdir -p ~/.ssh
   echo "ssh-ed25519 AAAA... leuwipanjang@android" >> ~/.ssh/authorized_keys
   chmod 700 ~/.ssh
   chmod 600 ~/.ssh/authorized_keys
   ```
5. Buka tab **Hosts**, tambah host baru, pilih metode autentikasi **SSH Public/Private Key**, lalu pilih kunci yang sudah Anda buat.
6. Tekan **Connect**! Anda akan langsung login ke server tanpa perlu mengetikkan password lagi.

---

<p align="center">
  Dibuat dengan ❤️ untuk komunitas Linux &amp; DevOps.
</p>
