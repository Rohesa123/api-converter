package com.formatter.api.converter;

import java.util.Optional;

/**
 * Bentuk serialisasi file hasil yang diunduh user.
 */
public enum OutputType {
    YAML("yaml", "yaml", "application/x-yaml"),
    JSON("json", "json", "application/json");

    private final String id;
    private final String extension;
    private final String contentType;

    OutputType(String id, String extension, String contentType) {
        this.id = id;
        this.extension = extension;
        this.contentType = contentType;
    }

    public String id() {
        return id;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static Optional<OutputType> fromId(String id) {
        for (OutputType ot : values()) {
            if (ot.id.equalsIgnoreCase(id)) {
                return Optional.of(ot);
            }
        }
        return Optional.empty();
    }
}
