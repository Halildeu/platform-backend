package com.example.ethics.evidence;

import com.example.ethics.model.EvidenceAttachment;
import java.io.InputStream;

public interface EvidenceObjectStore {
    ObjectReceipt putQuarantine(
            EvidenceAttachment attachment, InputStream input, long contentLength);
    ObjectReceipt sealOriginal(EvidenceAttachment attachment);
    byte[] readQuarantine(EvidenceAttachment attachment);
    ObjectReceipt putDerivative(
            EvidenceAttachment attachment, byte[] content, String sha256, String mediaType);
    byte[] readDerivative(EvidenceAttachment attachment);
    void deleteQuarantine(EvidenceAttachment attachment);

    record ObjectReceipt(String versionId, long size, String sha256) {}

    final class StoreException extends RuntimeException {
        private final String code;
        public StoreException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
        public StoreException(String code) {
            super(code);
            this.code = code;
        }
        public String code() { return code; }
    }
}
