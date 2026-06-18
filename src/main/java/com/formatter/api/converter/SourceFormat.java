package com.formatter.api.converter;

import java.util.Optional;

/**
 * Format file sumber yang bisa dideteksi & dikonversi.
 * Tambah entri baru di sini saat mendukung format input baru.
 */
public enum SourceFormat {
    INSOMNIA_V5("insomnia-5.0", "Insomnia Collection v5"),
    OPENAPI_3_0("openapi-3.0", "OpenAPI 3.0"),
    UNKNOWN("unknown", "Tidak dikenali");

    private final String id;
    private final String label;

    SourceFormat(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Optional<SourceFormat> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (SourceFormat sf : values()) {
            if (sf.id.equalsIgnoreCase(id)) {
                return Optional.of(sf);
            }
        }
        return Optional.empty();
    }
}
