package com.example.ethics.evidence;

public interface EvidenceProcessor {
    ProcessedEvidence process(byte[] original, String declaredMediaType);

    record ProcessedEvidence(
            byte[] derivative,
            String inputMediaType,
            String outputMediaType,
            String scannerDigest,
            String sanitizerDigest,
            String parserDigest,
            String rulesVersion,
            String transformationProfile,
            /**
             * ES-104J: bounded codes for active content the CDR pass removed
             * (JAVASCRIPT, OPEN_ACTION, ...). Empty for lanes that have nothing to
             * disarm. Never silent: a non-empty list is written to the audit ledger
             * by the pipeline worker, because "we cleaned it" without a record is
             * indistinguishable from "it was never there".
             */
            java.util.List<String> disarmed) {

        public ProcessedEvidence {
            disarmed = java.util.List.copyOf(disarmed);
        }
    }

    final class ProcessingException extends RuntimeException {
        public enum Outcome { INTEGRITY, POLICY, MALICIOUS, UNAVAILABLE, SANITIZE_FAILED }
        private final Outcome outcome;
        private final String code;

        public ProcessingException(Outcome outcome, String code) {
            super(code);
            this.outcome = outcome;
            this.code = code;
        }
        public ProcessingException(Outcome outcome, String code, Throwable cause) {
            super(code, cause);
            this.outcome = outcome;
            this.code = code;
        }
        public Outcome outcome() { return outcome; }
        public String code() { return code; }
    }
}
