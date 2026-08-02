package com.example.ethics.identity;

import com.example.ethics.api.EthicsDtos.ReportMode;
import com.example.ethics.api.EthicsDtos.ReporterIdentityPayload;
import com.example.ethics.model.ReporterIdentity;
import com.example.ethics.repository.ReporterIdentityRepository;
import com.example.ethics.repository.RevealRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ES-212 (#3370) — stores the reporter behind a CONFIDENTIAL or NAMED report, and decides
 * who may see it again.
 *
 * <h2>Two modes, two different promises</h2>
 *
 * <p><strong>NAMED</strong> means the reporter chose to be known to the people handling the
 * case. Showing the identity to a handler is the whole point; withholding it would break the
 * promise the form made.
 *
 * <p><strong>CONFIDENTIAL</strong> means the opposite: the organisation knows who reported,
 * but the handlers do not. The identity is sealed and opens only after a reveal request has
 * been <em>executed</em> — four eyes, a named legal basis, and a WORM audit entry
 * (ES-303, V2). This class does not re-implement any of that; it asks whether such a reveal
 * exists and refuses otherwise. Putting the decision here rather than in a controller means
 * every future read path inherits it, including ones nobody has written yet.
 *
 * <h2>Relationship to ADR-0050</h2>
 *
 * <p>ADR-0050 requires that for an ANONYMOUS report no case-to-person link exist at all,
 * which is why reporter access there is non-recoverable. Confidential mode deliberately has
 * that link — sealed, audited, but present. The two are not in tension; they are different
 * products of a different promise, and the schema keeps them apart (V20
 * {@code fk_reporter_identity_report_mode}) so a change in one cannot bleed into the other.
 */
@Service
public class ReporterIdentityService {

    private final ReporterIdentityRepository identities;
    private final RevealRequestRepository reveals;
    private final ReporterIdentityCrypto crypto;
    private final ObjectMapper objectMapper;

    public ReporterIdentityService(ReporterIdentityRepository identities,
                                   RevealRequestRepository reveals,
                                   ReporterIdentityCrypto crypto,
                                   ObjectMapper objectMapper) {
        this.identities = identities;
        this.reveals = reveals;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    /** Whether identity-bearing modes can be accepted at all right now (key material present). */
    public boolean isOperational() {
        return crypto.isOperational();
    }

    /**
     * Seals the identity against the case. Callers must have already established that the
     * mode collects an identity and that the organisation enabled it; this method's own
     * refusals are about the material, not the policy.
     */
    @Transactional
    public void store(UUID caseId, ReportMode mode, ReporterIdentityPayload payload, Instant now) {
        if (mode == ReportMode.ANONYMOUS) {
            // Unreachable through intake, which checks first. Kept as a loud failure rather
            // than a silent return so that a future caller who gets the order wrong finds
            // out here instead of writing a row the schema would reject with a foreign-key
            // error nobody can read.
            throw new IllegalArgumentException("anonymous reports carry no identity");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("reporter identity could not be serialised");
        }
        ReporterIdentityCrypto.Sealed sealed = crypto.seal(caseId, json);
        identities.save(new ReporterIdentity(
                caseId, mode.name(), sealed.keyId(), sealed.nonce(), sealed.ciphertext(), now));
    }

    /**
     * What a case handler may see of the reporter.
     *
     * <p>Returns a state rather than an empty optional so the interface can distinguish
     * "this report has no identity" from "it has one and you may not see it". That
     * distinction is not a leak — the report's mode is already visible to the handler — and
     * hiding it would leave the UI unable to offer the reveal path at all.
     */
    @Transactional(readOnly = true)
    public IdentityAccess readForHandler(UUID caseId) {
        Optional<ReporterIdentity> stored = identities.findById(caseId);
        if (stored.isEmpty()) {
            return IdentityAccess.none();
        }
        ReporterIdentity identity = stored.get();
        boolean named = ReportMode.NAMED.name().equals(identity.getMode());
        if (!named && !revealExecuted(caseId)) {
            return IdentityAccess.sealed();
        }
        String json = crypto.open(caseId, identity.getKeyId(), identity.getNonce(), identity.getCiphertext());
        try {
            return IdentityAccess.visible(objectMapper.readValue(json, ReporterIdentityPayload.class));
        } catch (Exception e) {
            throw new IllegalStateException("reporter identity could not be read");
        }
    }

    private boolean revealExecuted(UUID caseId) {
        return reveals.findAllByCaseIdOrderByRequestedAtDesc(caseId).stream()
                .anyMatch(r -> "EXECUTED".equals(r.getStatus()));
    }

    /** NONE: no identity was collected. SEALED: collected, reveal required. VISIBLE: readable. */
    public enum State { NONE, SEALED, VISIBLE }

    public record IdentityAccess(State state, ReporterIdentityPayload payload) {
        static IdentityAccess none() { return new IdentityAccess(State.NONE, null); }
        static IdentityAccess sealed() { return new IdentityAccess(State.SEALED, null); }
        static IdentityAccess visible(ReporterIdentityPayload payload) {
            return new IdentityAccess(State.VISIBLE, payload);
        }
    }
}
