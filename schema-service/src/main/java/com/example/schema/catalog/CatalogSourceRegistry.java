package com.example.schema.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Resolves a request's {@code source} to the reader that serves it.
 *
 * <p>The service spoke to exactly one database until gitops#3594 added the IFS
 * ERP Oracle instance, so callers that name no source keep reaching the Workcube
 * MSSQL reader and every existing URL behaves as before.
 *
 * <p>An unknown source is an error, never a silent fall back to the primary: a
 * caller asking for {@code ifs} and quietly receiving Workcube tables would be
 * shown one ERP's schema under another ERP's name.
 */
@Component
public class CatalogSourceRegistry {

    /** The source a request reaches when it names none — the original MSSQL lane. */
    public static final String PRIMARY_SOURCE_ID = "workcube";

    private static final Logger log = LoggerFactory.getLogger(CatalogSourceRegistry.class);

    private final Map<String, CatalogReader> readers = new LinkedHashMap<>();

    public CatalogSourceRegistry(List<CatalogReader> discovered) {
        for (CatalogReader reader : discovered) {
            CatalogReader clash = readers.put(reader.sourceId(), reader);
            if (clash != null) {
                throw new IllegalStateException(
                    "Two catalog readers claim source '" + reader.sourceId() + "': "
                        + clash.getClass().getSimpleName() + " and "
                        + reader.getClass().getSimpleName());
            }
        }
        log.info("Catalog sources registered: {}", describe());
    }

    /** The reader for {@code sourceId}, or the primary one when it is null/blank. */
    public CatalogReader resolve(String sourceId) {
        String key = (sourceId == null || sourceId.isBlank())
            ? PRIMARY_SOURCE_ID
            : sourceId.trim().toLowerCase(java.util.Locale.ROOT);
        CatalogReader reader = readers.get(key);
        if (reader == null) {
            throw new NoSuchElementException(
                "Unknown catalog source '" + key + "'; configured: " + readers.keySet());
        }
        return reader;
    }

    /** Every configured source with the engine behind it, for the source picker. */
    public List<Map<String, Object>> describe() {
        return readers.values().stream()
            .map(r -> Map.<String, Object>of("source", r.sourceId(), "engine", r.engine()))
            .toList();
    }

    /** Every registered reader, primary first (registration order). */
    public List<CatalogReader> readers() {
        return List.copyOf(readers.values());
    }

    public boolean has(String sourceId) {
        return sourceId != null && readers.containsKey(sourceId.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
