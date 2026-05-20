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
 * + the three NULL-fallback variants) and the {@code bypass_for_critical}
 * column mapping are verified end-to-end against real Postgres NULL
 * semantics. The {@code jsonb quiet_hours} column and the frequency
 * limit branch are left to the unit test (clock injection there) and to
 * future T1.1.6 / T1.1.7 acceptance work; this IT keeps scope to the
 * must-have #8 contract.
 *
 * <p>Eight scenarios:
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

    @BeforeEach
    void cleanPrefRows() {
        prefRepo.deleteAll();
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
        SubscriberPreference p = new SubscriberPreference();
        p.setOrgId("default");
        p.setSubscriberId(subscriberId);
        p.setTopicKey(topic);
        p.setChannel(channel);
        p.setEnabled(enabled);
        p.setBypassForCritical(bypassForCritical);
        prefRepo.save(p);
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
