# Format Converter

Aplikasi web + backend Java (Spring Boot) untuk mengonversi file spesifikasi/koleksi API antar-format. Anda mengimpor file lewat halaman web, proses konversi berjalan di server, lalu hasilnya bisa diunduh.

Konversi yang tersedia saat ini (**dua arah**):

| Dari | Ke |
| --- | --- |
| **Insomnia Collection v5** (`spec.insomnia.rest/5.0` atau `collection.insomnia.rest/5.0`) | **OpenAPI 3.0.0** |
| **OpenAPI 3.0.0** | **Insomnia Collection v5** |

Output dapat berupa `.yaml` atau `.json`.

Arsitektur dibuat **modular/extensible** — menambah pasangan format baru cukup membuat satu kelas converter, tanpa mengubah controller atau halaman web.

---

## ✨ Fitur

- **Impor lewat web** — drag & drop atau pilih file, **bisa banyak file sekaligus**, dengan tombol **Hapus semua**.
- **Pilih arah konversi** — dua dropdown: *Konversi dari* → *ke* (mengikuti pasangan yang didukung).
- **Proses di backend** — seluruh logika konversi berada di Java; frontend hanya mengirim file dan menerima hasil.
- **Output YAML atau JSON**, dapat dipilih di halaman.
- **Unduhan otomatis** — satu file diunduh apa adanya; banyak file dibungkus menjadi satu arsip **ZIP**.
- **Konversi berkualitas tinggi (high fidelity), dua arah:**
  - Folder Insomnia ⇄ `tags` OpenAPI.
  - Body request ⇄ `example` pada `requestBody`.
  - Header & query parameter ⇄ `parameters`.
  - Autentikasi bearer ⇄ `securitySchemes` + `security`.
  - **Environment ikut terbawa:** tiap sub-environment Insomnia (`Local`, `Live`, dst.) menjadi entri `servers` dengan `base_url` ter-resolve; seluruh variabel (termasuk token) disimpan di ekstensi `x-insomnia-environments`. Arah sebaliknya merekonstruksi blok `environments` Insomnia kembali (round-trip).
  - Path duplikat ditangani otomatis (mis. `/mitra/register`, `/mitra/register-2`).

---

## 📁 Folder `format/` (acuan format)

Berisi contoh **referensi struktur** tiap format yang didukung, memakai **data fiktif** (bukan dari `example/`):

| Berkas | Format |
| --- | --- |
| [`format/insomnia-5.0.yaml`](format/insomnia-5.0.yaml) | Insomnia Collection v5 (lengkap dengan `environments`) |
| [`format/openapi-3.0.yaml`](format/openapi-3.0.yaml) | OpenAPI 3.0.0 (lengkap dengan `x-insomnia-environments`) |

> Folder `example/` berisi file uji nyata; folder `format/` adalah template acuan berdata bebas.

---

## 🧱 Teknologi

- Java 21
- Spring Boot 3.5.x (`spring-boot-starter-web`)
- Jackson + `jackson-dataformat-yaml`
- Frontend statik (HTML/CSS/JS, tanpa build tool)

---

## 🚀 Menjalankan

> **Catatan penting:** project ini butuh **JDK 21**. Pastikan `JAVA_HOME` menunjuk ke JDK 21.

### Via Maven Wrapper

```bash
# (opsional) arahkan ke JDK 21 bila default sistem berbeda
export JAVA_HOME="/c/Program Files/Java/jdk-21"

./mvnw spring-boot:run
```

Buka **http://localhost:8080**.

### Via IntelliJ IDEA

1. `File → Open` → pilih folder project (`pom.xml`).
2. `File → Project Structure → Project` → **SDK = 21**, **Language level = 21**.
3. Buka `ApiApplication.java` → klik ▶ **Run 'ApiApplication'**.
4. Buka http://localhost:8080.

---

## 🖥️ Cara pakai (web)

1. Tarik/lepas atau klik untuk memilih satu atau beberapa file `.yaml` / `.yml` / `.json`.
2. Pilih **Konversi dari → ke** dan format output (YAML/JSON).
3. Klik **Konversi & Unduh**.
   - 1 file → langsung terunduh.
   - Beberapa file → terunduh sebagai `converted-bundle.zip`.

---

## 🔌 REST API

Backend dapat dipanggil langsung tanpa halaman web.

### `GET /api/formats`
Daftar pasangan konversi yang didukung (dipakai dropdown frontend).

### `POST /api/convert`
Konversi satu file. `multipart/form-data`.

| Param | Wajib | Default | Keterangan |
| --- | --- | --- | --- |
| `file` | ✅ | — | File sumber |
| `source` | ❌ | (auto-detect) | Format sumber, mis. `insomnia-5.0` / `openapi-3.0`. Kosong = dideteksi dari isi file |
| `target` | ❌ | `openapi-3.0` | Format target |
| `output` | ❌ | `yaml` | `yaml` atau `json` |

Mengembalikan file hasil dengan header `Content-Disposition: attachment`.

```bash
# Insomnia v5 -> OpenAPI 3.0
curl -OJ -F "file=@example/Mitra.yaml" -F "target=openapi-3.0" -F "output=yaml" \
  http://localhost:8080/api/convert

# OpenAPI 3.0 -> Insomnia v5
curl -OJ -F "file=@format/openapi-3.0.yaml" -F "source=openapi-3.0" -F "target=insomnia-5.0" \
  http://localhost:8080/api/convert
```

### `POST /api/convert/batch`
Konversi banyak file → satu ZIP. Sama seperti di atas, tetapi parameter file bernama `files` (boleh berkali-kali). File yang gagal dicatat di `_conversion-errors.txt` di dalam ZIP.

```bash
curl -OJ -F "files=@example/Mitra.yaml" -F "files=@example/Va Guard.yaml" \
  http://localhost:8080/api/convert/batch
```

---

## 🗂️ Struktur kode

```
src/main/java/com/formatter/api
├── ApiApplication.java
├── web/ConversionController.java          # endpoint /api/convert, /api/convert/batch, /api/formats
├── model/ConversionResult.java
└── converter/
    ├── FormatConverter.java               # kontrak konverter (source -> target)
    ├── ConverterRegistry.java             # auto-discovery semua converter
    ├── SourceFormatDetector.java          # deteksi format dari isi file
    ├── SourceFormat.java / TargetFormat.java / OutputType.java
    ├── insomnia/InsomniaV5ToOpenApi3Converter.java   # Insomnia v5 -> OpenAPI 3.0
    └── openapi/OpenApi3ToInsomniaV5Converter.java    # OpenAPI 3.0 -> Insomnia v5

src/main/resources/static                  # frontend (index.html, app.js, styles.css)
format/                                     # contoh referensi tiap format (data fiktif)
example/                                    # contoh file uji nyata
```

### Menambah format baru

1. Buat kelas baru yang mengimplementasikan `FormatConverter` dan beri anotasi `@Component`.
2. (Bila perlu) tambah entri pada enum `SourceFormat` / `TargetFormat`.
3. Lengkapi deteksi di `SourceFormatDetector` bila formatnya baru.

`ConverterRegistry` akan menemukannya otomatis; controller dan frontend tidak perlu diubah.

---

## 🧪 Test

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21"
./mvnw test
```

Unit test memakai berkas di `format/` dan `example/` sebagai fixture: struktur OpenAPI, `requestBody` example, tags, pemetaan environment → servers, konversi balik OpenAPI → Insomnia, dan **round-trip** (Insomnia → OpenAPI → Insomnia).

---

## 📄 Lisensi

Dirilis di bawah [Apache License 2.0](LICENSE).
