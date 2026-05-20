package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;

import java.util.Map;

/**
 * Channel adapter interface — Faz 23.1 PR3 (Codex 019df9ae Q1 + Q2 absorb).
 *
 * <p>Channel implementations (PR3 scope):
 * <ul>
 *   <li>SMTP — recipient-addressed (her recipient delivery row)</li>
 *   <li>Slack incoming webhook — target-addressed (provider/routing target başına)</li>
 *   <li>Webhook egress — target-addressed</li>
 * </ul>
 *
 * <p>PR3 scope (Codex Q1 REVISE):
 * <ul>
 *   <li>Adapter implementation tam, runtime call **internal-only**
 *       (DeliveryDispatchService direct invoke; no scheduled worker)</li>
 *   <li>Submit endpoint auto-dispatch YOK</li>
 *   <li>Worker PR4'te (OutboxPoller + RetryWorker)</li>
 * </ul>
 */
public interface ChannelAdapter {

    /**
     * Channel identifier (matches notification_delivery.channel column).
     * Examples: "email", "slack", "webhook".
     */
    String channelKey();

    /**
     * Send rendered message to delivery target via this channel.
     *
     * <p>Codex Q1: This method is invoked by {@code DeliveryDispatchService}
     * (internal direct-invoke); not by submit endpoint or scheduled worker
     * in PR3 scope.
     *
     * @param target delivery target (recipient or routing target ref)
     * @param message rendered subject + body parts
     * @return delivery result with provider message ID + status
     */
    DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message);

    /**
     * Delivery attempt result (immutable record).
     *
     * <p>Status semantics:
     * <ul>
     *   <li>{@code DELIVERED} — terminal success. Synchronous channels
     *       (email SMTP, slack, webhook, in-app) where send-success ≡ delivery
     *       (no provider DLR loop)</li>
     *   <li>{@code ACCEPTED} — Faz 23.4 PR-F: provider accepted message for
     *       carrier delivery; awaits async DLR webhook to terminalize to
     *       DELIVERED/FAILED. Used by SMS adapters (NetGSM, İletimerkezi).
     *       providerMessageId required (non-blank) — DLR correlator;
     *       missing/empty providerMessageId means caller should return
     *       {@code retry()} instead (no DLR correlation possible).</li>
     *   <li>{@code FAILED} — provider 4xx (permanent client error, no retry)</li>
     *   <li>{@code RETRY} — provider 5xx or timeout (transient, PR4 worker retries)</li>
     *   <li>{@code BOUNCED} — email-specific (hard bounce, no retry)</li>
     * </ul>
     *
     * <p><b>Faz 23.3.2 multipart metadata</b> (Codex thread {@code 019e4514}
     * PR-A1.1 absorb): {@link #providerMetadata} generic immutable Map ile
     * channel-specific outcome metadata taşınır. SMS için
     * {@code segment_count} + {@code encoding} key'leri eklenir; diğer
     * channel'lar ileride farklı key'ler (örn. email DKIM signature
     * algorithm, slack workspace_id) ekleyebilir.
     *
     * <p>Metadata audit event details'e merge edilir; {@link com.serban.notify.redaction.PiiRedactor}
     * whitelist'inde yer alan key'ler audit_event payload'a yazılır,
     * diğerleri filter'lanır (whitelist son savunma katmanı).
     */
    record DeliveryAttemptResult(
        Status status,
        String providerMessageId,
        String failureReason,
        Integer providerResponseCode,
        String actualProviderKey,
        Map<String, Object> providerMetadata
    ) {
        public enum Status {
            DELIVERED, ACCEPTED, FAILED, RETRY, BOUNCED
        }

        // Faz 23.3 multi-provider (Codex `019e3f82` absorb #2): actualProviderKey
        // SMS channel gerçek dispatch eden provider'ı ("jetsms"|"netgsm") taşır;
        // DeliveryDispatchService non-null ise notification_delivery.provider
        // kolonuna yazar (failover sonrası secondary kabul ettiyse gerçek provider
        // persist). SMS dışı kanallar (email/slack/webhook/in-app) null bırakır —
        // bu kanallarda provider plan-time DeliveryTarget'tan gelir (behavior-neutral).

        public DeliveryAttemptResult {
            providerMetadata = (providerMetadata == null || providerMetadata.isEmpty())
                ? Map.of()
                : Map.copyOf(providerMetadata);
        }

        public static DeliveryAttemptResult delivered(String providerMsgId) {
            return new DeliveryAttemptResult(
                Status.DELIVERED, providerMsgId, null, null, null, Map.of());
        }

        /** Faz 23.3 SMS-channel variant — gerçek provider key taşır. */
        public static DeliveryAttemptResult delivered(String providerMsgId,
                                                      String actualProviderKey) {
            return new DeliveryAttemptResult(
                Status.DELIVERED, providerMsgId, null, null, actualProviderKey, Map.of());
        }

        /**
         * Faz 23.4 PR-F: provider accepted message for carrier delivery,
         * awaits DLR. {@code providerMsgId} must be non-blank — DLR
         * correlator. Caller MUST use {@link #retry} instead if provider
         * did not return a message id (no DLR correlation possible).
         *
         * @throws IllegalArgumentException if providerMsgId is null or blank
         */
        public static DeliveryAttemptResult accepted(String providerMsgId) {
            return accepted(providerMsgId, null);
        }

        /** Faz 23.3 SMS-channel variant — gerçek provider key taşır. */
        public static DeliveryAttemptResult accepted(String providerMsgId,
                                                     String actualProviderKey) {
            return accepted(providerMsgId, actualProviderKey, Map.of());
        }

        /**
         * Faz 23.3.2 PR-A1.1 variant — gerçek provider key + provider metadata
         * (örn. SMS {@code segment_count}, {@code encoding}).
         */
        public static DeliveryAttemptResult accepted(String providerMsgId,
                                                     String actualProviderKey,
                                                     Map<String, Object> providerMetadata) {
            if (providerMsgId == null || providerMsgId.isBlank()) {
                throw new IllegalArgumentException(
                    "ACCEPTED requires non-blank providerMessageId for DLR correlation; "
                        + "use retry() if provider did not return a correlator");
            }
            return new DeliveryAttemptResult(
                Status.ACCEPTED, providerMsgId, null, null, actualProviderKey,
                providerMetadata == null ? Map.of() : providerMetadata);
        }

        public static DeliveryAttemptResult failed(String reason, Integer code) {
            return new DeliveryAttemptResult(
                Status.FAILED, null, reason, code, null, Map.of());
        }

        /** Faz 23.3 SMS-channel variant — gerçek provider key taşır. */
        public static DeliveryAttemptResult failed(String reason, Integer code,
                                                   String actualProviderKey) {
            return new DeliveryAttemptResult(
                Status.FAILED, null, reason, code, actualProviderKey, Map.of());
        }

        public static DeliveryAttemptResult retry(String reason, Integer code) {
            return new DeliveryAttemptResult(
                Status.RETRY, null, reason, code, null, Map.of());
        }

        /** Faz 23.3 SMS-channel variant — gerçek provider key taşır. */
        public static DeliveryAttemptResult retry(String reason, Integer code,
                                                  String actualProviderKey) {
            return new DeliveryAttemptResult(
                Status.RETRY, null, reason, code, actualProviderKey, Map.of());
        }

        public static DeliveryAttemptResult bounced(String reason) {
            return new DeliveryAttemptResult(
                Status.BOUNCED, null, reason, null, null, Map.of());
        }
    }
}
