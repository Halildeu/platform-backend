package com.example.ethics.catalog;

/**
 * ES-403 — the things a customer can be sold.
 *
 * <p><strong>A capability is not a permission.</strong> It says what the organisation bought,
 * never who inside it may do the thing: that stays with the authorization model, where
 * `case_handler` and `subject_reveal_approved` live. Conflating the two would make a billing
 * record into an access decision, and then a lapsed subscription would read as "this person
 * is not allowed" — which in a whistleblowing product is the wrong sentence entirely.
 *
 * <p>The set is closed and enumerated here rather than free-form strings, for the reason
 * today made expensive elsewhere: a value that exists in one place and is spelled differently
 * in another drifts silently.
 */
public enum EthicsCapability {

    /** Reporters may attach files to a report. */
    EVIDENCE_ATTACHMENTS,

    /** The organisation may run more than one public intake host. */
    MULTI_HOST_INTAKE,

    /** Staff may request the identity of a reported person through the break-glass path. */
    SUBJECT_REVEAL,

    /** The organisation may export its own data. */
    DATA_EXPORT,

    /** Deadline signals reach the organisation through its notification channel. */
    SLA_NOTIFICATIONS
}
