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

class InsomniaV5ToOpenApi3ConverterTest {

    private final InsomniaV5ToOpenApi3Converter converter = new InsomniaV5ToOpenApi3Converter();
    private final YAMLMapper yaml = new YAMLMapper();

    private byte[] mitra() throws Exception {
        return Files.readAllBytes(Path.of("example", "Mitra.yaml"));
    }

    private byte[] vaGuard() throws Exception {
        return Files.readAllBytes(Path.of("example", "Va Guard.yaml"));
    }

    @Test
    void supportsInsomniaToOpenApi() {
        assertTrue(converter.supports(SourceFormat.INSOMNIA_V5, TargetFormat.OPENAPI_3_0));
    }

    @Test
    void convertsToValidOpenApiYaml() throws Exception {
        ConversionResult result = converter.convert(mitra(), "Mitra.yaml", OutputType.YAML);

        String out = new String(result.content(), StandardCharsets.UTF_8);
        assertTrue(out.startsWith("openapi: 3.0.0") || out.contains("openapi: 3.0.0"));
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
        ConversionResult result = converter.convert(mitra(), "Mitra.yaml", OutputType.YAML);
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
        ConversionResult result = converter.convert(vaGuard(), "Va Guard.yaml", OutputType.YAML);
        JsonNode root = yaml.readTree(result.content());

        // base_url tiap environment harus muncul sebagai server dengan resolved URL
        JsonNode servers = root.path("servers");
        assertTrue(servers.isArray() && servers.size() >= 2, "harus ada server per environment");
        boolean hasLocal = false;
        boolean hasLive = false;
        for (JsonNode s : servers) {
            String url = s.path("url").asText();
            if (url.equals("http://localhost:8001")) hasLocal = true;
            if (url.equals("http://117.54.11.82:8101")) hasLive = true;
        }
        assertTrue(hasLocal, "server local (base_url) harus terbawa");
        assertTrue(hasLive, "server live (base_url) harus terbawa");

        // seluruh variabel environment (termasuk token) disimpan di ekstensi
        JsonNode env = root.path("x-insomnia-environments");
        assertTrue(env.isObject(), "x-insomnia-environments harus ada");
        assertTrue(env.path("local").path("uwowToken").asText().length() > 0,
                "token environment harus ikut tersimpan");

        // path tidak boleh lagi mengandung template {{ _.base_url }}
        assertFalse(result.toString().isEmpty());
        assertTrue(root.path("paths").has("/va-guard-mobile/check-email"),
                "path harus bersih dari prefix base_url");
    }

    @Test
    void producesValidJsonOutput() throws Exception {
        ConversionResult result = converter.convert(mitra(), "Mitra.yaml", OutputType.JSON);
        assertTrue(result.filename().endsWith(".openapi.json"));
        String out = new String(result.content(), StandardCharsets.UTF_8);
        assertFalse(out.isBlank());
        assertTrue(out.trim().startsWith("{"));
    }
}
