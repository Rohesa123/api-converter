package com.formatter.api.converter.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.formatter.api.converter.FormatConverter;
import com.formatter.api.converter.OutputType;
import com.formatter.api.converter.SourceFormat;
import com.formatter.api.converter.TargetFormat;
import com.formatter.api.model.ConversionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Konversi kebalikan: dokumen OpenAPI 3.0 menjadi koleksi Insomnia v5
 * ({@code type: collection.insomnia.rest/5.0}).
 *
 * <p>Setiap operation pada {@code paths} menjadi satu request Insomnia; tag
 * menjadi folder. Server pertama (atau ekstensi {@code x-insomnia-environments}
 * bila ada) direkonstruksi menjadi blok {@code environments}, dengan URL
 * request memakai variabel {@code {{ _.base_url }}}.</p>
 */
@Component
public class OpenApi3ToInsomniaV5Converter implements FormatConverter {

    private static final String[] HTTP_METHODS =
            {"get", "post", "put", "delete", "patch", "head", "options", "trace"};

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper jsonPretty = new ObjectMapper();
    private final YAMLMapper outYaml = (YAMLMapper) new YAMLMapper()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS);

    // Penghitung untuk sortKey & timestamp yang menurun stabil.
    private long sortCounter;

    @Override
    public SourceFormat source() {
        return SourceFormat.OPENAPI_3_0;
    }

    @Override
    public TargetFormat target() {
        return TargetFormat.INSOMNIA_V5;
    }

    @Override
    public ConversionResult convert(byte[] input, String originalName, OutputType output) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Gagal membaca file OpenAPI: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || !root.hasNonNull("openapi")) {
            throw new IllegalArgumentException("Struktur file OpenAPI tidak valid.");
        }

        Map<String, Object> doc = buildInsomnia(root);

        byte[] bytes;
        try {
            if (output == OutputType.JSON) {
                bytes = jsonPretty.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc);
            } else {
                bytes = outYaml.writeValueAsBytes(doc);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gagal menserialisasi hasil: " + e.getMessage(), e);
        }

        String baseName = stripExtension(originalName == null ? "converted" : originalName);
        String filename = baseName + ".insomnia." + output.extension();
        return new ConversionResult(bytes, filename, output.contentType());
    }

    private Map<String, Object> buildInsomnia(JsonNode root) {
        long now = System.currentTimeMillis();
        sortCounter = now;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("type", "collection.insomnia.rest/5.0");
        doc.put("schema_version", "5.1");
        doc.put("name", text(root.path("info"), "title", "Converted Collection"));
        doc.put("meta", meta("wrk_", now, root.path("info").path("description").asText("")));

        boolean usesBearer = hasBearerScheme(root);

        // Kelompokkan operation per tag (tag -> folder). Tanpa tag -> top-level.
        Map<String, List<Map<String, Object>>> byTag = new LinkedHashMap<>();
        List<Map<String, Object>> topLevel = new ArrayList<>();

        JsonNode paths = root.path("paths");
        if (paths.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String path = entry.getKey();
                JsonNode pathItem = entry.getValue();
                for (String method : HTTP_METHODS) {
                    JsonNode op = pathItem.get(method);
                    if (op == null || !op.isObject()) {
                        continue;
                    }
                    Map<String, Object> request = buildRequest(path, method, op, now);
                    String tag = firstTag(op);
                    if (tag == null) {
                        topLevel.add(request);
                    } else {
                        byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(request);
                    }
                }
            }
        }

        List<Object> collection = new ArrayList<>(topLevel);
        for (Map.Entry<String, List<Map<String, Object>>> e : byTag.entrySet()) {
            Map<String, Object> folder = new LinkedHashMap<>();
            folder.put("name", e.getKey());
            folder.put("meta", meta("fld_", now, ""));
            folder.put("children", e.getValue());
            collection.add(folder);
        }
        doc.put("collection", collection);

        // cookieJar default (seperti export Insomnia).
        Map<String, Object> jar = new LinkedHashMap<>();
        jar.put("name", "Default Jar");
        jar.put("meta", metaSimple("jar_", now));
        doc.put("cookieJar", jar);

        doc.put("environments", buildEnvironments(root, now));

        // simpan keterangan bearer agar tidak hilang (Insomnia tak punya securitySchemes global)
        if (usesBearer) {
            doc.put("x-source-security", Map.of("bearer", true));
        }

        return doc;
    }

    private Map<String, Object> buildRequest(String path, String method, JsonNode op, long now) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("url", "{{ _.base_url }}" + path);

        String name = op.path("summary").asText("");
        if (name.isBlank()) {
            name = op.path("operationId").asText(method.toUpperCase(Locale.ROOT) + " " + path);
        }
        req.put("name", name);
        req.put("meta", meta("req_", now, op.path("description").asText("")));
        req.put("method", method.toUpperCase(Locale.ROOT));

        // body dari requestBody.content.<mime>.example
        JsonNode content = op.path("requestBody").path("content");
        if (content.isObject() && content.size() > 0) {
            Map.Entry<String, JsonNode> media = content.fields().next();
            String mime = media.getKey();
            JsonNode example = media.getValue().get("example");
            String text = exampleToText(example, mime);
            if (text != null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("mimeType", mime);
                body.put("text", text);
                req.put("body", body);
            }
        }

        // parameters -> headers + query
        List<Map<String, Object>> headers = new ArrayList<>();
        List<Map<String, Object>> queryParams = new ArrayList<>();
        JsonNode params = op.path("parameters");
        if (params.isArray()) {
            for (JsonNode p : params) {
                String in = p.path("in").asText("");
                String pname = p.path("name").asText("");
                String value = p.path("example").asText("");
                if (pname.isBlank()) {
                    continue;
                }
                if ("header".equalsIgnoreCase(in)) {
                    headers.add(kv(pname, value));
                } else if ("query".equalsIgnoreCase(in)) {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("name", pname);
                    q.put("value", value);
                    q.put("disabled", false);
                    queryParams.add(q);
                }
            }
        }
        // Content-Type otomatis bila ada body
        if (req.containsKey("body")) {
            String mime = (String) ((Map<?, ?>) req.get("body")).get("mimeType");
            boolean hasCt = headers.stream().anyMatch(h -> "Content-Type".equalsIgnoreCase((String) h.get("name")));
            if (!hasCt) {
                headers.add(0, kv("Content-Type", mime));
            }
        }
        if (!queryParams.isEmpty()) {
            req.put("parameters", queryParams);
        }
        if (!headers.isEmpty()) {
            req.put("headers", headers);
        }

        // authentication: bearer bila operation punya security
        Map<String, Object> auth = new LinkedHashMap<>();
        if (op.path("security").isArray() && op.path("security").size() > 0) {
            auth.put("type", "bearer");
            auth.put("token", "{{ _.token }}");
        } else {
            auth.put("type", "none");
        }
        req.put("authentication", auth);

        req.put("settings", defaultSettings());
        return req;
    }

    /** Bangun blok environments dari x-insomnia-environments (round-trip) atau dari servers. */
    private Map<String, Object> buildEnvironments(JsonNode root, long now) {
        Map<String, Object> environments = new LinkedHashMap<>();
        environments.put("name", "Base Environment");
        environments.put("meta", metaSimple("env_", now));

        JsonNode ext = root.get("x-insomnia-environments");
        List<Map<String, Object>> subs = new ArrayList<>();

        if (ext != null && ext.isObject() && ext.size() > 0) {
            Iterator<Map.Entry<String, JsonNode>> it = ext.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().equalsIgnoreCase("Base Environment")) {
                    environments.put("data", jsonMapper.convertValue(e.getValue(), LinkedHashMap.class));
                } else {
                    subs.add(subEnv(e.getKey(), jsonMapper.convertValue(e.getValue(), LinkedHashMap.class), now));
                }
            }
        }

        if (subs.isEmpty()) {
            // rekonstruksi dari servers
            JsonNode servers = root.path("servers");
            if (servers.isArray()) {
                int i = 1;
                for (JsonNode s : servers) {
                    String url = s.path("url").asText("");
                    if (url.isBlank() || url.equals("/")) {
                        continue;
                    }
                    String envName = s.path("description").asText("");
                    if (envName.isBlank()) {
                        envName = "Environment " + i;
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("base_url", url);
                    subs.add(subEnv(envName, data, now));
                    i++;
                }
            }
        }

        if (!environments.containsKey("data")) {
            Map<String, Object> base = new LinkedHashMap<>();
            base.put("base_url", "_");
            environments.put("data", base);
        }
        if (!subs.isEmpty()) {
            environments.put("subEnvironments", subs);
        }
        return environments;
    }

    private Map<String, Object> subEnv(String name, Map<String, Object> data, long now) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("name", name);
        env.put("meta", metaSimpleSorted("env_", now));
        env.put("data", data);
        return env;
    }

    // ----- util -----

    private boolean hasBearerScheme(JsonNode root) {
        JsonNode schemes = root.path("components").path("securitySchemes");
        if (!schemes.isObject()) {
            return false;
        }
        for (JsonNode s : schemes) {
            if ("http".equalsIgnoreCase(s.path("type").asText())
                    && "bearer".equalsIgnoreCase(s.path("scheme").asText())) {
                return true;
            }
        }
        return false;
    }

    private String firstTag(JsonNode op) {
        JsonNode tags = op.path("tags");
        if (tags.isArray() && tags.size() > 0) {
            String t = tags.get(0).asText("");
            return t.isBlank() ? null : t;
        }
        return null;
    }

    private String exampleToText(JsonNode example, String mime) {
        if (example == null || example.isNull()) {
            return null;
        }
        if (example.isObject() || example.isArray()) {
            try {
                return jsonPretty.writerWithDefaultPrettyPrinter().writeValueAsString(example);
            } catch (JsonProcessingException e) {
                return example.toString();
            }
        }
        return example.asText();
    }

    private Map<String, Object> kv(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private Map<String, Object> defaultSettings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("renderRequestBody", true);
        settings.put("encodeUrl", true);
        settings.put("followRedirects", "global");
        Map<String, Object> cookies = new LinkedHashMap<>();
        cookies.put("send", true);
        cookies.put("store", true);
        settings.put("cookies", cookies);
        settings.put("rebuildPath", true);
        return settings;
    }

    private Map<String, Object> meta(String prefix, long now, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id(prefix));
        m.put("created", now);
        m.put("modified", now);
        if (prefix.equals("req_")) {
            m.put("isPrivate", false);
        }
        m.put("description", description == null ? "" : description);
        m.put("sortKey", -(sortCounter++));
        return m;
    }

    private Map<String, Object> metaSimple(String prefix, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id(prefix));
        m.put("created", now);
        m.put("modified", now);
        return m;
    }

    private Map<String, Object> metaSimpleSorted(String prefix, long now) {
        Map<String, Object> m = metaSimple(prefix, now);
        m.put("isPrivate", false);
        m.put("sortKey", sortCounter++);
        return m;
    }

    private String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }

    private String stripExtension(String name) {
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.isBlank() ? "converted" : base;
    }
}
