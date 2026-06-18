package com.formatter.api.converter.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.formatter.api.converter.OutputType;
import com.formatter.api.converter.SourceFormat;
import com.formatter.api.converter.TargetFormat;
import com.formatter.api.converter.insomnia.InsomniaV5ToOpenApi3Converter;
import com.formatter.api.model.ConversionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApi3ToInsomniaV5ConverterTest {

    private final OpenApi3ToInsomniaV5Converter converter = new OpenApi3ToInsomniaV5Converter();
    private final InsomniaV5ToOpenApi3Converter forward = new InsomniaV5ToOpenApi3Converter();
    private final YAMLMapper yaml = new YAMLMapper();

    private byte[] openapiRef() throws Exception {
        return Files.readAllBytes(Path.of("format", "openapi-3.0.yaml"));
    }

    private byte[] insomniaRef() throws Exception {
        return Files.readAllBytes(Path.of("format", "insomnia-5.0.yaml"));
    }

    @Test
    void supportsOpenApiToInsomnia() {
        assertTrue(converter.supports(SourceFormat.OPENAPI_3_0, TargetFormat.INSOMNIA_V5));
    }

    @Test
    void convertsOpenApiIntoInsomniaCollection() throws Exception {
        ConversionResult result = converter.convert(openapiRef(), "openapi-3.0.yaml", OutputType.YAML);
        assertTrue(result.filename().endsWith(".insomnia.yaml"));

        JsonNode root = yaml.readTree(result.content());
        assertEquals("collection.insomnia.rest/5.0", root.path("type").asText());
        assertEquals("Weather API", root.path("name").asText());
        assertTrue(root.path("collection").isArray() && root.path("collection").size() > 0);

        // environment direkonstruksi dari x-insomnia-environments
        JsonNode subs = root.path("environments").path("subEnvironments");
        assertTrue(subs.isArray() && subs.size() >= 2, "sub-environment harus terbentuk");
        boolean tokenCarried = false;
        for (JsonNode s : subs) {
            if (s.path("data").path("token").asText().length() > 0) tokenCarried = true;
        }
        assertTrue(tokenCarried, "token environment harus ikut");

        // request memakai variabel base_url
        String out = new String(result.content());
        assertTrue(out.contains("{{ _.base_url }}/forecast"), "URL request harus pakai {{ _.base_url }}");
    }

    @Test
    void roundTripInsomniaToOpenApiAndBack() throws Exception {
        // Insomnia -> OpenAPI
        ConversionResult openapi = forward.convert(insomniaRef(), "insomnia-5.0.yaml", OutputType.YAML);
        // OpenAPI -> Insomnia
        ConversionResult back = converter.convert(openapi.content(), "roundtrip.yaml", OutputType.YAML);

        JsonNode root = yaml.readTree(back.content());
        assertEquals("collection.insomnia.rest/5.0", root.path("type").asText());
        // base_url environment harus bertahan melewati dua arah konversi
        JsonNode subs = root.path("environments").path("subEnvironments");
        assertTrue(subs.isArray() && subs.size() > 0);
        boolean hasBaseUrl = false;
        for (JsonNode s : subs) {
            if (s.path("data").path("base_url").asText().length() > 0) hasBaseUrl = true;
        }
        assertTrue(hasBaseUrl, "base_url harus bertahan saat round-trip");
    }

    @Test
    void producesValidJsonOutput() throws Exception {
        ConversionResult result = converter.convert(openapiRef(), "openapi-3.0.yaml", OutputType.JSON);
        assertTrue(result.filename().endsWith(".insomnia.json"));
        String out = new String(result.content());
        assertTrue(out.trim().startsWith("{"));
    }
}
