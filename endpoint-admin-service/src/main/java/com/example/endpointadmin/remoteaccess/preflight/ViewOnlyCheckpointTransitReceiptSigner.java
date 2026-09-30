package com.example.endpointadmin.remoteaccess.preflight;

/** Both receipt types use the role-pinned checkpoint-signer Transit key. */
public final class ViewOnlyCheckpointTransitReceiptSigner
        implements ViewOnlyLeaseReceiptSigner, ViewOnlyCheckpointReceiptSigner {
    private final ViewOnlyReceiptPayloadFactory payloads;
    private final ViewOnlyDsseSigner dsse;

    public ViewOnlyCheckpointTransitReceiptSigner(ViewOnlyReceiptPayloadFactory payloads,
                                                  ViewOnlyDsseSigner dsse) {
        this.payloads = payloads;
        this.dsse = dsse;
    }

    @Override
    public byte[] sign(ViewOnlyLeaseSigningInput input) {
        return dsse.sign(ViewOnlyReceiptPayloadFactory.LEASE_PAYLOAD_TYPE, payloads.lease(input));
    }

    @Override
    public byte[] sign(ViewOnlyCheckpointSigningInput input) {
        return dsse.sign(ViewOnlyReceiptPayloadFactory.CHECKPOINT_PAYLOAD_TYPE, payloads.checkpoint(input));
    }
}
