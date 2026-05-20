package com.serban.notify.preference;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.repository.SubscriberPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubscriberPreferenceService integration test — Faz 23.2.A T1.1.9 closure
 * (Codex thread {@code 019e42d6} P2 acceptance sweep).
 *
 * <p>The existing {@code SubscriberPreferenceServiceTest} mocks the
 * repository and exercises the precedence + critical-bypass + quiet-hours
 * decision logic in pure Java. This IT exercises the SAME contract through
 * a real Spring Boot context + Testcontainers Postgres + JPA repository,
 * so the JPA derived-query SQL ({@code findByOrgIdAndSubscriberIdAndTopicKeyAndChannel}
 * + the three NULL-fallback variants), the {@code bypass_for_critical}
 * column mapping, the {@code jsonb quiet_hours} column round-trip, and the
 * {@code FrequencyLimitService} in-memory window are verified end-to-end
 * against real Postgres NULL semantics. M3 T1.1.6 (quiet hours) and
 * T1.1.7 (frequency limit) acceptance — including critical-severity
 * bypass under both — land in this IT (Codex thread {@code 019e4469}
 * PARTIAL absorb).
 *
 * <p>Twelve scenarios:
 * <ol>
 *   <li>No preference row → ALLOWED (default no_preference_set)</li>
 *   <li>Channel+topic row, enabled=true → ALLOWED</li>
 *   <li>Channel+topic row, enabled=false → DENIED (preference_disabled)</li>
 *   <li>enabled=false + severity=critical + bypass_for_critical=true →
 *       ALLOWED (critical_bypass)</li>
 *   <li>enabled=false + severity=critical + bypass_for_critical=false →
 *       DENIED (preference_disabled, bypass not honored)</li>
 *   <li>Channel-null wildcard row (topic-specific, all channels)
 *       enabled=false → DENIED — precedence-2 fallback hits the JPA
 *       {@code findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull}
 *       derived query.</li>
 *   <li>Topic-null wildcard row (all topics, channel-specific)
 *       enabled=false → DENIED — precedence-3 fallback hits
 *       {@code findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel}.</li>
 *   <li>Both-null wildcard ("mute all") row enabled=false → DENIED —
 *       precedence-4 fallback hits
 *       {@code findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannelIsNull}.
 *       Codex 019e0c28 iter P1 regression guard: must not silently
 *       fall through to no_preference_set.</li>
 *   <li><b>T1.1.6 quiet hours deny (non-critical)</b>: enabled pref +
 *       quietHours {@code {start:"22:00",end:"07:00",tz:"Europe/Istanbul"}}
 *       + fixed clock at 20:30 UTC (= 23:30 Istanbul, within window) +
 *       severity=info → DENIED ({@code quiet_hours}). Exercises the
 *       full {@code jsonb} round-trip + timezone path.</li>
 *   <li><b>T1.1.6 quiet hours bypass (critical)</b>: same window + same
 *       fixed clock + bypassForCritical=true + severity=critical →
 *       ALLOWED ({@code critical_bypass_quiet_hours}).</li>
 *   <li><b>T1.1.7 frequency limit deny (non-critical, over limit)</b>:
 *       enabled pref + frequencyLimitPerDay=1; first non-critical send
 *       ALLOWED (window fills), second non-critical send DENIED
 *       ({@code frequency_limit}). Exercises real
 *       {@code FrequencyLimitService} bean — no @TestConfiguration mock.</li>
 *   <li><b>T1.1.7 frequency limit bypass (critical) + window
 *       conservation</b>: enabled pref + bypassForCritical=true +
 *       frequencyLimitPerDay=1; first non-critical fills window,
 *       critical send ALLOWED ({@code critical_bypass_frequency}),
 *       then a follow-up non-critical send STILL DENIED — proves the
 *       critical bypass did NOT consume a slot in the rolling window.</li>
 * </ol>
 *
 * <p>Each scenario seeds a row through the repository, then evaluates a
 * fresh intent and verifies the decision via the public service API. The
 * {@code @DirtiesContext} class-mode keeps every test in its own context
 * and the {@code testKey()} helper assigns each scenario a unique
 * (subscriberId, topicKey, channel) tuple so reused Testcontainers DB
 * state cannot leak between methods even if context refresh were skipped.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class SubscriberPreferenceServiceIntegrationTest extends AbstractPostgresTest {

    @Autowired SubscriberPreferenceService service;
    @Autowired SubscriberPreferenceRepository prefRepo;
    @Autowired FrequencyLimitService frequencyLimitService;

    @BeforeEach
    void cleanPrefRows() {
        prefRepo.deleteAll();
        // FrequencyLimitService holds an in-memory ConcurrentHashMap of rolling
        // windows keyed by (orgId, subscriberId). Clear it between tests so a
        // window seeded in one frequency-limit scenario cannot leak into the
        // next. The repository wipe + unique TestKey already isolate DB state;
        // clearAll() extends that isolation to the service's runtime cache.
        frequencyLimitService.clearAll();
        // Re-arm default system clock at the start of each test in case a
        // previous quiet-hours scenario injected a fixed clock — the service
        // is a singleton, the setter mutates shared state.
        service.setClock(Clock.systemDefaultZone());
    }

    @Test
    void noPreferenceRowAllowsByDefault() {
        TestKey k = testKey("noPreferenceRowAllowsByDefault");

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed())
            .as("absence of preference row must default to ALLOW so the system "
                + "doesn't silently mute deliveries when no preference is set")
            .isTrue();
        assertThat(decision.reason()).isEqualTo("no_preference_set");
    }

    @Test
    void enabledChannelTopicRowAllows() {
        TestKey k = testKey("enabledChannelTopicRowAllows");
        seedPreference(k, /*enabled=*/ true, /*bypassForCritical=*/ true);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("preference_enabled");
    }

    @Test
    void disabledChannelTopicRowDenies() {
        TestKey k = testKey("disabledChannelTopicRowDenies");
        seedPreference(k, /*enabled=*/ false, /*bypassForCritical=*/ false);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    @Test
    void disabledPreferenceWithCriticalBypassAllowsCriticalSeverity() {
        TestKey k = testKey("disabledPreferenceWithCriticalBypassAllowsCriticalSeverity");
        seedPreference(k, /*enabled=*/ false, /*bypassForCritical=*/ true);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.critical),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed())
            .as("critical severity must override a disabled preference "
                + "when bypass_for_critical=true (must-have #8 contract)")
            .isTrue();
        assertThat(decision.reason()).isEqualTo("critical_bypass");
    }

    @Test
    void disabledPreferenceWithoutCriticalBypassStillDeniesCriticalSeverity() {
        TestKey k = testKey("disabledPreferenceWithoutCriticalBypassStillDeniesCriticalSeverity");
        seedPreference(k, /*enabled=*/ false, /*bypassForCritical=*/ false);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.critical),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed())
            .as("critical severity must NOT bypass the preference when the "
                + "subscriber explicitly set bypass_for_critical=false — "
                + "this lets a user opt-out of even critical alerts on a "
                + "given channel (e.g. SMS) per the must-have #8 contract")
            .isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    /* ----- Fallback precedence (Codex iter-1 P2 absorb) ----- */

    /**
     * Precedence-2 fallback: when no exact (topic, channel) row exists
     * but a (topic, channel=null) row does, the service must honor the
     * channel-null row. This verifies the JPA derived query
     * {@code findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull}
     * against real Postgres NULL semantics — mocked unit tests do not
     * exercise the SQL emitted for {@code IS NULL} predicates.
     */
    @Test
    void channelWildcardFallbackDeniesWhenNoExactRow() {
        TestKey k = testKey("channelWildcardFallbackDeniesWhenNoExactRow");
        seedPreferenceWithKeys(k.subscriberId(), k.topic(), /*channel=*/ null,
            /*enabled=*/ false, /*bypassForCritical=*/ false);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    /**
     * Precedence-3 fallback: when neither exact nor channel-null row
     * exists but a (topic=null, channel) row does (the subscriber has
     * disabled a channel across all topics), the service must honor it.
     * Verifies {@code findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel}.
     */
    @Test
    void topicWildcardFallbackDeniesWhenNoExactOrChannelWildcardRow() {
        TestKey k = testKey("topicWildcardFallbackDeniesWhenNoExactOrChannelWildcardRow");
        seedPreferenceWithKeys(k.subscriberId(), /*topic=*/ null, k.channel(),
            /*enabled=*/ false, /*bypassForCritical=*/ false);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    /**
     * Precedence-4 (both-null) fallback: a "mute all topics + channels"
     * row must be honored as the last fallback. Without this branch the
     * service would silently fall through to {@code no_preference_set}
     * → ALLOW, mis-honoring the subscriber's intent to mute every
     * notification. Codex 019e0c28 iter P1 regression guard.
     */
    @Test
    void bothNullWildcardFallbackDeniesAsLastResort() {
        TestKey k = testKey("bothNullWildcardFallbackDeniesAsLastResort");
        seedPreferenceWithKeys(k.subscriberId(), /*topic=*/ null, /*channel=*/ null,
            /*enabled=*/ false, /*bypassForCritical=*/ false);

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    /* ----- T1.1.6 quiet hours (Codex 019e4469 PARTIAL absorb) -------- */

    /**
     * Quiet hours deny path for non-critical severity.
     *
     * <p>Seeds an enabled preference with a real quiet-hours JSON contract
     * ({@code {"start":"22:00","end":"07:00","tz":"Europe/Istanbul"}}) and
     * pins the service clock to {@code 2026-05-10T20:30:00Z} — that is
     * {@code 23:30 Europe/Istanbul}, squarely inside the cross-day window.
     * Codex 019e4469 absorb: use the real {@code Clock.fixed} injection
     * rather than a degenerate {@code 00:00-23:59} window so the timezone
     * arithmetic and cross-day window logic are actually exercised.
     */
    @Test
    void quietHoursWindowDeniesNonCritical() {
        TestKey k = testKey("quietHoursWindowDeniesNonCritical");
        seedPreferenceFull(
            k.subscriberId(), k.topic(), k.channel(),
            /*enabled=*/ true, /*bypassForCritical=*/ false,
            quietHoursIstanbulNight(),
            /*frequencyLimitPerDay=*/ null
        );
        service.setClock(Clock.fixed(
            Instant.parse("2026-05-10T20:30:00Z"),  // 23:30 Europe/Istanbul
            ZoneOffset.UTC
        ));

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed())
            .as("a non-critical send INSIDE the subscriber's quiet hours "
                + "window must be denied with reason quiet_hours — T1.1.6 "
                + "acceptance gate; tz=Europe/Istanbul cross-day window")
            .isFalse();
        assertThat(decision.reason()).isEqualTo("quiet_hours");
    }

    /**
     * Quiet hours critical bypass: severity=critical + bypassForCritical=true
     * → ALLOWED inside the same window, with reason
     * {@code critical_bypass_quiet_hours}. Confirms the bypass is BOTH
     * gate-conditional (bypassForCritical) AND severity-conditional
     * (critical) — neither alone should bypass.
     */
    @Test
    void quietHoursWindowBypassesForCritical() {
        TestKey k = testKey("quietHoursWindowBypassesForCritical");
        seedPreferenceFull(
            k.subscriberId(), k.topic(), k.channel(),
            /*enabled=*/ true, /*bypassForCritical=*/ true,
            quietHoursIstanbulNight(),
            /*frequencyLimitPerDay=*/ null
        );
        service.setClock(Clock.fixed(
            Instant.parse("2026-05-10T20:30:00Z"),
            ZoneOffset.UTC
        ));

        var decision = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.critical),
            k.channel(),
            k.subscriberId()
        );

        assertThat(decision.allowed())
            .as("critical severity + bypassForCritical=true must override "
                + "quiet hours suppression — T1.1.6 bypass gate")
            .isTrue();
        assertThat(decision.reason()).isEqualTo("critical_bypass_quiet_hours");
    }

    /* ----- T1.1.7 frequency limit (Codex 019e4469 PARTIAL absorb) ---- */

    /**
     * Frequency limit deny path for non-critical severity.
     *
     * <p>Seeds an enabled preference with {@code frequency_limit_per_day=1}
     * and exercises the real {@code FrequencyLimitService} singleton — no
     * {@code @TestConfiguration} mock. The first non-critical send fills
     * the rolling window (ALLOWED, reason {@code preference_enabled}); the
     * second non-critical send is suppressed (DENIED, reason
     * {@code frequency_limit}).
     */
    @Test
    void frequencyLimitDeniesNonCriticalWhenOverLimit() {
        TestKey k = testKey("frequencyLimitDeniesNonCriticalWhenOverLimit");
        seedPreferenceFull(
            k.subscriberId(), k.topic(), k.channel(),
            /*enabled=*/ true, /*bypassForCritical=*/ false,
            /*quietHours=*/ null,
            /*frequencyLimitPerDay=*/ 1
        );

        var first = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );
        var second = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(first.allowed()).isTrue();
        assertThat(first.reason()).isEqualTo("preference_enabled");
        assertThat(second.allowed())
            .as("the second non-critical send within the rolling window "
                + "must be denied with reason frequency_limit when "
                + "frequency_limit_per_day=1 — T1.1.7 acceptance gate")
            .isFalse();
        assertThat(second.reason()).isEqualTo("frequency_limit");
    }

    /**
     * Frequency limit critical bypass + window conservation.
     *
     * <p>Seeds {@code frequency_limit_per_day=1} + {@code bypassForCritical=true}.
     * Sequence:
     * <ol>
     *   <li>1st non-critical → ALLOWED ({@code preference_enabled}); window
     *       count=1 (full).</li>
     *   <li>Critical → ALLOWED ({@code critical_bypass_frequency}); MUST
     *       NOT consume a slot.</li>
     *   <li>2nd non-critical → DENIED ({@code frequency_limit}); proves
     *       the critical bypass did not extend the window — Codex
     *       019e4469 strong assertion.</li>
     * </ol>
     */
    @Test
    void frequencyLimitBypassesForCriticalAndDoesNotConsumeWindow() {
        TestKey k = testKey("frequencyLimitBypassesForCriticalAndDoesNotConsumeWindow");
        seedPreferenceFull(
            k.subscriberId(), k.topic(), k.channel(),
            /*enabled=*/ true, /*bypassForCritical=*/ true,
            /*quietHours=*/ null,
            /*frequencyLimitPerDay=*/ 1
        );

        var first = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );
        var critical = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.critical),
            k.channel(),
            k.subscriberId()
        );
        var third = service.evaluate(
            makeIntent(k.topic(), NotificationIntent.Severity.info),
            k.channel(),
            k.subscriberId()
        );

        assertThat(first.allowed()).isTrue();
        assertThat(first.reason()).isEqualTo("preference_enabled");
        assertThat(critical.allowed())
            .as("critical severity + bypassForCritical=true must override "
                + "frequency suppression — T1.1.7 bypass gate")
            .isTrue();
        assertThat(critical.reason()).isEqualTo("critical_bypass_frequency");
        assertThat(third.allowed())
            .as("a follow-up non-critical send must still be denied — "
                + "the critical bypass branch returns BEFORE calling "
                + "frequencyLimitService.checkAndRecord(), so the rolling "
                + "window is unchanged. Codex 019e4469 strong assertion: "
                + "without this, critical traffic could silently reset "
                + "the user's frequency budget.")
            .isFalse();
        assertThat(third.reason()).isEqualTo("frequency_limit");
    }

    /* ----- Helpers --------------------------------------------------- */

    /**
     * Per-test unique key tuple. Even though {@code @DirtiesContext}
     * AFTER_EACH refreshes the Spring context, the Testcontainers
     * Postgres is reused across the suite; the {@code @BeforeEach}
     * {@code deleteAll()} keeps rows isolated, and the unique tuple
     * keeps decisions distinguishable in any diagnostic dump.
     */
    private record TestKey(String subscriberId, String topic, String channel) {}

    private TestKey testKey(String testName) {
        // Truncate to fit subscriber_id VARCHAR(128) and topic_key VARCHAR(128).
        String shortHash = Integer.toHexString(testName.hashCode());
        return new TestKey(
            "1204-" + shortHash,
            "auth.password-reset." + shortHash,
            "email"
        );
    }

    private void seedPreference(TestKey k, boolean enabled, boolean bypassForCritical) {
        seedPreferenceWithKeys(k.subscriberId(), k.topic(), k.channel(),
            enabled, bypassForCritical);
    }

    /**
     * Wildcard-aware seeder — accepts nullable {@code topic} and
     * {@code channel} so the fallback IT cases can install rows that
     * exercise the JPA derived queries with {@code IS NULL} predicates.
     */
    private void seedPreferenceWithKeys(
        String subscriberId, String topic, String channel,
        boolean enabled, boolean bypassForCritical
    ) {
        seedPreferenceFull(subscriberId, topic, channel, enabled, bypassForCritical,
            /*quietHours=*/ null, /*frequencyLimitPerDay=*/ null);
    }

    /**
     * Full-control seeder — adds optional {@code quietHours} JSON and
     * {@code frequencyLimitPerDay} so the T1.1.6 / T1.1.7 scenarios can
     * install preferences that exercise the jsonb column round-trip and
     * the FrequencyLimitService bridge. Other fields default to the same
     * values used by the must-have #8 scenarios.
     */
    private void seedPreferenceFull(
        String subscriberId, String topic, String channel,
        boolean enabled, boolean bypassForCritical,
        Map<String, Object> quietHours,
        Integer frequencyLimitPerDay
    ) {
        SubscriberPreference p = new SubscriberPreference();
        p.setOrgId("default");
        p.setSubscriberId(subscriberId);
        p.setTopicKey(topic);
        p.setChannel(channel);
        p.setEnabled(enabled);
        p.setBypassForCritical(bypassForCritical);
        p.setQuietHours(quietHours);
        p.setFrequencyLimitPerDay(frequencyLimitPerDay);
        prefRepo.save(p);
    }

    /**
     * Canonical Europe/Istanbul night-window contract used by T1.1.6
     * scenarios: cross-day window 22:00 → 07:00 in Istanbul tz. Combined
     * with a fixed clock at 20:30 UTC (= 23:30 Istanbul) this lands the
     * evaluation squarely inside the window and exercises the timezone
     * + cross-day arithmetic.
     */
    private static Map<String, Object> quietHoursIstanbulNight() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("start", "22:00");
        q.put("end", "07:00");
        q.put("tz", "Europe/Istanbul");
        return q;
    }

    private NotificationIntent makeIntent(String topic, NotificationIntent.Severity severity) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("it-" + Integer.toHexString(topic.hashCode()));
        i.setOrgId("default");
        i.setTopicKey(topic);
        i.setSeverity(severity);
        return i;
    }
}
