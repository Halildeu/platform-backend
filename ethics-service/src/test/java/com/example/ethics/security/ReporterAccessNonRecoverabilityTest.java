package com.example.ethics.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ethics.api.PublicEthicsController;
import com.example.ethics.model.ReporterAccessGrant;
import com.example.ethics.service.SecretHasher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ES-010 (platform-k8s-gitops#2657) — the reporter's access secret is non-recoverable by
 * design, and that has to stay true by construction rather than by good intentions.
 *
 * <p>Anonymity here is not a feature toggle. The moment the product can hand a lost secret
 * back to someone, it must first be able to decide WHO is asking — and any mechanism that
 * can answer that question (an e-mail address, a phone number, a security answer) is a link
 * from a case to a person. That link is the thing a whistleblower is trusting us not to
 * hold. Recovery and anonymity cannot both exist; the product chose anonymity, and the
 * honest cost is that a reporter who loses the secret loses their side of the conversation.
 * The case survives for staff, and the UI says so plainly before the secret leaves the
 * screen.
 *
 * <p>These tests are the enforcement. An ADR records a decision; only a failing build stops
 * the decision from being undone by a well-meaning "users keep losing their receipts, let's
 * add a reset e-mail" change six months from now. The three ways that erosion would show up
 * in code are: a recovery endpoint, an identity column on the grant, or storing the secret
 * in a form that can be read back.
 *
 * <p>Live corroboration (2026-08-02, TEST cell): every guessed recovery path returned 404
 * while the real {@code POST /mailbox/sessions} returned 400 — the 404s are genuine absence,
 * not a blanket catch-all. But guessing paths only disproves the paths guessed; the mapping
 * scan below covers the whole surface.
 */
class ReporterAccessNonRecoverabilityTest {

    /** Words that describe handing access back to someone who no longer holds the secret. */
    private static final Pattern RECOVERY_SEMANTICS =
            Pattern.compile("recover|recovery|reset|forgot|resend|restore|retrieve|remind|unlock",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Words that name a human being. Matched against camelCase-split TOKENS, not as
     * substrings: a bare `ip` alternative matches the "ip" inside `receiptId` and fails a
     * field that identifies a case, not a person. The first run of this test did exactly
     * that — the pattern was right about the shape and wrong about the value.
     *
     * <p>`channel` and `receiptId` are deliberately absent: one holds the intake host, the
     * other is the case-access handle. Neither points at a person.
     */
    private static final java.util.Set<String> IDENTITY_TOKENS = java.util.Set.of(
            "email", "mail", "phone", "msisdn", "gsm", "name", "surname", "username",
            "user", "person", "contact", "address", "ip", "question", "answer", "hint",
            "tckn", "national", "birth", "subject", "identity", "identifier");

    private static List<String> tokensOf(String fieldName) {
        var tokens = new ArrayList<String>();
        for (String token : fieldName.split("(?<!^)(?=[A-Z])|_")) {
            if (!token.isBlank()) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private static List<String> publicMappingPaths() {
        var paths = new ArrayList<String>();
        var base = PublicEthicsController.class.getAnnotation(RequestMapping.class);
        if (base != null) {
            for (String value : base.value()) {
                paths.add(value);
            }
        }
        for (Method method : PublicEthicsController.class.getDeclaredMethods()) {
            for (var get : method.getAnnotationsByType(GetMapping.class)) {
                paths.addAll(List.of(get.value()));
            }
            for (var post : method.getAnnotationsByType(PostMapping.class)) {
                paths.addAll(List.of(post.value()));
            }
            for (var put : method.getAnnotationsByType(PutMapping.class)) {
                paths.addAll(List.of(put.value()));
            }
            for (var delete : method.getAnnotationsByType(DeleteMapping.class)) {
                paths.addAll(List.of(delete.value()));
            }
            for (var mapping : method.getAnnotationsByType(RequestMapping.class)) {
                paths.addAll(List.of(mapping.value()));
            }
            // The method name matters as much as the path: `POST /mailbox/help` routed to
            // `resendAccessSecret` would pass a path-only check and still be the thing this
            // test exists to prevent.
            paths.add(method.getName());
        }
        return paths;
    }

    @Test
    @DisplayName("no public endpoint offers to recover, reset or resend the access secret")
    void publicSurfaceCarriesNoRecoveryAffordance() {
        var offenders = publicMappingPaths().stream()
                .filter(path -> RECOVERY_SEMANTICS.matcher(path).find())
                .toList();
        assertTrue(offenders.isEmpty(),
                "the reporter surface must offer no way back in without the secret, but found: "
                        + offenders
                        + ". Recovery requires identifying the asker, and identifying the asker "
                        + "is exactly the link ES-010 refuses to hold.");
    }

    @Test
    @DisplayName("the access grant holds nothing that could identify the reporter")
    void grantStoresNoIdentityShapedField() {
        var offenders = new ArrayList<String>();
        for (Field field : ReporterAccessGrant.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            String name = field.getName();
            if (tokensOf(name).stream().anyMatch(IDENTITY_TOKENS::contains)) {
                offenders.add(name);
            }
        }
        assertTrue(offenders.isEmpty(),
                "ReporterAccessGrant must carry no identity-shaped field, but found: " + offenders
                        + ". A column added 'only for notifications' is still a case-to-person link.");
    }

    @Test
    @DisplayName("the grant stores a hash, never a readable secret")
    void grantStoresAHashNotTheSecret() {
        var fieldNames = new ArrayList<String>();
        for (Field field : ReporterAccessGrant.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                fieldNames.add(field.getName().toLowerCase(Locale.ROOT));
            }
        }
        assertTrue(fieldNames.contains("secrethash"), "the grant must keep a hashed secret");
        assertFalse(fieldNames.contains("secret") || fieldNames.contains("accesssecret"),
                "a readable secret column would make recovery possible — and therefore mandatory "
                        + "to hand over on a lawful order. Only the hash may be stored.");

        // Not just "a field named hash": prove the stored form actually differs from the input
        // and is salted, so two identical secrets do not produce the same row. A name is a
        // label; this is the property.
        var hasher = new SecretHasher();
        String secret = hasher.newSecret();
        String first = hasher.hash(secret, 1000);
        String second = hasher.hash(secret, 1000);
        assertNotEquals(secret, first, "the stored value must not be the secret itself");
        assertNotEquals(first, second, "equal secrets must not produce equal stored values (salt)");
        assertTrue(hasher.verify(secret, first), "the hash must still verify the real secret");
        assertFalse(hasher.verify(secret + "x", first), "a different secret must not verify");
    }

    @Test
    @DisplayName("the hasher can answer 'is this the secret?' and never 'what was the secret?'")
    void theHasherApiIsVerifyOnly() {
        var hasher = new SecretHasher();
        String secret = hasher.newSecret();
        String stored = hasher.hash(secret, 1000);
        assertFalse(stored.contains(secret),
                "the stored verifier must not contain the secret in any readable form");

        // The guarantee is the SHAPE OF THE API, not one call's output: as long as the only
        // way to use a stored value is to compare a candidate against it, no code path — and
        // no lawful order — can turn the database back into a reporter's secret. A method
        // that reversed it would make recovery possible, and possible means compellable.
        var reversers = new ArrayList<String>();
        for (Method method : SecretHasher.class.getDeclaredMethods()) {
            if (method.isSynthetic() || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (Pattern.compile("decrypt|unhash|reverse|decode|plain|reveal|recover",
                    Pattern.CASE_INSENSITIVE).matcher(method.getName()).find()) {
                reversers.add(method.getName());
            }
        }
        assertTrue(reversers.isEmpty(),
                "SecretHasher must stay verify-only, but found reversing method(s): " + reversers);
        assertEquals(boolean.class, verifyReturnType(),
                "verify() must answer with a boolean and nothing more — returning the secret, "
                        + "or anything derived from it, would leak what the hash exists to hide");
    }

    private static Class<?> verifyReturnType() {
        for (Method method : SecretHasher.class.getDeclaredMethods()) {
            if (method.getName().equals("verify")) {
                return method.getReturnType();
            }
        }
        throw new AssertionError("SecretHasher.verify is missing — the verify-only contract is gone");
    }
}
