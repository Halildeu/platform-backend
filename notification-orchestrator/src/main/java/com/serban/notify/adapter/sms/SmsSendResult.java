package com.serban.notify.adapter.sms;

/**
 * SMS provider send sonucu — Faz 23.3 multi-provider (Codex `019e3f82`
 * iter-2 absorb).
 *
 * <p>{@link SmsProvider#send} bu record'u döner. {@link SmsAdapter} bunu
 * {@code ChannelAdapter.DeliveryAttemptResult}'a map eder, failover kararını
 * {@link #failureClass} üzerinden verir.
 *
 * <p>{@link #actualProviderKey} — sonucu üreten gerçek provider
 * ({@code "jetsms"} | {@code "netgsm"}). Failover sonrası secondary kabul
 * ederse bu alan secondary provider'ı taşır; {@code DeliveryDispatchService}
 * {@code notification_delivery.provider} kolonuna bu değeri yazar (plan-time
 * placeholder değil — Codex absorb #2).
 *
 * <p>{@link #providerMsgId} — DLR correlator. {@code ACCEPTED} sonuçta
 * non-blank zorunlu (yoksa caller {@code retry} dönmeli). Format:
 * {@code "<providerKey>-<rawId>"} (örn. {@code "jetsms-756464245"},
 * {@code "netgsm-4039201"}).
 */
public record SmsSendResult(
    SmsSendStatus status,
    SmsFailureClass failureClass,
    String actualProviderKey,
    String providerMsgId,
    String providerCode
) {

    /** Send sonuç durumu. DELIVERED yok — SMS her zaman async DLR ile terminal olur. */
    public enum SmsSendStatus {
        /** Provider mesajı carrier'a kabul etti; terminal durum DLR ile gelir. */
        ACCEPTED,
        /** Kalıcı hata — retry yok. */
        FAILED,
        /** Transient hata — RetryWorker tekrar dener. */
        RETRY
    }

    public SmsSendResult {
        if (status == null) {
            throw new IllegalArgumentException("SmsSendResult.status null olamaz");
        }
        if (failureClass == null) {
            throw new IllegalArgumentException("SmsSendResult.failureClass null olamaz");
        }
        if (status == SmsSendStatus.ACCEPTED
            && (providerMsgId == null || providerMsgId.isBlank())) {
            throw new IllegalArgumentException(
                "ACCEPTED sonuç DLR correlation için non-blank providerMsgId gerektirir; "
                    + "provider correlator dönmediyse retry() kullan");
        }
    }

    /**
     * Carrier kabul etti — DLR bekleniyor.
     *
     * @param actualProviderKey gerçek dispatch eden provider
     * @param providerMsgId DLR correlator ({@code "<providerKey>-<rawId>"}), non-blank
     */
    public static SmsSendResult accepted(String actualProviderKey, String providerMsgId) {
        return new SmsSendResult(
            SmsSendStatus.ACCEPTED, SmsFailureClass.NONE,
            actualProviderKey, providerMsgId, null);
    }

    /**
     * Kalıcı hata — secondary failover {@code failureClass.failoverEligible()}
     * değerine göre {@link SmsAdapter} tarafından karar verilir.
     */
    public static SmsSendResult failed(String actualProviderKey,
                                       SmsFailureClass failureClass,
                                       String providerCode) {
        return new SmsSendResult(
            SmsSendStatus.FAILED, failureClass, actualProviderKey, null, providerCode);
    }

    /** Transient hata — RetryWorker tekrar dener. */
    public static SmsSendResult retry(String actualProviderKey,
                                      SmsFailureClass failureClass,
                                      String providerCode) {
        return new SmsSendResult(
            SmsSendStatus.RETRY, failureClass, actualProviderKey, null, providerCode);
    }
}
