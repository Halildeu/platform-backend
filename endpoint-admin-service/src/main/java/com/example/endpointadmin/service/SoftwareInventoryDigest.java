package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BE-024 — canonical software-state digest builder shared by the ingest-time
 * history append ({@code EndpointSoftwareInventoryService}) and the read-time
 * diff ({@code EndpointSoftwareInventoryDiffService}).
 *
 * <p>Centralising the {@code appKey} derivation + the per-app whitelist here
 * guarantees the value the diff service compares is byte-identical to what
 * ingest persisted — there is exactly one definition of "what identifies an
 * app" and "what we keep about it".
 *
 * <h3>appKey — synthetic stable identity</h3>
 *
 * <p>{@link EndpointSoftwareInventoryItem} has NO packageId column (packageId
 * lives only on the separate winget catalog table, not joined here). The diff
 * identity is therefore a SHA-256 over the normalized natural key:
 *
 * <pre>{@code  sha256( lower(trim(displayName)) US lower(trim(publisher)) US msiProductCodeHash )}</pre>
 *
 * <p>where {@code US} is the ASCII unit-separator (0x1F) — a control char
 * that cannot appear in a sanitized display name / publisher, so the join is
 * collision- and injection-free. Hashing (rather than the raw joined string)
 * also keeps the key fixed-width. Normalization is locale-independent
 * ({@link Locale#ROOT}) and null-safe (a missing publisher / hash collapses
 * to an empty segment, so two rows that differ only by a present-vs-absent
 * publisher are NOT treated as the same app).
 */
public final class SoftwareInventoryDigest {

    /** Digest entry keys (also the JSONB object keys persisted in
     *  {@code apps_digest}). */
    public static final String KEY_APP_KEY = "appKey";
    public static final String KEY_DISPLAY_NAME = "displayName";
    public static final String KEY_PUBLISHER = "publisher";
    public static final String KEY_VERSION = "version";
    public static final String KEY_MSI_HASH = "msiProductCodeHash";

    /** ASCII unit separator (0x1F): joins fields within one app's key. */
    private static final String US = "\u001f";

    /** ASCII record separator (0x1E): joins entries in the digest hash. */
    private static final String RS = "\u001e";

    private SoftwareInventoryDigest() {
    }

    /**
     * Build the whitelist per-app digest for a freshly-parsed item set
     * (ingest path). Entries are sorted by {@code appKey} so the
     * {@link #digestHash(List)} of the same logical state is stable
     * regardless of the agent's apps[] ordering.
     */
    public static List<Map<String, Object>> fromItems(
            List<EndpointSoftwareInventoryItem> items) {
        List<Map<String, Object>> digest = new ArrayList<>();
        if (items == null) {
            return digest;
        }
        for (EndpointSoftwareInventoryItem item : items) {
            String displayName = item.getDisplayName();
            String publisher = item.getPublisher();
            String version = item.getDisplayVersion();
            String msiHash = item.getMsiProductCodeHash();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(KEY_APP_KEY, appKey(displayName, publisher, msiHash));
            entry.put(KEY_DISPLAY_NAME, displayName);
            entry.put(KEY_PUBLISHER, publisher);
            entry.put(KEY_VERSION, version);
            entry.put(KEY_MSI_HASH, msiHash);
            digest.add(entry);
        }
        digest.sort(Comparator.comparing(e -> String.valueOf(e.get(KEY_APP_KEY))));
        return digest;
    }

    /**
     * Deterministic SHA-256 over the canonical digest content (appKey +
     * version per entry, in appKey order). Two byte-identical states share
     * this hash so the diff service can recognise a no-change re-collect and
     * a future pruning job can collapse duplicates.
     */
    public static String digestHash(List<Map<String, Object>> digest) {
        StringBuilder canonical = new StringBuilder();
        if (digest != null) {
            List<Map<String, Object>> sorted = new ArrayList<>(digest);
            sorted.sort(Comparator.comparing(e -> String.valueOf(e.get(KEY_APP_KEY))));
            for (Map<String, Object> entry : sorted) {
                canonical.append(stringOrEmpty(entry.get(KEY_APP_KEY)))
                        .append(US)
                        .append(stringOrEmpty(entry.get(KEY_VERSION)))
                        .append(RS);
            }
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * SHA-256 over {@code lower(trim(displayName)) US lower(trim(publisher))
     * US msiProductCodeHash}, null-safe + locale-independent.
     */
    public static String appKey(String displayName, String publisher, String msiHash) {
        String name = normalize(displayName);
        String pub = normalize(publisher);
        // msiProductCodeHash is already a fixed sha256:<16hex> token; keep
        // its case as-is (lowercase hex by contract) but null -> empty.
        String hash = msiHash == null ? "" : msiHash.trim();
        String raw = name + US + pub + US + hash;
        return sha256Hex(raw);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is guaranteed present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
