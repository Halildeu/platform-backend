package com.example.gpcore.domain;

/**
 * Data classification (ADR-0034 KVKK data boundary). Ordered by increasing
 * sensitivity; {@link #SPECIAL_CATEGORY} is KVKK "özel nitelikli kişisel veri".
 *
 * <p>Feeds the deny-overrides ABAC layer (ADR-0035 §3): at/above
 * {@link #RESTRICTED}, AI/export/download actions require a matching subject
 * clearance, else they are denied regardless of an OpenFGA allow.
 */
public enum Classification {
    PUBLIC,
    INTERNAL,
    CONFIDENTIAL,
    RESTRICTED,
    SPECIAL_CATEGORY;

    /** True when this classification requires an explicit clearance for sensitive actions. */
    public boolean requiresClearance() {
        return this.ordinal() >= RESTRICTED.ordinal();
    }

    /** Clearance token a subject must hold for sensitive actions on this classification. */
    public String clearanceToken() {
        // Locale.ROOT — never the default locale: on a Turkish-locale JVM (the
        // target deployment) "I" lower-cases to dotless "ı", which would silently
        // break clearance-token matching.
        return "clearance:" + name().toLowerCase(java.util.Locale.ROOT);
    }
}
