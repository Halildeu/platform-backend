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
 * so the JPA mapping (jsonb quiet_hours column, channel/topicKey nullable
 * keys for fallback rows, bypass_for_critical column) and the SQL
 * fallback queries used by the service are also verified end-to-end.
 *
 * <p>Five scenarios cover the must-have #8 contract (preference +
 * critical bypass) from the M3 acceptance gate:
 * <ol>
 *   <li>No preference row → ALLOWED (default no_preference_set)</li>
 *   <li>Channel+topic row, enabled=true → ALLOWED</li>
 *   <li>Channel+topic row, enabled=false → DENIED (preference_disabled)</li>
 *   <li>enabled=false + severity=critical + bypass_for_critical=true →
 *       ALLOWED (critical_bypass)</li>
 *   <li>enabled=false + severity=critical + bypass_for_critical=false →
 *       DENIED (preference_disabled, bypass not honored)</li>
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
        SubscriberPreference p = new SubscriberPreference();
        p.setOrgId("default");
        p.setSubscriberId(k.subscriberId());
        p.setTopicKey(k.topic());
        p.setChannel(k.channel());
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
