package com.formatter.api.model;

/**
 * Hasil konversi siap dikirim sebagai unduhan.
 */
public record ConversionResult(byte[] content, String filename, String contentType) {
}
