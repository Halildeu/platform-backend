package com.example.report.schema.tier;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 Program 8b — CommittedSnapshotLoader Tier 2 unit tests.
 *
 * <p>Spec §5.1:
 * <ul>
 *   <li>{@code CommittedSnapshotLoader_lookupAccountCardRowsAmount}: classpath
 *       fixture'da ACCOUNT_CARD_ROWS.AMOUNT type=DECIMAL(18,2) doğrula</li>
 *   <li>{@code CommittedSnapshotLoader_unknownSchemaTable_throwsMiss}: yeni
 *       table committed snapshot'ta yok → Optional.empty (caller Tier 3'e düşer)</li>
 *   <li>{@code CommittedSnapshotLoader_snapshotAgeWarn_at31Days}: Clock fixed
 *       to 31 gün sonra → snapshotAgeDays() > 30 (Q4 default 30d threshold)</li>
 * </ul>
 */
class CommittedSnapshotLoaderTest {

    private CommittedSnapshotLoader loader;
    private SchemaTruthLookupContext ctx;

    @BeforeEach
    void setUp() {
        loader = new CommittedSnapshotLoader(
                new DefaultResourceLoader(),
                new ObjectMapper(),
                "classpath:schema/workcube-schema-fixture.json");
        loader.loadSnapshot();

        ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.BUILD_DETERMINISTIC, "test_consumer");
    }

    @Test
    void lookupAccountCardRowsAmount_returnsExpectedColumnType() {
        Optional<SchemaSnapshot> result = loader.lookup(ctx, "workcube_mikrolink_2026_35");

        assertThat(result).isPresent();
        SchemaSnapshot snapshot = result.get();
        assertThat(snapshot.tables()).containsKey("ACCOUNT_CARD_ROWS");

        SchemaSnapshot.TableInfo accountCardRows = snapshot.tables().get("ACCOUNT_CARD_ROWS");
        assertThat(accountCardRows.columns())
                .anyMatch(col -> "AMOUNT".equals(col.name())
                        && "DECIMAL(18,2)".equals(col.dataType()));
    }

    @Test
    void unknownSchema_returnsEmpty_callerFallsToTier3() {
        // Yeni schema committed snapshot'ta yok → caller Tier 3'e düşer
        Optional<SchemaSnapshot> result = loader.lookup(ctx, "workcube_brand_new_schema_2027_99");

        assertThat(result).isEmpty();
    }

    @Test
    void snapshotAgeDays_returnsAtLeast30_when_clockIs31DaysAhead() {
        // mtime ne olursa olsun (test fixture mtime'ı), 31 gün sonra > 30 olur.
        // Snapshot loaded at @BeforeEach; Clock 31 gün ahead.
        Optional<Long> ageNow = loader.snapshotAgeDays(Clock.systemUTC());
        // İlk yükleme anı: ~0 gün
        assertThat(ageNow).isPresent();

        // Future clock — 31 gün ileri
        Clock futureClock = Clock.fixed(
                Instant.now().plus(Duration.ofDays(31)),
                ZoneId.systemDefault());
        Optional<Long> ageFuture = loader.snapshotAgeDays(futureClock);
        assertThat(ageFuture).isPresent();
        // 31 gün ileride age >= 30 (Plan §3.8 Q4 default threshold)
        assertThat(ageFuture.get()).isGreaterThanOrEqualTo(30L);
    }
}
