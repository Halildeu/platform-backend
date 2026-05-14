package com.example.report.contract.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R16 PR-C — Source-side {@link AuthzReferenceRegistry} implementation.
 *
 * <p>Canonical OpenFGA model dosyasından ({@code backend/openfga/model.fga})
 * type tanımlarını parse eder. {@code type report_group}, {@code type report},
 * {@code type module}, vb. — bu types üzerinden instance key'leri (örn.
 * {@code report_group:FINANCE_REPORTS}) WARN-first contract gate'ine girer.
 *
 * <p><b>Sınırlama (PR-C kapsamı)</b>: OpenFGA DSL type'ları obje id envanteri
 * taşımaz (Codex 019e27f5 önerisi). Bu yüzden registry sadece:
 * <ul>
 *   <li>type X tanımı var mı (örn. {@code type report_group} eklenmiş mi)?</li>
 * </ul>
 * Spesifik {@code report_group:FINANCE_REPORTS} INSTANCE varlığı runtime
 * tuple seed sorumluluğundadır (PR-B-2 kapsamı). RC-012 PR-C kapsamında
 * SADECE type-level kontrol yapar.
 *
 * <p>Source path resolution: working directory'den canonical {@code backend/openfga/model.fga}
 * okunur. Maven module root'tan koşulduğunda relative path:
 * {@code ../backend/openfga/model.fga}. Test'ten koşulduğunda explicit override.
 */
public final class OpenFgaModelAuthzReferenceRegistry implements AuthzReferenceRegistry {

    private static final Logger log = LoggerFactory.getLogger(
            OpenFgaModelAuthzReferenceRegistry.class);

    private static final Pattern TYPE_PATTERN = Pattern.compile("^type\\s+(\\w+)\\s*$",
            Pattern.MULTILINE);

    private final Path canonicalModelPath;
    private final Set<String> reportGroups;
    private final Set<String> permissions;

    /**
     * Default constructor — canonical model `../backend/openfga/model.fga`
     * (Maven module root'tan).
     */
    public OpenFgaModelAuthzReferenceRegistry() {
        this(Path.of("../backend/openfga/model.fga"));
    }

    public OpenFgaModelAuthzReferenceRegistry(Path canonicalModelPath) {
        this.canonicalModelPath = canonicalModelPath;
        this.reportGroups = computeReportGroups();
        this.permissions = Collections.emptySet(); // PR-C kapsamı dışı (PR-C-2)
    }

    @Override
    public Set<String> knownReportGroups() {
        return reportGroups;
    }

    @Override
    public Set<String> knownPermissions() {
        return permissions;
    }

    /**
     * Canonical model dosyasında {@code type report_group} tanımı varsa,
     * R16 PR-B sonrası report_group authz contract aktiftir.
     *
     * @return type report_group present in canonical model
     */
    public boolean isReportGroupTypeRegistered() {
        return knownTypes().contains("report_group");
    }

    /**
     * Tüm OpenFGA type tanımlarını canonical model'den parse et.
     */
    public Set<String> knownTypes() {
        if (!Files.exists(canonicalModelPath)) {
            log.warn("Canonical OpenFGA model dosyası bulunamadı: {}", canonicalModelPath);
            return Collections.emptySet();
        }
        try {
            String content = Files.readString(canonicalModelPath);
            Matcher m = TYPE_PATTERN.matcher(content);
            Set<String> types = new HashSet<>();
            while (m.find()) {
                types.add(m.group(1));
            }
            return Collections.unmodifiableSet(types);
        } catch (IOException ex) {
            log.warn("Canonical OpenFGA model okuma hatası: {}", ex.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> computeReportGroups() {
        // PR-C kapsamı: type report_group var mı kontrol et (instance key'leri
        // PR-B-2 runtime'da gelir). Authz catalog source registry için type
        // varlığı yeterli — type yoksa hiçbir reportGroup authz'lanamaz.
        // Type varsa, instance key'leri WARN-first registry'den döner (boş
        // başlangıç; debt yaml manuel doldurulur).
        if (isReportGroupTypeRegistered()) {
            // Type var → registry açık. Specific keys runtime tuple seed
            // ile dolar; source-side hardcoded set tutmak yanlış olur.
            // Bu yüzden boş döner ama RC-012 type varlığını kontrol eder.
            return Collections.emptySet();
        }
        return Collections.emptySet();
    }
}
