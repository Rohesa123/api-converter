package com.formatter.api.converter.insomnia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.formatter.api.converter.OutputType;
import com.formatter.api.converter.SourceFormat;
import com.formatter.api.converter.TargetFormat;
import com.formatter.api.model.ConversionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture diambil dari folder {@code format/} (acuan yang ter-commit) agar test
 * tetap jalan di CI/GitHub Actions.
 */
class InsomniaV5ToOpenApi3ConverterTest {

    private final InsomniaV5ToOpenApi3Converter converter = new InsomniaV5ToOpenApi3Converter();
    private final YAMLMapper yaml = new YAMLMapper();

    private byte[] collectionRef() throws Exception {
        return Files.readAllBytes(Path.of("format", "insomnia-collection-5.0.yaml"));
    }

    private byte[] documentRef() throws Exception {
        return Files.readAllBytes(Path.of("format", "insomnia-document-5.0.yaml"));
    }

    @Test
    void supportsInsomniaToOpenApi() {
        assertTrue(converter.supports(SourceFormat.INSOMNIA_V5, TargetFormat.OPENAPI_3_0));
    }

    @Test
    void convertsSpecDocumentVariant() throws Exception {
        // varian spec.insomnia.rest/5.0 (punya blok spec:) harus tetap terkonversi
        ConversionResult result = converter.convert(documentRef(), "insomnia-document-5.0.yaml", OutputType.YAML);
        JsonNode root = yaml.readTree(result.content());
        assertEquals("3.0.0", root.path("openapi").asText());
        assertTrue(root.path("paths").size() > 0, "paths harus terbentuk dari koleksi");
    }

    @Test
    void convertsToValidOpenApiYaml() throws Exception {
        ConversionResult result = converter.convert(collectionRef(), "insomnia-collection-5.0.yaml", OutputType.YAML);

        String out = new String(result.content(), StandardCharsets.UTF_8);
        assertTrue(out.contains("openapi: 3.0.0"));
        assertTrue(result.filename().endsWith(".openapi.yaml"));

        JsonNode root = yaml.readTree(result.content());
        assertEquals("3.0.0", root.path("openapi").asText());
        assertTrue(root.path("info").path("title").asText().length() > 0);
        assertTrue(root.path("servers").isArray());
        assertTrue(root.path("paths").isObject());
        assertTrue(root.path("paths").size() > 0, "harus ada minimal satu path");
    }

    @Test
    void mapsBodyAsExampleAndTagsFromFolders() throws Exception {
        ConversionResult result = converter.convert(collectionRef(), "insomnia-collection-5.0.yaml", OutputType.YAML);
        JsonNode root = yaml.readTree(result.content());
        JsonNode paths = root.path("paths");

        boolean foundRequestBodyExample = false;
        boolean foundTag = false;
        var it = paths.elements();
        while (it.hasNext()) {
            JsonNode pathItem = it.next();
            var methods = pathItem.elements();
            while (methods.hasNext()) {
                JsonNode op = methods.next();
                if (op.path("requestBody").path("content").size() > 0) {
                    JsonNode content = op.path("requestBody").path("content");
                    var media = content.elements();
                    while (media.hasNext()) {
                        if (media.next().has("example")) {
                            foundRequestBodyExample = true;
                        }
                    }
                }
                if (op.path("tags").isArray() && op.path("tags").size() > 0) {
                    foundTag = true;
                }
            }
        }
        assertTrue(foundRequestBodyExample, "body Insomnia harus muncul sebagai example");
        assertTrue(foundTag, "folder harus menjadi tags");
    }

    @Test
    void carriesEnvironmentsIntoServersAndExtension() throws Exception {
        ConversionResult result = converter.convert(collectionRef(), "insomnia-collection-5.0.yaml", OutputType.YAML);
        JsonNode root = yaml.readTree(result.content());

        // base_url tiap environment harus muncul sebagai server dengan resolved URL
        JsonNode servers = root.path("servers");
        assertTrue(servers.isArray() && servers.size() >= 2, "harus ada server per environment");
        boolean hasLocal = false;
        boolean hasProd = false;
        for (JsonNode s : servers) {
            String url = s.path("url").asText();
            if (url.equals("http://localhost:8080")) hasLocal = true;
            if (url.equals("https://api.bookstore.example.com")) hasProd = true;
        }
        assertTrue(hasLocal, "server Local (base_url) harus terbawa");
        assertTrue(hasProd, "server Production (base_url) harus terbawa");

        // seluruh variabel environment (termasuk token) disimpan di ekstensi
        JsonNode env = root.path("x-insomnia-environments");
        assertTrue(env.isObject(), "x-insomnia-environments harus ada");
        assertTrue(env.path("Local").path("token").asText().length() > 0,
                "token environment harus ikut tersimpan");

        // path harus bersih dari prefix {{ _.base_url }}
        assertTrue(root.path("paths").has("/books"),
                "path harus bersih dari prefix base_url");
    }

    @Test
    void producesValidJsonOutput() throws Exception {
        ConversionResult result = converter.convert(collectionRef(), "insomnia-collection-5.0.yaml", OutputType.JSON);
        assertTrue(result.filename().endsWith(".openapi.json"));
        String out = new String(result.content(), StandardCharsets.UTF_8);
        assertFalse(out.isBlank());
        assertTrue(out.trim().startsWith("{"));
    }
}
