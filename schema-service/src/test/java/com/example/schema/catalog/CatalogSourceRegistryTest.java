package com.example.schema.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The registry is what keeps two ERPs from being mistaken for one another
 * (gitops#3594), so these pin the three ways that could go wrong: a request
 * silently landing on the wrong source, an unknown source being answered with
 * the primary's data, and two readers claiming the same name.
 */
class CatalogSourceRegistryTest {

    private static CatalogReader reader(String sourceId, String engine) {
        CatalogReader reader = mock(CatalogReader.class);
        when(reader.sourceId()).thenReturn(sourceId);
        when(reader.engine()).thenReturn(engine);
        return reader;
    }

    @Test
    void absentSourceResolvesToThePrimaryLane() {
        CatalogReader workcube = reader(CatalogSourceRegistry.PRIMARY_SOURCE_ID, "mssql");
        CatalogSourceRegistry registry = new CatalogSourceRegistry(List.of(workcube));

        // Every caller written before the second source names none; they must
        // keep reaching MSSQL.
        assertThat(registry.resolve(null)).isSameAs(workcube);
        assertThat(registry.resolve("")).isSameAs(workcube);
        assertThat(registry.resolve("   ")).isSameAs(workcube);
    }

    @Test
    void aNamedSourceResolvesToItsOwnReader() {
        CatalogReader workcube = reader(CatalogSourceRegistry.PRIMARY_SOURCE_ID, "mssql");
        CatalogReader ifs = reader("ifs", "oracle");
        CatalogSourceRegistry registry = new CatalogSourceRegistry(List.of(workcube, ifs));

        assertThat(registry.resolve("ifs")).isSameAs(ifs);
        // Case and surrounding whitespace come from URLs and humans, not from
        // the configuration, so neither may change which database is read.
        assertThat(registry.resolve("IFS")).isSameAs(ifs);
        assertThat(registry.resolve("  ifs  ")).isSameAs(ifs);
        assertThat(registry.resolve(CatalogSourceRegistry.PRIMARY_SOURCE_ID)).isSameAs(workcube);
    }

    @Test
    void anUnknownSourceFailsRatherThanFallingBackToThePrimary() {
        CatalogSourceRegistry registry =
            new CatalogSourceRegistry(List.of(reader(CatalogSourceRegistry.PRIMARY_SOURCE_ID, "mssql")));

        // The dangerous failure mode is a quiet fallback: a caller asking for
        // 'ifs' would be shown Workcube's schema under the IFS name.
        assertThatThrownBy(() -> registry.resolve("ifs"))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining("ifs")
            .hasMessageContaining(CatalogSourceRegistry.PRIMARY_SOURCE_ID);
    }

    @Test
    void twoReadersClaimingOneSourceIsRejectedAtStartup() {
        // Silently keeping the last one registered would make which database
        // answers depend on bean ordering.
        assertThatThrownBy(() -> new CatalogSourceRegistry(
                List.of(reader("ifs", "oracle"), reader("ifs", "oracle"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ifs");
    }

    @Test
    void describeListsEverySourceWithItsEngine() {
        CatalogSourceRegistry registry = new CatalogSourceRegistry(List.of(
            reader(CatalogSourceRegistry.PRIMARY_SOURCE_ID, "mssql"),
            reader("ifs", "oracle")));

        assertThat(registry.describe()).containsExactly(
            java.util.Map.of("source", CatalogSourceRegistry.PRIMARY_SOURCE_ID, "engine", "mssql"),
            java.util.Map.of("source", "ifs", "engine", "oracle"));
        assertThat(registry.has("IFS")).isTrue();
        assertThat(registry.has("nope")).isFalse();
    }
}
