package com.formatter.api.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Mendeteksi format sumber dari isi file (bukan dari ekstensi), agar file
 * .yaml/.yml/.json bisa di-sniff secara seragam.
 */
@Component
public class SourceFormatDetector {

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public SourceFormat detect(byte[] input) {
        JsonNode root = parse(input);
        if (root == null || !root.isObject()) {
            return SourceFormat.UNKNOWN;
        }

        JsonNode type = root.get("type");
        if (type != null) {
            String t = type.asText("");
            // Insomnia v5 punya beberapa varian "type": spec.* (API Spec) dan
            // collection.* (Request Collection). Keduanya struktur koleksinya sama.
            if (t.startsWith("spec.insomnia.rest/5") || t.startsWith("collection.insomnia.rest/5")) {
                return SourceFormat.INSOMNIA_V5;
            }
        }

        if (root.hasNonNull("openapi") && root.get("openapi").asText("").startsWith("3.")) {
            return SourceFormat.OPENAPI_3_0;
        }

        return SourceFormat.UNKNOWN;
    }

    private JsonNode parse(byte[] input) {
        String text = new String(input, StandardCharsets.UTF_8).trim();
        // Coba JSON dulu bila terlihat seperti JSON, selain itu YAML (YAML juga superset JSON).
        try {
            if (text.startsWith("{")) {
                return jsonMapper.readTree(input);
            }
            return yamlMapper.readTree(input);
        } catch (Exception e) {
            try {
                return jsonMapper.readTree(input);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
