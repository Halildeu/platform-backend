package com.example.ethics.security;

import com.example.ethics.config.EthicsProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faz 35 ES-203/D — how a colleague is named across the browser boundary.
 *
 * <p>Until now a participant was named by their Keycloak subject: the manager's browser
 * received subjects in a list and sent one back to assign. That contradicts a decision
 * the platform had already taken — {@code kcSubject} was deliberately removed from the
 * public {@code UserResponse} surface so the KC UUID stays server-to-server. In a
 * whistleblowing product the cost is sharper than elsewhere: a subject that reaches a
 * browser is a correlation key, stable across every case and every other product.
 *
 * <p>So the browser gets a handle instead, and the handle is scoped to one case. An
 * org-stable handle would work just as well for a picker and would also give anyone
 * holding two of them a way to say "the same person is on both of these cases". Scoping
 * to the case removes that: the same colleague on two cases has two unrelated handles.
 *
 * <p><b>There is no reverse table.</b> The handle is a one-way function, and resolving
 * one means recomputing it for each subject the case could legitimately be assigned to
 * and matching. That costs one HMAC per candidate — nothing at realistic team sizes —
 * and buys the absence of a mapping anyone could read, dump or restore from a backup.
 * A table that does not exist cannot leak.
 *
 * <p>The key is versioned in the handle itself, so rotating it invalidates outstanding
 * handles rather than silently changing what they mean. Old handles stop resolving,
 * which is the correct failure: a stale handle should be refused, not quietly matched.
 */
@Component
public class ParticipantHandles {

    /** Bound into the MAC so a handle minted here can never be replayed elsewhere. */
    private static final String PURPOSE = "faz35.ethics.participant";

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;

    public ParticipantHandles(EthicsProperties properties) {
        String configured = properties.participantHandleKey();
        // Fail closed at startup rather than at the first assignment. A service that
        // boots without this key would answer with handles derived from a default,
        // which is the same as no scoping at all.
        if (configured == null || configured.length() < 32) {
            throw new IllegalStateException(
                    "ethics.participant-handle-key is required and must be at least 32 characters");
        }
        this.key = configured.getBytes(StandardCharsets.UTF_8);
    }

    /** The handle for this subject on this case. Stable while the key is. */
    public String mint(UUID orgId, UUID caseId, String subject) {
        if (orgId == null || caseId == null || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("participant handle requires org, case and subject");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            // Length-prefixed. With two fixed-length UUIDs and a constant purpose no
            // shift between fields is expressible today, so this buys nothing yet — it
            // is here for the field after next, when one of them is variable-length and
            // the ambiguity would arrive silently.
            for (String part : new String[] {orgId.toString(), caseId.toString(), subject, PURPOSE}) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                mac.update((byte) ':');
                mac.update(bytes);
            }
            return VERSION + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        }
    }

    /**
     * Whether {@code handle} names {@code subject} on this case.
     *
     * <p>Compared with {@link MessageDigest#isEqual} rather than {@code equals}: the
     * caller supplies the handle, and a comparison that returns early on the first
     * differing byte tells them how much of a guess was right.
     */
    public boolean matches(String handle, UUID orgId, UUID caseId, String subject) {
        if (handle == null || handle.isBlank()) return false;
        return MessageDigest.isEqual(
                mint(orgId, caseId, subject).getBytes(StandardCharsets.UTF_8),
                handle.getBytes(StandardCharsets.UTF_8));
    }

    /** A handle the service did not mint — wrong key version, wrong case, or invented. */
    public static ResponseStatusException unknown() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PARTICIPANT_HANDLE_UNKNOWN");
    }
}
