package com.formatter.api.web;

import com.formatter.api.converter.ConverterRegistry;
import com.formatter.api.converter.FormatConverter;
import com.formatter.api.converter.OutputType;
import com.formatter.api.converter.SourceFormat;
import com.formatter.api.converter.SourceFormatDetector;
import com.formatter.api.converter.TargetFormat;
import com.formatter.api.model.ConversionResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api")
public class ConversionController {

    // Stempel waktu untuk nama arsip: tahun-bulan-tanggal-jam-menit-detik-ms.
    private static final DateTimeFormatter ZIP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final ConverterRegistry registry;
    private final SourceFormatDetector detector;

    public ConversionController(ConverterRegistry registry, SourceFormatDetector detector) {
        this.registry = registry;
        this.detector = detector;
    }

    /** Daftar konversi yang didukung — dipakai frontend untuk mengisi pilihan. */
    @GetMapping("/formats")
    public List<Map<String, Object>> formats() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FormatConverter c : registry.all()) {
            result.add(Map.of(
                    "source", Map.of("id", c.source().id(), "label", c.source().label()),
                    "target", Map.of("id", c.target().id(), "label", c.target().label()),
                    "outputs", List.of(OutputType.YAML.id(), OutputType.JSON.id())
            ));
        }
        return result;
    }

    @PostMapping(value = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", defaultValue = "openapi-3.0") String target,
            @RequestParam(value = "output", defaultValue = "yaml") String output,
            @RequestParam(value = "source", required = false) String source) {

        if (file == null || file.isEmpty()) {
            return badRequest("File kosong atau tidak diunggah.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            return badRequest("Gagal membaca file: " + e.getMessage());
        }

        Optional<TargetFormat> targetFormat = TargetFormat.fromId(target);
        if (targetFormat.isEmpty()) {
            return badRequest("Format target tidak didukung: " + target);
        }

        Optional<OutputType> outputType = OutputType.fromId(output);
        if (outputType.isEmpty()) {
            return badRequest("Bentuk output tidak didukung: " + output);
        }

        SourceFormat sourceFormat;
        if (hasExplicitSource(source)) {
            Optional<SourceFormat> chosen = SourceFormat.fromId(source);
            if (chosen.isEmpty() || chosen.get() == SourceFormat.UNKNOWN) {
                return badRequest("Format sumber tidak didukung: " + source);
            }
            sourceFormat = chosen.get();
        } else {
            sourceFormat = detector.detect(bytes);
            if (sourceFormat == SourceFormat.UNKNOWN) {
                return badRequest("Format file sumber tidak dikenali.");
            }
        }

        Optional<FormatConverter> converter = registry.find(sourceFormat, targetFormat.get());
        if (converter.isEmpty()) {
            return badRequest("Konversi dari " + sourceFormat.label()
                    + " ke " + targetFormat.get().label() + " belum didukung.");
        }

        ConversionResult conversionResult;
        try {
            conversionResult = converter.get()
                    .convert(bytes, file.getOriginalFilename(), outputType.get());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Gagal mengonversi: " + e.getMessage()));
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + conversionResult.filename() + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .contentType(MediaType.parseMediaType(conversionResult.contentType()))
                .body(conversionResult.content());
    }

    /**
     * Konversi banyak file sekaligus. Hasil dibungkus menjadi satu arsip ZIP.
     * File yang gagal dikonversi dicatat di {@code _conversion-errors.txt} di
     * dalam ZIP, sementara file lain tetap diproses.
     */
    @PostMapping(value = "/convert/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> convertBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "target", defaultValue = "openapi-3.0") String target,
            @RequestParam(value = "output", defaultValue = "yaml") String output,
            @RequestParam(value = "source", required = false) String source) {

        if (files == null || files.length == 0) {
            return badRequest("Tidak ada file yang diunggah.");
        }

        Optional<TargetFormat> targetFormat = TargetFormat.fromId(target);
        if (targetFormat.isEmpty()) {
            return badRequest("Format target tidak didukung: " + target);
        }
        Optional<OutputType> outputType = OutputType.fromId(output);
        if (outputType.isEmpty()) {
            return badRequest("Bentuk output tidak didukung: " + output);
        }

        SourceFormat explicitSource = null;
        if (hasExplicitSource(source)) {
            Optional<SourceFormat> chosen = SourceFormat.fromId(source);
            if (chosen.isEmpty() || chosen.get() == SourceFormat.UNKNOWN) {
                return badRequest("Format sumber tidak didukung: " + source);
            }
            explicitSource = chosen.get();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        List<String> errors = new ArrayList<>();
        int success = 0;

        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            Set<String> usedNames = new LinkedHashSet<>();
            for (MultipartFile file : files) {
                String originalName = file.getOriginalFilename();
                try {
                    if (file.isEmpty()) {
                        throw new IllegalArgumentException("file kosong");
                    }
                    byte[] bytes = file.getBytes();

                    SourceFormat sourceFormat = explicitSource != null
                            ? explicitSource
                            : detector.detect(bytes);
                    if (sourceFormat == SourceFormat.UNKNOWN) {
                        throw new IllegalArgumentException("format sumber tidak dikenali");
                    }
                    FormatConverter converter = registry.find(sourceFormat, targetFormat.get())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "konversi dari " + sourceFormat.label() + " belum didukung"));

                    ConversionResult result = converter.convert(bytes, originalName, outputType.get());
                    String entryName = uniqueName(usedNames, result.filename());
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(result.content());
                    zip.closeEntry();
                    success++;
                } catch (Exception e) {
                    errors.add((originalName == null ? "(tanpa nama)" : originalName) + " — " + e.getMessage());
                }
            }

            if (!errors.isEmpty()) {
                zip.putNextEntry(new ZipEntry("_conversion-errors.txt"));
                zip.write(String.join("\n", errors).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Gagal membuat arsip: " + e.getMessage()));
        }

        if (success == 0) {
            return badRequest("Semua file gagal dikonversi:\n" + String.join("\n", errors));
        }

        String stamp = LocalDateTime.now().format(ZIP_STAMP);
        String zipName = "converted-bundle-" + stamp + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + zipName + "\"")
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.CONTENT_DISPOSITION)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(baos.toByteArray());
    }

    private String uniqueName(Set<String> used, String name) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        String candidate;
        do {
            candidate = stem + "-" + n++ + ext;
        } while (!used.add(candidate));
        return candidate;
    }

    /** True bila user memilih sumber secara eksplisit (bukan kosong / "auto"). */
    private boolean hasExplicitSource(String source) {
        return source != null && !source.isBlank() && !source.equalsIgnoreCase("auto");
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
