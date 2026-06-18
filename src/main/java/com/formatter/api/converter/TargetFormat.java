package com.formatter.api.converter;

import java.util.Optional;

/**
 * Format file target hasil konversi.
 * Tambah entri baru di sini saat mendukung format output baru.
 */
public enum TargetFormat {
    OPENAPI_3_0("openapi-3.0", "OpenAPI 3.0"),
    INSOMNIA_V5("insomnia-5.0", "Insomnia Collection v5");

    private final String id;
    private final String label;

    TargetFormat(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Optional<TargetFormat> fromId(String id) {
        for (TargetFormat tf : values()) {
            if (tf.id.equalsIgnoreCase(id)) {
                return Optional.of(tf);
            }
        }
        return Optional.empty();
    }
}
