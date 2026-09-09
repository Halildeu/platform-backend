package com.example.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gitops#3603 — schema-service matches SQL identifiers by upper/lower-casing
 * them. {@code String.toUpperCase()} without a locale follows the JVM default,
 * and on {@code tr_TR} it turns {@code i} into U+0130 (dotted capital I), which
 * is outside {@code [A-Z]} and never equals the catalogue's own spelling — every
 * identifier containing an {@code i} then silently stops matching (caught live in
 * RelationshipDiscoveryService, gitops#3594). This test is the machine-enforced
 * ban: every case conversion in main sources must name {@code Locale.ROOT}.
 */
class LocaleSafeCaseConversionTest {

    private static final Pattern LOCALE_LESS = Pattern.compile("\\.(toUpperCase|toLowerCase)\\(\\)");

    @Test
    void everyCaseConversionInMainSourcesNamesALocale() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(root).isDirectory();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    String code = line.replaceFirst("//.*$", "");
                    Matcher m = LOCALE_LESS.matcher(code);
                    if (m.find()) {
                        offenders.add(root.relativize(file) + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("locale-less toUpperCase()/toLowerCase() — use Locale.ROOT (gitops#3603)")
                .isEmpty();
    }
}
