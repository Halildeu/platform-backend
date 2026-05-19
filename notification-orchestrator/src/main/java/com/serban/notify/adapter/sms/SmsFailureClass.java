package com.serban.notify.adapter.sms;

/**
 * SMS provider failure taxonomy — Faz 23.3 multi-provider (Codex `019e3f82`
 * iter-2 absorb).
 *
 * <p>{@link SmsAdapter} failover orchestration bu sınıflandırmaya bakar:
 * failover-eligible sınıf → secondary provider denenir; failover-NOT-eligible
 * (kalıcı recipient/content hatası) → direkt fail (secondary'de de aynı
 * sonuç olacağı için gereksiz çağrı yapılmaz).
 *
 * <p>{@link #PROVIDER_CONFIG} ve {@link #UNSUPPORTED_CHARSET} özel ele alınır
 * (bkz. {@link #failoverEligible()} javadoc).
 */
public enum SmsFailureClass {

    // ───────────────────────── failover-eligible (transient) ──────────────
    /** Connect/response timeout — secondary denenebilir. */
    TIMEOUT(true),
    /** Provider HTTP 5xx — secondary denenebilir. */
    HTTP_5XX(true),
    /** Provider system fault (örn. JetSMS Status=-15, NetGSM kod parse fail). */
    PROVIDER_SYSTEM(true),
    /** Provider rate-limit / throttle. */
    RATE_LIMIT(true),
    /** Yetersiz kontör / kredi (örn. NetGSM kod 60). */
    QUOTA_OR_CREDIT(true),
    /** Provider 2xx ama DLR correlator (msg id) dönmedi — ACCEPTED limbo riski. */
    NO_CORRELATOR(true),
    /** Bilinmeyen ama transient varsayılan (schema drift safety). */
    UNKNOWN_TRANSIENT(true),

    // ──────────────────── failover-NOT-eligible (kalıcı) ───────────────────
    /** Geçersiz telefon formatı — secondary'de de fail eder. */
    INVALID_PHONE(false),
    /** Geçersiz mesaj metni (provider reject). */
    INVALID_TEXT(false),
    /** Mesaj segment limitini aşıyor. */
    MESSAGE_TOO_LONG(false),
    /** IYS opt-out — alıcı ticari mesaj almayı reddetmiş (KVKK). */
    IYS_OPT_OUT(false),
    /** Policy block (kara liste vb.). */
    POLICY_BLOCK(false),
    /** Boş mesaj — content hatası. */
    EMPTY_MESSAGE(false),

    // ─────────────────────────── özel sınıflar ────────────────────────────
    /**
     * Provider config/credential hatası (login fail, geçersiz originator,
     * eksik credential). Codex `019e3f82` absorb: default sessiz failover
     * <b>YAPILMAZ</b> — high-severity alert üretilir. Operator-controlled
     * {@code notify.adapters.sms.failover-on-provider-config-error=true}
     * flag ile kritik topic'ler için açılabilir (default kapalı).
     */
    PROVIDER_CONFIG(false),
    /**
     * Mesaj metni provider'ın charset'inde encode edilemiyor (örn. JetSMS
     * ISO-8859-9 dışı emoji/CJK karakter). Codex `019e3f82` absorb:
     * silent transliteration YAPILMAZ — secondary provider Unicode
     * destekliyorsa charset-capability pre-route, hiçbiri desteklemiyorsa
     * validation fail. {@link SmsAdapter} bu sınıfı capability route ile
     * ele alır (failoverEligible() false döner — pre-route ayrı path).
     */
    UNSUPPORTED_CHARSET(false),

    /** Başarı — failure yok. */
    NONE(false);

    private final boolean failoverEligible;

    SmsFailureClass(boolean failoverEligible) {
        this.failoverEligible = failoverEligible;
    }

    /**
     * Failover orchestration bu provider sonucundan sonra secondary
     * provider'ı denemeli mi?
     *
     * <p>{@code true} — transient hata, secondary mantıklı.
     * <p>{@code false} — kalıcı hata YA DA özel sınıf ({@link #PROVIDER_CONFIG}
     * alert + opt-in flag; {@link #UNSUPPORTED_CHARSET} capability pre-route).
     * Özel sınıflar {@link SmsAdapter} içinde ayrıca ele alınır.
     */
    public boolean failoverEligible() {
        return failoverEligible;
    }
}
