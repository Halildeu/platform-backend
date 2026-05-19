package com.serban.notify.adapter.sms;

/**
 * SMS DLR poll sonucu — Faz 23.3 multi-provider (Codex `019e3f82` iter-2).
 *
 * <p>{@link SmsProvider#pollDelivery} POLL-mode provider'lar (JetSMS) için
 * her sorgulanan message ID'nin teslimat durumunu döner. PR-3
 * (JetSmsDlrPollingWorker) bu sonucu generic {@code DlrIngestService}
 * core'una map eder.
 *
 * <p>{@link #terminal} — {@code true} ise {@link #deliveryStatus} kalıcı
 * (DELIVERED / FAILED); {@code false} ise provider hâlâ pending bildiriyor
 * (örn. JetSMS MessageState 2/6/7/8 — operatöre gönderildi rapor yok) ve
 * worker bir sonraki cycle'da yeniden poll eder.
 */
public record SmsDlrPollResult(
    String rawProviderMsgId,
    boolean terminal,
    DeliveryStatus deliveryStatus,
    String providerStateCode
) {

    /** Generic DLR ingest terminal status — provider-agnostic. */
    public enum DeliveryStatus {
        /** Teslim edildi. */
        DELIVERED,
        /** Kalıcı başarısız (yanlış numara, timeout, vb.). */
        FAILED,
        /** Henüz terminal değil — yeniden poll. */
        PENDING
    }

    public SmsDlrPollResult {
        if (rawProviderMsgId == null || rawProviderMsgId.isBlank()) {
            throw new IllegalArgumentException("rawProviderMsgId null/blank olamaz");
        }
        if (deliveryStatus == null) {
            throw new IllegalArgumentException("deliveryStatus null olamaz");
        }
    }

    /** Terminal DELIVERED. */
    public static SmsDlrPollResult delivered(String rawId, String stateCode) {
        return new SmsDlrPollResult(rawId, true, DeliveryStatus.DELIVERED, stateCode);
    }

    /** Terminal FAILED. */
    public static SmsDlrPollResult failed(String rawId, String stateCode) {
        return new SmsDlrPollResult(rawId, true, DeliveryStatus.FAILED, stateCode);
    }

    /** Henüz terminal değil — yeniden poll edilecek. */
    public static SmsDlrPollResult pending(String rawId, String stateCode) {
        return new SmsDlrPollResult(rawId, false, DeliveryStatus.PENDING, stateCode);
    }
}
