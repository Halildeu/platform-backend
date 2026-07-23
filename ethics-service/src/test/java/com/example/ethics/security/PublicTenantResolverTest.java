package com.example.ethics.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.config.EthicsProperties;
import com.example.ethics.config.PublicTenantProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublicTenantResolverTest {
    private static final UUID DEFAULT_ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final UUID FIRMA_ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private EthicsProperties props(UUID defaultOrg) {
        return new EthicsProperties(defaultOrg, Duration.ofMinutes(15), 210_000,
                "ethics-manager", "ethics-manager", true, 30);
    }

    private PublicTenantResolver resolver(Map<String, UUID> hosts) {
        return new PublicTenantResolver(props(DEFAULT_ORG), new PublicTenantProperties(hosts));
    }

    @Test
    void unmappedHostFallsBackToDefaultOrg() {
        PublicTenantResolver resolver = resolver(Map.of());
        assertThat(resolver.resolve("etik.acik.com")).isEqualTo(DEFAULT_ORG);
        assertThat(resolver.resolve("speakup.acik.com")).isEqualTo(DEFAULT_ORG);
    }

    @Test
    void configuredHostResolvesToTenantOrg() {
        PublicTenantResolver resolver = resolver(Map.of("ihbar.firma.com", FIRMA_ORG));
        assertThat(resolver.resolve("ihbar.firma.com")).isEqualTo(FIRMA_ORG);
    }

    @Test
    void hostMatchIsCaseInsensitive() {
        PublicTenantResolver resolver = resolver(Map.of("Ihbar.FIRMA.com", FIRMA_ORG));
        assertThat(resolver.resolve("IHBAR.firma.COM")).isEqualTo(FIRMA_ORG);
        assertThat(resolver.resolve("  ihbar.firma.com  ")).isEqualTo(FIRMA_ORG);
    }

    @Test
    void nullOrBlankHostFallsBackToDefaultOrg() {
        PublicTenantResolver resolver = resolver(Map.of("ihbar.firma.com", FIRMA_ORG));
        assertThat(resolver.resolve(null)).isEqualTo(DEFAULT_ORG);
        assertThat(resolver.resolve("")).isEqualTo(DEFAULT_ORG);
        assertThat(resolver.resolve("   ")).isEqualTo(DEFAULT_ORG);
    }

    @Test
    void multipleTenantsAreIsolated() {
        UUID firmaB = UUID.fromString("22222222-2222-2222-2222-222222222222");
        PublicTenantResolver resolver = resolver(Map.of(
                "ihbar.firma-a.com", FIRMA_ORG,
                "ihbar.firma-b.com", firmaB));
        assertThat(resolver.resolve("ihbar.firma-a.com")).isEqualTo(FIRMA_ORG);
        assertThat(resolver.resolve("ihbar.firma-b.com")).isEqualTo(firmaB);
        assertThat(resolver.resolve("etik.acik.com")).isEqualTo(DEFAULT_ORG);
    }

    @Test
    void defaultOrgHonoursConfiguredPublicOrgId() {
        UUID otherDefault = UUID.fromString("99999999-9999-9999-9999-999999999999");
        PublicTenantResolver resolver = new PublicTenantResolver(
                props(otherDefault), new PublicTenantProperties(Map.of()));
        assertThat(resolver.resolve("anything.example.com")).isEqualTo(otherDefault);
    }
}
