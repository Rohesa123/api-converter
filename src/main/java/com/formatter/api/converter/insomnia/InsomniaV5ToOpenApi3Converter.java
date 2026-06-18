package com.formatter.api.converter.insomnia;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Konversi koleksi Insomnia v5 (type: spec.insomnia.rest/5.0) menjadi
 * dokumen OpenAPI 3.0.0.
 *
 * <p>Pendekatan: koleksi diparse menjadi pohon {@link JsonNode}, lalu setiap
 * request (node yang punya {@code url} + {@code method}) dipetakan menjadi
 * sebuah operation pada {@code paths}. Folder (node yang punya {@code children})
 * menjadi tag.</p>
 *
 * <p>Blok {@code environments} Insomnia dipetakan menjadi:
 * <ul>
 *   <li>{@code servers} OpenAPI — variabel base URL tiap sub-environment
 *       di-resolve menjadi server tersendiri (description = nama environment);</li>
 *   <li>ekstensi {@code x-insomnia-environments} — menyimpan SELURUH variabel
 *       environment (termasuk token) agar tidak ada data yang hilang.</li>
 * </ul></p>
 */
@Component
public class InsomniaV5ToOpenApi3Converter implements FormatConverter {

    private static final String OPENAPI_VERSION = "3.0.0";

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final YAMLMapper outYaml = (YAMLMapper) new YAMLMapper()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);

    @Override
    public SourceFormat source() {
        return SourceFormat.INSOMNIA_V5;
    }

    @Override
    public TargetFormat target() {
        return TargetFormat.OPENAPI_3_0;
    }

    @Override
    public ConversionResult convert(byte[] input, String originalName, OutputType output) {
        JsonNode root;
        try {
            root = yamlMapper.readTree(input);
        } catch (Exception e) {
            throw new IllegalArgumentException("Gagal membaca file Insomnia: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Struktur file Insomnia tidak valid.");
        }

        Map<String, Object> openapi = buildOpenApi(root);

        byte[] bytes;
        try {
            if (output == OutputType.JSON) {
                bytes = jsonMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(openapi);
            } else {
                bytes = outYaml.writeValueAsBytes(openapi);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gagal menserialisasi hasil: " + e.getMessage(), e);
        }

        String baseName = stripExtension(originalName == null ? "converted" : originalName);
        String filename = baseName + ".openapi." + output.extension();
        return new ConversionResult(bytes, filename, output.contentType());
    }

    // ----- pembangunan dokumen OpenAPI -----

    private Map<String, Object> buildOpenApi(JsonNode root) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", OPENAPI_VERSION);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", text(root, "name", "Converted API"));
        info.put("version", "1.0.0");
        info.put("description", root.path("meta").path("description").asText(""));
        doc.put("info", info);

        Context ctx = new Context();
        JsonNode collection = root.get("collection");
        if (collection != null && collection.isArray()) {
            walk(collection, null, ctx);
        }

        JsonNode environments = root.get("environments");
        doc.put("servers", buildServers(ctx, environments));
        doc.put("paths", ctx.paths);

        if (ctx.usesBearer) {
            Map<String, Object> bearer = new LinkedHashMap<>();
            bearer.put("type", "http");
            bearer.put("scheme", "bearer");
            Map<String, Object> schemes = new LinkedHashMap<>();
            schemes.put("bearerAuth", bearer);
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("securitySchemes", schemes);
            doc.put("components", components);
        }

        // Simpan seluruh variabel environment agar tidak hilang saat konversi.
        Map<String, Object> envExt = buildEnvironmentsExtension(environments);
        if (!envExt.isEmpty()) {
            doc.put("x-insomnia-environments", envExt);
        }

        return doc;
    }

    private void walk(JsonNode items, String currentTag, Context ctx) {
        for (JsonNode item : items) {
            boolean isFolder = item.has("children");
            boolean isRequest = item.has("url") && item.has("method");
            if (isFolder) {
                String tag = item.path("name").asText(currentTag);
                walk(item.get("children"), tag, ctx);
            } else if (isRequest) {
                addOperation(item, currentTag, ctx);
            }
        }
    }

    private void addOperation(JsonNode req, String tag, Context ctx) {
        String rawUrl = req.path("url").asText("");
        UrlParts parts = parseUrl(rawUrl);
        if (parts.isTemplate) {
            if (parts.baseVar != null && !parts.baseVar.isBlank()) {
                ctx.baseVars.add(parts.baseVar);
            }
            if (parts.templateRaw != null && !parts.templateRaw.isBlank()) {
                ctx.templateServers.add(parts.templateRaw);
            }
        } else if (parts.server != null && !parts.server.isBlank()) {
            ctx.absoluteServers.add(parts.server);
        }

        String method = req.path("method").asText("GET").toLowerCase(Locale.ROOT);
        String name = req.path("name").asText("");

        String pathKey = resolvePathKey(parts.path, method, ctx);

        Map<String, Object> op = new LinkedHashMap<>();
        if (!name.isBlank()) {
            op.put("summary", name);
        }
        String description = req.path("meta").path("description").asText("");
        op.put("description", description);
        op.put("operationId", uniqueOperationId(method, parts.path, name, ctx));
        if (tag != null && !tag.isBlank()) {
            op.put("tags", List.of(tag));
        }

        // parameters: header + query (dari array & dari URL)
        List<Map<String, Object>> parameters = buildParameters(req, parts.query);
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }

        // requestBody
        Map<String, Object> requestBody = buildRequestBody(req);
        if (requestBody != null) {
            op.put("requestBody", requestBody);
        }

        // security (bearer)
        JsonNode auth = req.get("authentication");
        if (auth != null && "bearer".equalsIgnoreCase(auth.path("type").asText(""))
                && !auth.path("disabled").asBoolean(false)) {
            ctx.usesBearer = true;
            op.put("security", List.of(Map.of("bearerAuth", List.of())));
        }

        op.put("responses", defaultResponses());

        ctx.paths.computeIfAbsent(pathKey, k -> new LinkedHashMap<>()).put(method, op);
    }

    private List<Map<String, Object>> buildParameters(JsonNode req, List<String> urlQueryNames) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        JsonNode params = req.get("parameters");
        if (params != null && params.isArray()) {
            for (JsonNode p : params) {
                if (p.path("disabled").asBoolean(false)) {
                    continue;
                }
                String pname = p.path("name").asText("");
                if (pname.isBlank() || !seen.add("query:" + pname)) {
                    continue;
                }
                parameters.add(param(pname, "query", p.path("value").asText("")));
            }
        }

        for (String qn : urlQueryNames) {
            if (!qn.isBlank() && seen.add("query:" + qn)) {
                parameters.add(param(qn, "query", ""));
            }
        }

        JsonNode headers = req.get("headers");
        if (headers != null && headers.isArray()) {
            for (JsonNode h : headers) {
                if (h.path("disabled").asBoolean(false)) {
                    continue;
                }
                String hname = h.path("name").asText("");
                if (hname.isBlank() || hname.equalsIgnoreCase("Content-Type")) {
                    continue;
                }
                if (!seen.add("header:" + hname)) {
                    continue;
                }
                parameters.add(param(hname, "header", h.path("value").asText("")));
            }
        }

        return parameters;
    }

    private Map<String, Object> param(String name, String in, String example) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", in);
        p.put("required", false);
        p.put("schema", Map.of("type", "string"));
        if (example != null && !example.isBlank()) {
            p.put("example", example);
        }
        return p;
    }

    private Map<String, Object> buildRequestBody(JsonNode req) {
        JsonNode body = req.get("body");
        if (body == null || !body.isObject()) {
            return null;
        }
        String mimeType = body.path("mimeType").asText("application/json");
        String textBody = body.path("text").asText("");
        if (textBody.isBlank()) {
            return null;
        }

        Object example;
        if (mimeType.toLowerCase(Locale.ROOT).contains("json")) {
            try {
                example = jsonMapper.readTree(textBody);
            } catch (Exception e) {
                example = textBody;
            }
        } else {
            example = textBody;
        }

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("schema", Map.of("type", "object"));
        media.put("example", example);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put(mimeType, media);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("content", content);
        return requestBody;
    }

    private Map<String, Object> defaultResponses() {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", Map.of("description", "Successful"));
        responses.put("400", Map.of("description", "Bad Request"));
        responses.put("500", Map.of("description", "Server Error"));
        return responses;
    }

    // ----- environments -> servers + ekstensi -----

    /**
     * Bangun daftar {@code servers}. Bila ada {@code environments}, tiap
     * sub-environment yang punya nilai untuk variabel base URL diubah menjadi
     * satu server (url ter-resolve, description = nama environment).
     */
    private List<Map<String, Object>> buildServers(Context ctx, JsonNode environments) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        boolean hasEnv = environments != null && environments.isObject();
        JsonNode baseData = hasEnv ? environments.get("data") : null;
        JsonNode subEnvs = hasEnv ? environments.get("subEnvironments") : null;

        Set<String> resolvedVars = new LinkedHashSet<>();
        if (subEnvs != null && subEnvs.isArray() && !ctx.baseVars.isEmpty()) {
            for (JsonNode env : subEnvs) {
                String envName = env.path("name").asText("env");
                JsonNode data = env.get("data");
                for (String var : ctx.baseVars) {
                    String value = lookupVar(data, baseData, var);
                    if (value != null && !value.isBlank()) {
                        resolvedVars.add(var);
                        if (seenUrls.add(value)) {
                            Map<String, Object> server = new LinkedHashMap<>();
                            server.put("url", value);
                            server.put("description", envName);
                            result.add(server);
                        }
                    }
                }
            }
        }

        // Variabel template yang tidak ter-resolve environment -> tetap pakai literalnya.
        for (String raw : ctx.templateServers) {
            String var = extractVar(raw);
            if (var != null && resolvedVars.contains(var)) {
                continue;
            }
            if (seenUrls.add(raw)) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("url", raw);
                result.add(server);
            }
        }

        // URL absolut (tanpa template).
        for (String s : ctx.absoluteServers) {
            if (seenUrls.add(s)) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("url", s);
                result.add(server);
            }
        }

        if (result.isEmpty()) {
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", "/");
            result.add(server);
        }
        return result;
    }

    /** Susun ekstensi x-insomnia-environments: nama environment -> data variabel. */
    private Map<String, Object> buildEnvironmentsExtension(JsonNode environments) {
        Map<String, Object> ext = new LinkedHashMap<>();
        if (environments == null || !environments.isObject()) {
            return ext;
        }

        JsonNode baseData = environments.get("data");
        if (baseData != null && baseData.isObject() && baseData.size() > 0) {
            String baseName = environments.path("name").asText("Base Environment");
            ext.put(baseName, jsonMapper.convertValue(baseData, LinkedHashMap.class));
        }

        JsonNode subEnvs = environments.get("subEnvironments");
        if (subEnvs != null && subEnvs.isArray()) {
            for (JsonNode env : subEnvs) {
                JsonNode data = env.get("data");
                if (data != null && data.isObject()) {
                    String name = env.path("name").asText("env");
                    ext.put(name, jsonMapper.convertValue(data, LinkedHashMap.class));
                }
            }
        }
        return ext;
    }

    private String lookupVar(JsonNode data, JsonNode baseData, String var) {
        if (data != null && data.hasNonNull(var)) {
            return data.get(var).asText();
        }
        if (baseData != null && baseData.hasNonNull(var)) {
            return baseData.get(var).asText();
        }
        return null;
    }

    /** Ambil nama variabel dari token template, mis. "{{ _.base_url }}" -> "base_url". */
    private String extractVar(String templateRaw) {
        if (templateRaw == null) {
            return null;
        }
        String v = templateRaw.replace("{{", "").replace("}}", "").trim();
        if (v.startsWith("_.")) {
            v = v.substring(2);
        }
        return v.isBlank() ? null : v;
    }

    // ----- URL & penamaan -----

    private String resolvePathKey(String path, String method, Context ctx) {
        Map<String, Object> existing = ctx.paths.get(path);
        if (existing == null || !existing.containsKey(method)) {
            return path;
        }
        // path + method bentrok: cari key unik dengan suffix -2, -3, ...
        int n = 2;
        String candidate;
        do {
            candidate = path + "-" + n++;
            Map<String, Object> c = ctx.paths.get(candidate);
            if (c == null || !c.containsKey(method)) {
                return candidate;
            }
        } while (true);
    }

    private String uniqueOperationId(String method, String path, String name, Context ctx) {
        String base = method + pascal(path.replace('/', ' '), true) + pascal(name, true);
        base = base.replaceAll("[^A-Za-z0-9]", "");
        if (base.isEmpty()) {
            base = method + "Operation";
        }
        String id = base;
        int n = 2;
        while (!ctx.operationIds.add(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    /** Capitalize tiap kata; bila splitWords=true pisah pada whitespace. */
    private String pascal(String input, boolean splitWords) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String[] words = splitWords ? input.trim().split("\\s+") : new String[]{input};
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            String cleaned = w.replaceAll("[^A-Za-z0-9]", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(cleaned.charAt(0)));
            if (cleaned.length() > 1) {
                sb.append(cleaned.substring(1));
            }
        }
        return sb.toString();
    }

    private UrlParts parseUrl(String rawUrl) {
        UrlParts parts = new UrlParts();
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty()) {
            parts.path = "/";
            return parts;
        }

        String remainder;
        if (url.startsWith("{{")) {
            parts.isTemplate = true;
            int close = url.indexOf("}}");
            if (close >= 0) {
                parts.templateRaw = url.substring(0, close + 2).trim();
                parts.baseVar = extractVar(parts.templateRaw);
                remainder = url.substring(close + 2);
            } else {
                remainder = url;
            }
        } else {
            int scheme = url.indexOf("://");
            if (scheme >= 0) {
                int slash = url.indexOf('/', scheme + 3);
                if (slash >= 0) {
                    parts.server = url.substring(0, slash);
                    remainder = url.substring(slash);
                } else {
                    parts.server = url;
                    remainder = "";
                }
            } else {
                // host[:port]/path tanpa scheme
                int slash = url.indexOf('/');
                if (slash > 0) {
                    parts.server = "http://" + url.substring(0, slash);
                    remainder = url.substring(slash);
                } else {
                    parts.server = "http://" + url;
                    remainder = "";
                }
            }
        }

        // pisahkan query
        int q = remainder.indexOf('?');
        String pathPart = q >= 0 ? remainder.substring(0, q) : remainder;
        String queryPart = q >= 0 ? remainder.substring(q + 1) : "";

        if (!pathPart.startsWith("/")) {
            pathPart = "/" + pathPart;
        }
        parts.path = pathPart.isEmpty() ? "/" : pathPart;

        if (!queryPart.isBlank()) {
            for (String pair : queryPart.split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                parts.query.add(eq >= 0 ? pair.substring(0, eq) : pair);
            }
        }

        return parts;
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

    /** Akumulasi state selama traversal koleksi. */
    private static final class Context {
        final Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        final Set<String> operationIds = new LinkedHashSet<>();
        final Set<String> absoluteServers = new LinkedHashSet<>();
        final Set<String> templateServers = new LinkedHashSet<>();
        final Set<String> baseVars = new LinkedHashSet<>();
        boolean usesBearer = false;
    }

    private static final class UrlParts {
        String server;
        boolean isTemplate = false;
        String templateRaw;
        String baseVar;
        String path = "/";
        final List<String> query = new ArrayList<>();
    }
}
