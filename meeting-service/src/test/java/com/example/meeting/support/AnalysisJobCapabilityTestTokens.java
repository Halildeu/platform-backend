package com.example.meeting.support;

import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/** Test-only producer for the transcript-service job capability contract. */
public final class AnalysisJobCapabilityTestTokens {

    private static final byte[] SECRET =
            "meeting-analysis-capability-test-key-2026".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public static final String ENCODED_SECRET = Base64.getEncoder().encodeToString(SECRET);

    private AnalysisJobCapabilityTestTokens() {
    }

    public static String issue(
            UUID tenantId,
            UUID meetingId,
            UUID analysisRunId,
            MeetingAnalysisResultIngestRequest request) {
        Instant issuedAt = Instant.now().minusSeconds(1);
        return issue(
                UUID.randomUUID(),
                tenantId,
                meetingId,
                UUID.fromString(request.transcriptSessionId()),
                request.finalizationVersion(),
                request.finalizedAt(),
                request.transcriptSha256(),
                analysisRunId,
                request.analysisSpecVersion(),
                issuedAt,
                issuedAt.plusSeconds(300));
    }

    public static String issue(
            UUID capabilityId,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            Instant finalizedAt,
            String transcriptSha256,
            UUID analysisRunId,
            String analysisSpecVersion,
            Instant issuedAt,
            Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("transcript-service")
                    .audience("meeting-service")
                    .subject("meeting-ai")
                    .claim("client_id", "meeting-ai")
                    .claim("perm", "meeting:analysis-result:write")
                    .jwtID(capabilityId.toString())
                    .claim("tenant_id", tenantId.toString())
                    .claim("meeting_id", meetingId.toString())
                    .claim("session_id", sessionId.toString())
                    .claim("finalization_version", finalizationVersion)
                    .claim("finalized_at", finalizedAt.toString())
                    .claim("transcript_sha256", transcriptSha256)
                    .claim("analysis_run_id", analysisRunId.toString())
                    .claim("analysis_spec_version", analysisSpecVersion)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("Unable to create test capability", ex);
        }
    }
}
