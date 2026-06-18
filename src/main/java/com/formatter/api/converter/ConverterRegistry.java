package com.formatter.api.converter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Memilih {@link FormatConverter} yang sesuai berdasar pasangan (source, target).
 * Semua bean FormatConverter di-inject otomatis oleh Spring, sehingga menambah
 * konverter baru tidak memerlukan perubahan di sini maupun di controller.
 */
@Component
public class ConverterRegistry {

    private final List<FormatConverter> converters;

    public ConverterRegistry(List<FormatConverter> converters) {
        this.converters = converters;
    }

    public Optional<FormatConverter> find(SourceFormat source, TargetFormat target) {
        return converters.stream()
                .filter(c -> c.supports(source, target))
                .findFirst();
    }

    public List<FormatConverter> all() {
        return converters;
    }
}
