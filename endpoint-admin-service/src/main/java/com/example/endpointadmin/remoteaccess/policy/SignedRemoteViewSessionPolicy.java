package com.example.endpointadmin.remoteaccess.policy;

/** Complete signed envelope plus the two digests used by session/permit/audit lineage. */
public record SignedRemoteViewSessionPolicy(
        String canonicalEnvelopeJson,
        String envelopeDigest,
        String payloadDigest,
        String keyId,
        String policyDigest) {
}
