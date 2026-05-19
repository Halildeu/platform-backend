package com.serban.notify.adapter.sms;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMS channel adapter facade test (Faz 23.3 multi-provider — PR-1).
 *
 * <p>{@link SmsAdapter} provider-agnostic facade davranışı: channel-level
 * validation (E.164, empty message), provider seçimi, failover orchestration,
 * {@link SmsSendResult} → {@code DeliveryAttemptResult} map. Provider HTTP
 * logic {@code NetGsmProviderTest}'te (fake provider burada).
 *
 * <p>PR-1 behavior-neutral: source-default {@code primary=netgsm secondary=}
 * (boş) — failover path fake provider'larla doğrulanır, ama prod source
 * default'ta secondary yok → primary-only davranış eski NetGsmSmsAdapter ile
 * semantik aynı.
 */
class SmsAdapterTest {

    /** Test-only SmsProvider — providerKey + send davranışı configurable. */
    private static final class FakeProvider implements SmsProvider {
        private final String key;
        private final boolean unicode;
        private final Function<String, SmsSendResult> sendFn;
        int sendCallCount = 0;

        FakeProvider(String key, boolean unicode, Function<String, SmsSendResult> sendFn) {
            this.key = key;
            this.unicode = unicode;
            this.sendFn = sendFn;
        }

        @Override public String providerKey() { return key; }
        @Override public SmsDlrMode dlrMode() { return SmsDlrMode.PUSH; }
        @Override public boolean supportsUnicode() { return unicode; }
        @Override public SmsSendResult send(String e164Phone, String text) {
            sendCallCount++;
            return sendFn.apply(text);
        }
    }

    private static DeliveryTarget target(String phone) {
        return new DeliveryTarget("sms", "subscriber", "1", "hash-mock", phone, "sms");
    }

    private static RenderedMessage msg(String subject, String bodyText) {
        return new RenderedMessage(subject, null, bodyText, "tr-TR");
    }

    // ─── Channel metadata ────────────────────────────────────────────────

    @Test
    void channelKeyIsSms() {
        SmsAdapter adapter = new SmsAdapter(
            List.of(new FakeProvider("netgsm", true, t -> SmsSendResult.accepted("netgsm", "netgsm-1"))),
            "netgsm", "");
        assertThat(adapter.channelKey()).isEqualTo("sms");
    }

    // ─── Validation (facade-level) ───────────────────────────────────────

    @Test
    void invalidPhoneFormatFailsBeforeProvider() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("905321234567"),  // missing leading "+"
            msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("E.164");
        // PII discipline: raw phone never in failureReason
        assertThat(r.failureReason()).doesNotContain("905321234567");
        assertThat(netgsm.sendCallCount).isZero();  // provider never called
    }

    @Test
    void emptyMessageFailsBeforeProvider() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321234567"), msg(null, null));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("empty message");
        assertThat(netgsm.sendCallCount).isZero();
    }

    @Test
    void fallbackToSubjectWhenBodyNull() {
        FakeProvider netgsm = new FakeProvider("netgsm", true, text -> {
            assertThat(text).isEqualTo("Subject only");  // subject used as text
            return SmsSendResult.accepted("netgsm", "netgsm-sub");
        });
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321234567"), msg("Subject only", null));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(netgsm.sendCallCount).isEqualTo(1);
    }

    // ─── Provider routing + result mapping ───────────────────────────────

    @Test
    void primaryProviderAcceptedMapsWithActualProviderKey() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-abc"));
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "Hello"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.providerMessageId()).isEqualTo("netgsm-abc");
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
    }

    @Test
    void primaryNotRegisteredFails() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "jetsms", "");  // primary not registered

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("primary provider not registered");
    }

    @Test
    void providerFailedMapsToFailedWithProviderKey() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.failed("netgsm", SmsFailureClass.INVALID_PHONE, "50"));
        SmsAdapter adapter = new SmsAdapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(r.failureReason()).contains("INVALID_PHONE");
    }

    // ─── Failover (PR-1 temel) ───────────────────────────────────────────

    @Test
    void failoverEligibleRetriesSecondary() {
        // primary RETRY (TIMEOUT — failover-eligible) → secondary denenir
        FakeProvider primary = new FakeProvider("jetsms", false,
            t -> SmsSendResult.retry("jetsms", SmsFailureClass.TIMEOUT, "io"));
        FakeProvider secondary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-fb"));
        SmsAdapter adapter = new SmsAdapter(List.of(primary, secondary), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");  // secondary dispatched
        assertThat(r.providerMessageId()).isEqualTo("netgsm-fb");
        assertThat(primary.sendCallCount).isEqualTo(1);
        assertThat(secondary.sendCallCount).isEqualTo(1);
    }

    @Test
    void failoverNotEligibleDoesNotRetrySecondary() {
        // primary FAILED (INVALID_PHONE — failover-NOT-eligible) → secondary
        // denenmez (secondary'de de aynı sonuç olur — gereksiz çağrı)
        FakeProvider primary = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.INVALID_PHONE, "x"));
        FakeProvider secondary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-fb"));
        SmsAdapter adapter = new SmsAdapter(List.of(primary, secondary), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");  // primary stays
        assertThat(secondary.sendCallCount).isZero();  // secondary never called
    }

    @Test
    void noSecondaryConfiguredPrimaryOnly() {
        // PR-1 source-default: secondary boş → primary-only (behavior-neutral)
        FakeProvider primary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.retry("netgsm", SmsFailureClass.HTTP_5XX, "503"));
        SmsAdapter adapter = new SmsAdapter(List.of(primary), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(primary.sendCallCount).isEqualTo(1);
    }

    @Test
    void providerThrowsHandledAsRetry() {
        FakeProvider primary = new FakeProvider("netgsm", true, t -> {
            throw new RuntimeException("unexpected");
        });
        SmsAdapter adapter = new SmsAdapter(List.of(primary), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = adapter.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
    }
}
