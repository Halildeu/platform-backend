package com.example.ethics.audit;

import com.example.ethics.model.WormAuditEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** Offline/read-only verifier used by acceptance and restore drills. */
@Component
public class EthicsAuditIntegrityVerifier {
    private static final HexFormat HEX = HexFormat.of();

    public Verification verify(List<WormAuditEntry> rows) {
        String expectedPrevious = null;
        long priorSequence = 0L;
        for (WormAuditEntry row : rows) {
            if (row.getSeq() <= priorSequence) {
                return new Verification(false, "SEQUENCE_ORDER_INVALID", row.getSeq());
            }
            if (!java.util.Objects.equals(expectedPrevious, row.getPrevHash())) {
                return new Verification(false, "PREVIOUS_HASH_MISMATCH", row.getSeq());
            }
            String expectedHash = recompute(row);
            if (!expectedHash.equals(row.getEntryHash())) {
                return new Verification(false, "ENTRY_HASH_MISMATCH", row.getSeq());
            }
            expectedPrevious = row.getEntryHash();
            priorSequence = row.getSeq();
        }
        return new Verification(true, "PASS", rows.size());
    }

    private static String recompute(WormAuditEntry row) {
        String previous = row.getPrevHash() == null || row.getPrevHash().isBlank()
                ? EthicsAuditChain.GENESIS
                : row.getPrevHash();
        String canonical = canonicalPayload(row);
        String material = EthicsAuditChain.DOMAIN_PREFIX
                + "\nprev=" + previous
                + "\npayload=" + canonical;
        try {
            return HEX.formatHex(MessageDigest.getInstance(EthicsAuditChain.HASH_ALGORITHM)
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String canonicalPayload(WormAuditEntry row) {
        // This exact field order mirrors EthicsAuditChain.canonicalPayload.
        com.fasterxml.jackson.databind.node.ObjectNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        root.put("aggregate_id", row.getAggregateId().toString().toLowerCase());
        root.put("event_timestamp", row.getEventTimestamp().toString());
        root.put("event_type", row.getEventType());
        root.put("org_id", row.getOrgId().toString().toLowerCase());
        root.put("payload", row.getPayload());
        root.put("source_outbox_id", row.getSourceOutboxId().toString().toLowerCase());
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("Ethics audit verification payload serialization failed", error);
        }
    }

    public record Verification(boolean valid, String reason, long position) {
    }
}
