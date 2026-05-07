package com.serban.notify.preference;

import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.SubscriberPreference;
import com.serban.notify.repository.SubscriberContactRepository;
import com.serban.notify.repository.SubscriberPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubscriberPreferenceService unit test (Codex 019dfaaa PR5).
 */
class SubscriberPreferenceServiceTest {

    private SubscriberContactRepository contactRepo;
    private SubscriberPreferenceRepository prefRepo;
    private SubscriberPreferenceService service;

    @BeforeEach
    void setUp() {
        contactRepo = mock(SubscriberContactRepository.class);
        prefRepo = mock(SubscriberPreferenceRepository.class);
        service = new SubscriberPreferenceService(contactRepo, prefRepo);
    }

    @Test
    void noPreferenceDefaultsToAllow() {
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
            anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
            anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("no_preference_set");
    }

    @Test
    void preferenceEnabledTrueAllowed() {
        SubscriberPreference pref = preference(true, false);
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void preferenceEnabledFalseDeniedForInfoSeverity() {
        SubscriberPreference pref = preference(false, true);
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    @Test
    void criticalSeverityBypassesPreferenceDeny() {
        SubscriberPreference pref = preference(false, true);
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.critical);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("critical_bypass");
    }

    @Test
    void criticalSeverityWithoutBypassFlagStillDenied() {
        SubscriberPreference pref = preference(false, false);  // bypassForCritical=false
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.critical);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    @Test
    void channelWildcardFallback() {
        SubscriberPreference pref = preference(false, false);
        // Exact match returns empty
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.empty());
        // Channel-wildcard returns the preference
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
            "default", "1204", "auth.password-reset")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void topicWildcardFallback() {
        SubscriberPreference pref = preference(false, false);
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
            "default", "1204", "auth.password-reset")).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
            "default", "1204", "email")).thenReturn(Optional.of(pref));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed()).isFalse();
    }

    /**
     * Faz 23.5 PR2 absorb (Codex iter P1 regression guard).
     * When the only preference row is a both-null wildcard
     * ({@code topic_key=null AND channel=null} → "mute all topics &
     * channels"), the dispatch path must honor it. Without the 4th
     * fallback this would silently allow delivery
     * (no_preference_set → ALLOW).
     */
    @Test
    void bothNullWildcardFallbackHonoredOnDispatch() {
        SubscriberPreference muteAll = new SubscriberPreference();
        muteAll.setOrgId("default");
        muteAll.setSubscriberId("1204");
        muteAll.setTopicKey(null);
        muteAll.setChannel(null);
        muteAll.setEnabled(false);
        muteAll.setBypassForCritical(false);

        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
            "default", "1204", "auth.password-reset", "email")).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
            "default", "1204", "auth.password-reset")).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
            "default", "1204", "email")).thenReturn(Optional.empty());
        when(prefRepo.findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannelIsNull(
            "default", "1204")).thenReturn(Optional.of(muteAll));

        NotificationIntent intent = makeIntent("auth.password-reset", NotificationIntent.Severity.info);
        var decision = service.evaluate(intent, "email", "1204");

        assertThat(decision.allowed())
            .as("both-null mute-all wildcard must deny dispatch")
            .isFalse();
        assertThat(decision.reason()).isEqualTo("preference_disabled");
    }

    private NotificationIntent makeIntent(String topic, NotificationIntent.Severity severity) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("test");
        i.setOrgId("default");
        i.setTopicKey(topic);
        i.setSeverity(severity);
        return i;
    }

    private SubscriberPreference preference(boolean enabled, boolean bypassForCritical) {
        SubscriberPreference p = new SubscriberPreference();
        p.setSubscriberId("1204");
        p.setOrgId("default");
        p.setTopicKey("auth.password-reset");
        p.setChannel("email");
        p.setEnabled(enabled);
        p.setBypassForCritical(bypassForCritical);
        return p;
    }
}
