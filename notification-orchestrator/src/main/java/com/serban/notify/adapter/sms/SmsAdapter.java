package com.serban.notify.adapter.sms;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SMS channel adapter — Faz 23.3 multi-provider failover facade
 * (Codex `019e3f82` AGREE).
 *
 * <p><b>Tek</b> {@code channelKey="sms"} {@link ChannelAdapter} — provider
 * çoğulluğu ({@link SmsProvider} listesi) bu facade'de orchestrate edilir.
 * {@code ChannelAdapterRegistry} her channelKey için tek adapter indekslediği
 * için JetSMS/NetGSM doğrudan {@code ChannelAdapter} olamaz.
 *
 * <p><b>Faz 23.3 PR-1 (behavior-neutral)</b>: source default
 * {@code primary-provider=netgsm}, {@code secondary-provider=} (boş). Bu
 * konfigürasyonla SmsAdapter yalnızca NetGSM'i çağırır — davranış eski
 * {@code NetGsmSmsAdapter} ile semantik olarak aynıdır. JetSMS primary
 * runtime flip'i GitOps ConfigMap ile gelir (PR-4 overlay).
 *
 * <p>Failover (PR-1 temel; PR-2 full matrix): primary provider
 * {@code FAILED}/{@code RETRY} döner ve {@link SmsFailureClass#failoverEligible()}
 * {@code true} ise secondary provider denenir. Kalıcı hata
 * (invalid phone, IYS opt-out, vb.) → secondary denenmez. PR-2'de
 * {@code UNSUPPORTED_CHARSET} charset-capability pre-route + {@code PROVIDER_CONFIG}
 * alert eklenir.
 *
 * <p>Validation (provider-agnostic, facade-level): E.164 telefon formatı +
 * boş mesaj guard. Encoding/segment provider-specific
 * ({@link SmsProvider#send} içinde).
 */
@Component
public class SmsAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmsAdapter.class);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private final Map<String, SmsProvider> providersByKey;
    private final String primaryKey;
    private final String secondaryKey;

    public SmsAdapter(
        List<SmsProvider> providers,
        @Value("${notify.adapters.sms.primary-provider:netgsm}") String primaryKey,
        @Value("${notify.adapters.sms.secondary-provider:}") String secondaryKey
    ) {
        Map<String, SmsProvider> index = new HashMap<>();
        for (SmsProvider p : providers) {
            index.put(p.providerKey(), p);
        }
        this.providersByKey = Map.copyOf(index);
        this.primaryKey = primaryKey == null ? "" : primaryKey.trim();
        this.secondaryKey = secondaryKey == null ? "" : secondaryKey.trim();
        log.info("SmsAdapter activated: primary={} secondary={} registered={}",
            this.primaryKey,
            this.secondaryKey.isEmpty() ? "(none)" : this.secondaryKey,
            providersByKey.keySet());
    }

    @Override
    public String channelKey() {
        return "sms";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        // 1. Validation (provider-agnostic).
        String phone = target.targetRef();
        if (phone == null || !E164.matcher(phone).matches()) {
            log.warn("sms invalid phone format: hash={}", target.recipientHash());
            return DeliveryAttemptResult.failed("phone not E.164", null);
        }
        String text = (message.bodyText() != null && !message.bodyText().isBlank())
            ? message.bodyText()
            : message.subject();
        if (text == null || text.isBlank()) {
            return DeliveryAttemptResult.failed("empty message (no body_text or subject)", null);
        }

        // 2. Primary provider.
        SmsProvider primary = providersByKey.get(primaryKey);
        if (primary == null) {
            log.error("sms primary provider '{}' not registered (registered={})",
                primaryKey, providersByKey.keySet());
            return DeliveryAttemptResult.failed(
                "sms primary provider not registered: " + primaryKey, null);
        }

        SmsSendResult primaryResult = safeSend(primary, phone, text);
        log.info("sms primary={} result status={} class={} hash={}",
            primaryKey, primaryResult.status(), primaryResult.failureClass(),
            target.recipientHash());

        // 3. Failover decision (PR-1 temel — PR-2 full matrix).
        if (primaryResult.status() != SmsSendResult.SmsSendStatus.ACCEPTED
            && primaryResult.failureClass().failoverEligible()
            && !secondaryKey.isEmpty()) {

            SmsProvider secondary = providersByKey.get(secondaryKey);
            if (secondary != null) {
                log.info("sms failover: primary={} class={} → secondary={}",
                    primaryKey, primaryResult.failureClass(), secondaryKey);
                SmsSendResult secondaryResult = safeSend(secondary, phone, text);
                log.info("sms secondary={} result status={} class={} hash={}",
                    secondaryKey, secondaryResult.status(), secondaryResult.failureClass(),
                    target.recipientHash());
                return toAttemptResult(secondaryResult);
            }
            log.warn("sms failover skipped: secondary '{}' not registered", secondaryKey);
        }

        return toAttemptResult(primaryResult);
    }

    /** Provider.send'i sarmalar — beklenmedik exception RETRY'a çevrilir. */
    private SmsSendResult safeSend(SmsProvider provider, String phone, String text) {
        try {
            return provider.send(phone, text);
        } catch (RuntimeException e) {
            log.warn("sms provider {} threw {} — RETRY",
                provider.providerKey(), e.getClass().getSimpleName());
            return SmsSendResult.retry(provider.providerKey(),
                SmsFailureClass.UNKNOWN_TRANSIENT, "exc:" + e.getClass().getSimpleName());
        }
    }

    /** {@link SmsSendResult} → {@link ChannelAdapter.DeliveryAttemptResult}. */
    private static DeliveryAttemptResult toAttemptResult(SmsSendResult r) {
        return switch (r.status()) {
            case ACCEPTED -> DeliveryAttemptResult.accepted(
                r.providerMsgId(), r.actualProviderKey());
            case FAILED -> DeliveryAttemptResult.failed(
                "sms " + r.actualProviderKey() + " " + r.failureClass()
                    + (r.providerCode() != null ? " (" + r.providerCode() + ")" : ""),
                null, r.actualProviderKey());
            case RETRY -> DeliveryAttemptResult.retry(
                "sms " + r.actualProviderKey() + " " + r.failureClass()
                    + (r.providerCode() != null ? " (" + r.providerCode() + ")" : ""),
                null, r.actualProviderKey());
        };
    }
}
