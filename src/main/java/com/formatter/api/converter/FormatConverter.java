package com.formatter.api.converter;

import com.formatter.api.model.ConversionResult;

/**
 * Kontrak konverter format. Setiap implementasi menangani satu pasangan
 * (sourceFormat -> targetFormat). Untuk menambah konversi baru cukup buat
 * kelas baru yang mengimplementasikan interface ini dan menandainya sebagai
 * Spring bean (@Component); registry akan menemukannya otomatis.
 */
public interface FormatConverter {

    /** Format sumber yang ditangani. */
    SourceFormat source();

    /** Format target yang dihasilkan. */
    TargetFormat target();

    default boolean supports(SourceFormat source, TargetFormat target) {
        return source() == source && target() == target;
    }

    /**
     * Konversi isi file.
     *
     * @param input        byte mentah file sumber
     * @param originalName nama file asli (untuk menamai hasil)
     * @param output       bentuk serialisasi keluaran (YAML/JSON)
     */
    ConversionResult convert(byte[] input, String originalName, OutputType output);
}
