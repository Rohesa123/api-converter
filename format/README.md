# Katalog Format (acuan)

Folder ini adalah **katalog referensi** dari setiap format yang dikenal aplikasi.
Tujuannya: jadi acuan struktur ke depan — kalau ada **format baru**, taruh satu
berkas contohnya di sini, lalu minta AI/developer **membaca folder ini** untuk
memahami bentuk format sebelum menulis konverternya.

> Semua data di berkas-berkas ini **fiktif/bebas**, hanya untuk menggambarkan
> struktur format.

## Isi katalog

| Berkas | Format | `type` / penanda | Catatan |
| --- | --- | --- | --- |
| [`insomnia-collection-5.0.yaml`](insomnia-collection-5.0.yaml) | Insomnia v5 — Request Collection | `collection.insomnia.rest/5.0` | Tanpa blok `spec:` |
| [`insomnia-document-5.0.yaml`](insomnia-document-5.0.yaml) | Insomnia v5 — Design Document / API Spec | `spec.insomnia.rest/5.0` | Ada blok `spec:` |
| [`openapi-3.0.yaml`](openapi-3.0.yaml) | OpenAPI 3.0.0 | `openapi: 3.0.0` | Pakai `x-insomnia-environments` |

Kedua varian Insomnia v5 memiliki struktur `collection` + `environments` yang
identik; konverter memperlakukannya sama. Yang membedakan hanya nilai `type`
dan keberadaan blok `spec:` pada varian Design Document.

## Konvensi setiap berkas acuan

- Diawali komentar yang menjelaskan: nama format, penanda/`type`, dan ciri khas.
- Menyertakan **environment** (mis. `base_url`, `token`) bila format aslinya punya,
  agar pemetaan environment juga terdokumentasi.
- Data dibuat sederhana namun mewakili semua bagian penting (request/operation,
  body, header, query, autentikasi, environment).

## Cara menambah format baru

1. Tambahkan satu berkas contoh di folder ini (data fiktif), ikuti konvensi di atas.
2. Perbarui tabel **Isi katalog** di README ini.
3. Implementasikan konverternya: buat kelas `FormatConverter` baru
   (`@Component`) dan, bila perlu, tambah entri enum `SourceFormat`/`TargetFormat`
   serta deteksi di `SourceFormatDetector`. Lihat
   [README utama](../README.md#menambah-format-baru).
