package com.example.ethics.evidence;

import com.example.ethics.model.EvidenceAttachment;
import java.io.InputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ethics.evidence.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class DisabledEvidenceObjectStore implements EvidenceObjectStore {
    private static StoreException unavailable() {
        return new StoreException("EVIDENCE_STORAGE_UNAVAILABLE");
    }
    @Override public ObjectReceipt putQuarantine(EvidenceAttachment a, InputStream i, long l) { throw unavailable(); }
    @Override public ObjectReceipt sealOriginal(EvidenceAttachment a) { throw unavailable(); }
    @Override public byte[] readQuarantine(EvidenceAttachment a) { throw unavailable(); }
    @Override public ObjectReceipt putDerivative(EvidenceAttachment a, byte[] c, String s, String m) { throw unavailable(); }
    @Override public byte[] readDerivative(EvidenceAttachment a) { throw unavailable(); }
    @Override public void deleteQuarantine(EvidenceAttachment a) { throw unavailable(); }
}
