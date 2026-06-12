package com.example.endpointadmin.remoteaccess.bridge.wire;

import com.example.endpointadmin.remoteaccess.RemoteOperation;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.proto.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.proto.Capability;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.ConsentPrompt;
import com.example.endpointadmin.remoteaccess.bridge.proto.ConsentResult;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.ErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.proto.OperationRequest;
import com.example.endpointadmin.remoteaccess.bridge.proto.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.proto.WireOperation;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2a (Codex 019eb9fb) — the proto↔domain boundary is fail-closed: ids re-validated, enums
 * default-deny (UNSPECIFIED + unknown wire numbers + non-pilot values), proto3 implicit defaults explicit,
 * and the signed {@link OperationPermit#canonicalPayload()} survives the wire byte-for-byte.
 */
class RemoteBridgeProtoAdapterTest {

    private static final String ALG = RemoteBridgePermitSigner.PERMIT_ALG;
    private static final String HASH = CanonicalCommand.of("hostname").hash();

    // ------------------------------------------------------------------
    // Enum default-deny
    // ------------------------------------------------------------------

    @Test
    void capabilityUnspecifiedAndUnknownWireNumbersAreRejected() {
        assertFalse(RemoteBridgeProtoAdapter.decodeCapability(Capability.CAPABILITY_UNSPECIFIED).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decodeCapability(Capability.UNRECOGNIZED).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decodeCapability(Capability.forNumber(99)
                == null ? Capability.UNRECOGNIZED : Capability.forNumber(99)).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decodeCapability(null).isOk());
    }

    @Test
    void pilotCapabilitiesDecodeAndNonPilotEncodeThrows() {
        assertEquals(RemoteSessionCapability.VIEW_ONLY,
                RemoteBridgeProtoAdapter.decodeCapability(Capability.VIEW_ONLY).orElseThrow());
        assertEquals(RemoteSessionCapability.CONSTRAINED_PTY,
                RemoteBridgeProtoAdapter.decodeCapability(Capability.CONSTRAINED_PTY).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBridgeProtoAdapter.encodeCapability(RemoteSessionCapability.FULL_RDP));
        assertThrows(IllegalArgumentException.class, () -> RemoteBridgeProtoAdapter.encodeCapability(null));
    }

    @Test
    void anUnknownCapabilityWireNumberInsideARepeatedFieldRejectsTheWholeMessage() {
        // a peer speaking a NEWER contract (enum value 7) must be refused, not silently truncated
        SessionRequest tampered = SessionRequest.newBuilder()
                .setSessionId("sess-1").setDeviceId("dev-1").setOperatorSubject("operator@x")
                .addRequestedCapabilitiesValue(Capability.VIEW_ONLY_VALUE)
                .addRequestedCapabilitiesValue(7)
                .build();
        assertFalse(RemoteBridgeProtoAdapter.decode(tampered).isOk());
    }

    @Test
    void operationUnspecifiedUnknownAndNonPilotAreRejected() {
        assertFalse(RemoteBridgeProtoAdapter.decodeOperation(WireOperation.WIRE_OPERATION_UNSPECIFIED).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decodeOperation(WireOperation.UNRECOGNIZED).isOk());
        assertEquals(RemoteOperation.SCREEN_VIEW,
                RemoteBridgeProtoAdapter.decodeOperation(WireOperation.SCREEN_VIEW).orElseThrow());
        assertEquals(RemoteOperation.PTY_COMMAND,
                RemoteBridgeProtoAdapter.decodeOperation(WireOperation.PTY_COMMAND).orElseThrow());
        // KEYBOARD_INPUT is a real domain operation but NOT a pilot wire operation — encode refuses
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBridgeProtoAdapter.encodeOperation(RemoteOperation.KEYBOARD_INPUT));
    }

    // ------------------------------------------------------------------
    // Message round-trips + proto3 default traps
    // ------------------------------------------------------------------

    @Test
    void agentHelloRoundTripsAndEmptyAdvertisedCapabilitiesAreAllowed() {
        RemoteBridgeMessages.AgentHello hello = new RemoteBridgeMessages.AgentHello(
                "0.2.3", "dev-1", "ab12cd", "ZXZpZGVuY2U=", "rb-v1", Set.of(RemoteSessionCapability.VIEW_ONLY));
        AgentHello proto = RemoteBridgeProtoAdapter.encode(hello);
        assertEquals(hello, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());

        AgentHello noCaps = proto.toBuilder().clearAdvertisedCapabilities().build();
        assertEquals(Set.of(), RemoteBridgeProtoAdapter.decode(noCaps).orElseThrow().advertisedCapabilities());
    }

    @Test
    void agentHelloRejectsBadDeviceIdAndMissingAttestation() {
        AgentHello base = RemoteBridgeProtoAdapter.encode(new RemoteBridgeMessages.AgentHello(
                "0.2.3", "dev-1", "ab12cd", "ZXZpZGVuY2U=", "rb-v1", Set.of()));
        assertFalse(RemoteBridgeProtoAdapter.decode(base.toBuilder().setDeviceId("dev 1").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(base.toBuilder().setDeviceId("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(base.toBuilder().setAttestationEvidenceB64("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(base.toBuilder().setAgentVersion("a\nb").build()).isOk());
    }

    @Test
    void sessionRequestRoundTripsAndEmptyReasonNormalisesToNull() {
        RemoteBridgeMessages.SessionRequest request = new RemoteBridgeMessages.SessionRequest(
                "sess-1", "dev-1", "operator@x", null,
                Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY));
        SessionRequest proto = RemoteBridgeProtoAdapter.encode(request);
        assertEquals("", proto.getReason()); // null encodes as the proto3 default
        RemoteBridgeMessages.SessionRequest decoded = RemoteBridgeProtoAdapter.decode(proto).orElseThrow();
        assertEquals(request, decoded);
        assertNull(decoded.reason()); // and decodes back to null, not ""
    }

    @Test
    void sessionRequestRejectsEmptyCapabilitiesBadIdsAndUnsafeReason() {
        SessionRequest valid = RemoteBridgeProtoAdapter.encode(new RemoteBridgeMessages.SessionRequest(
                "sess-1", "dev-1", "operator@x", "yardım", Set.of(RemoteSessionCapability.VIEW_ONLY)));
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().clearRequestedCapabilities().build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setSessionId("s\nid").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setOperatorSubject((char) 0 + "x").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setDeviceId("d".repeat(257)).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setReason("a" + (char) 7 + "b").build()).isOk());
    }

    @Test
    void consentPromptRoundTripsAndRejectsEmptyCapabilitiesOrNonPositiveExpiry() {
        RemoteBridgeMessages.ConsentPrompt prompt = new RemoteBridgeMessages.ConsentPrompt(
                "sess-1", "Halil K", "disk doluluk", Set.of(RemoteSessionCapability.VIEW_ONLY), 2000L);
        ConsentPrompt proto = RemoteBridgeProtoAdapter.encode(prompt);
        assertEquals(prompt, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().clearCapabilities().build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setExpiryEpochMillis(0).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setOperatorDisplayName("").build()).isOk());
    }

    @Test
    void consentResultRoundTripsAndAcceptsWindowsSessionNamesThatAreNotWireIds() {
        RemoteBridgeMessages.ConsentResult result = new RemoteBridgeMessages.ConsentResult(
                "sess-1", true, "RDP-Tcp#0", 1000L, 2000L); // '#' is NOT in the wire-id allowlist — by design
        ConsentResult proto = RemoteBridgeProtoAdapter.encode(result);
        assertEquals(result, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setWindowsInteractiveSession("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setWindowsInteractiveSession("a\nb").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setGrantedAtEpochMillis(-1).build()).isOk());
    }

    @Test
    void operationRequestRoundTripsAndEmptyCommandLineNormalisesToNull() {
        RemoteBridgeMessages.OperationRequest view = new RemoteBridgeMessages.OperationRequest(
                "sess-1", "op-1", RemoteOperation.SCREEN_VIEW, null);
        OperationRequest proto = RemoteBridgeProtoAdapter.encode(view);
        assertEquals("", proto.getCommandLine());
        assertEquals(view, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());

        RemoteBridgeMessages.OperationRequest pty = new RemoteBridgeMessages.OperationRequest(
                "sess-1", "op-2", RemoteOperation.PTY_COMMAND, "hostname");
        assertEquals(pty, RemoteBridgeProtoAdapter.decode(RemoteBridgeProtoAdapter.encode(pty)).orElseThrow());
    }

    @Test
    void operationRequestRejectsBadIdsAndNonPilotOperationNumbers() {
        OperationRequest valid = RemoteBridgeProtoAdapter.encode(new RemoteBridgeMessages.OperationRequest(
                "sess-1", "op-1", RemoteOperation.SCREEN_VIEW, null));
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setSessionId("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setOperationId("op 1").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(
                valid.toBuilder().setOperationValue(0).build()).isOk());  // UNSPECIFIED
        assertFalse(RemoteBridgeProtoAdapter.decode(
                valid.toBuilder().setOperationValue(11).build()).isOk()); // unknown / future number
    }

    @Test
    void killRoundTripsAndRejectsUnsafeReasonOrMissingTimestamp() {
        RemoteBridgeMessages.Kill kill = new RemoteBridgeMessages.Kill("sess-1", "duress-detected", 1000L);
        Kill proto = RemoteBridgeProtoAdapter.encode(kill);
        assertEquals(kill, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setKillReason("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setKillReason("x\rj").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setIssuedAtEpochMillis(0).build()).isOk());
    }

    @Test
    void auditEventRoundTripsWithEmptyOrSha256HexContentHashOnly() {
        RemoteBridgeMessages.AuditEvent event = new RemoteBridgeMessages.AuditEvent(
                "sess-1", "ALLOW_DECISION:op-1", HASH, 1000L);
        AuditEvent proto = RemoteBridgeProtoAdapter.encode(event);
        assertEquals(event, RemoteBridgeProtoAdapter.decode(proto).orElseThrow());
        assertTrue(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setContentHash("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setContentHash("xyz").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(
                proto.toBuilder().setContentHash(HASH.toUpperCase()).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(proto.toBuilder().setEventType("e" + (char) 27 + "v").build()).isOk());
    }

    // ------------------------------------------------------------------
    // OperationPermit — the signed artifact
    // ------------------------------------------------------------------

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        return g.generateKeyPair();
    }

    private static OperationPermit signedPermit(KeyPair kp) {
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", ALG);
        OperationPermit unsigned = new OperationPermit(ALG, "kid-1", 1, "policy-1", "sess-1:op-1", "sess-1",
                "op-1", "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY, HASH, 1000L, 1300L, 7L, null);
        return signer.sign(unsigned).orElseThrow();
    }

    @Test
    void aSignedPermitSurvivesTheWireByteForByteAndStillVerifies() throws Exception {
        KeyPair kp = ec();
        OperationPermit signed = signedPermit(kp);

        // encode → REAL wire bytes → parse → decode (the full path an agent will run)
        byte[] wire = RemoteBridgeProtoAdapter.encode(signed).toByteArray();
        OperationPermit decoded = RemoteBridgeProtoAdapter.decode(
                com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit.parseFrom(wire)).orElseThrow();

        assertEquals(signed, decoded);
        assertArrayEquals(signed.canonicalPayload(), decoded.canonicalPayload()); // the T-1 acceptance boundary
        assertTrue(new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1").verify(decoded, 1100L));
    }

    @Test
    void aPermitFieldChangedOnTheWireFailsVerification() throws Exception {
        KeyPair kp = ec();
        OperationPermit signed = signedPermit(kp);
        com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit tampered =
                RemoteBridgeProtoAdapter.encode(signed).toBuilder().setOperationId("op-2").build();
        OperationPermit decoded = RemoteBridgeProtoAdapter.decode(tampered).orElseThrow(); // shape-valid…
        assertFalse(new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1").verify(decoded, 1100L)); // …but forged
    }

    @Test
    void permitDecodeRejectsWrongVersionAlgAndIds() throws Exception {
        com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit valid =
                RemoteBridgeProtoAdapter.encode(signedPermit(ec()));
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setPermitVersion(2).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setAlg("SHA256withRSA").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setAlg("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setKid("k id").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setDeviceId("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setOperatorSubject("a\nb").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(
                valid.toBuilder().setDecisionId("d".repeat(514)).build()).isOk());
        assertTrue(RemoteBridgeProtoAdapter.decode(
                valid.toBuilder().setDecisionId("s".repeat(256) + ":" + "o".repeat(256)).build()).isOk());
    }

    @Test
    void permitDecodeRejectsCapabilityCommandHashMismatchBothWays() throws Exception {
        com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit pty =
                RemoteBridgeProtoAdapter.encode(signedPermit(ec()));
        // CONSTRAINED_PTY must carry a sha-256 hash
        assertFalse(RemoteBridgeProtoAdapter.decode(pty.toBuilder().setCommandHash("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(pty.toBuilder().setCommandHash("beef").build()).isOk());
        // VIEW_ONLY must NOT carry one
        com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit viewWithHash = pty.toBuilder()
                .setCapability(Capability.VIEW_ONLY).build();
        assertFalse(RemoteBridgeProtoAdapter.decode(viewWithHash).isOk());
        assertTrue(RemoteBridgeProtoAdapter.decode(
                viewWithHash.toBuilder().setCommandHash("").build()).isOk());
    }

    @Test
    void permitDecodeRejectsNonPilotCapabilityWindowSeqAndSignatureDefects() throws Exception {
        com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit valid =
                RemoteBridgeProtoAdapter.encode(signedPermit(ec()));
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setCapabilityValue(0).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setCapabilityValue(9).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setIssuedAtEpochMillis(0).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(
                valid.toBuilder().setExpiresAtEpochMillis(valid.getIssuedAtEpochMillis()).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setSeq(-1).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setSignatureB64("").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(valid.toBuilder().setSignatureB64("%%%").build()).isOk());
    }

    // ------------------------------------------------------------------
    // Envelope channel/payload compatibility
    // ------------------------------------------------------------------

    private static Envelope.Builder control() {
        return Envelope.newBuilder().setChannelType(ChannelType.CONTROL)
                .setKill(Kill.newBuilder().setSessionId("sess-1").setKillReason("r").setIssuedAtEpochMillis(1));
    }

    @Test
    void envelopeRequiresAnExplicitChannelAndAPayload() {
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(
                control().setChannelType(ChannelType.CHANNEL_TYPE_UNSPECIFIED).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(
                control().setChannelTypeValue(9).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(
                Envelope.newBuilder().setChannelType(ChannelType.CONTROL).build()).isOk()); // no payload
        assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(control().build()).isOk());
    }

    @Test
    void dataFramesOnlyOnDataAndControlPayloadsOnlyOnControl() {
        Envelope dataOnControl = Envelope.newBuilder().setChannelType(ChannelType.CONTROL)
                .setDataFrame(DataFrame.newBuilder().setStreamId("st-1").setFrameSeq(1)
                        .setContentType("image/png")).build();
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(dataOnControl).isOk());
        assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(
                dataOnControl.toBuilder().setChannelType(ChannelType.DATA).build()).isOk());

        Envelope sessionRequestOnData = Envelope.newBuilder().setChannelType(ChannelType.DATA)
                .setSessionRequest(SessionRequest.newBuilder().setSessionId("sess-1")).build();
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(sessionRequestOnData).isOk());
        // a KILL parked on DATA would sit behind the very backpressure it must bypass — illegal by contract
        Envelope killOnData = control().setChannelType(ChannelType.DATA).build();
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(killOnData).isOk());
    }

    @Test
    void heartbeatAndErrorTravelOnBothChannels() {
        for (ChannelType channel : new ChannelType[] {ChannelType.CONTROL, ChannelType.DATA}) {
            assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(Envelope.newBuilder().setChannelType(channel)
                    .setHeartbeat(Heartbeat.newBuilder().setHeartbeatIntervalMillis(5000)).build()).isOk());
            assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(Envelope.newBuilder().setChannelType(channel)
                    .setError(ErrorFrame.newBuilder().setCode("backoff").setRetryable(true)).build()).isOk());
        }
    }

    @Test
    void envelopeHeadersAreOptionalButValidateWhenPresent() {
        assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(
                control().setSessionId("sess-1").setDeviceId("dev-1").setStreamId("st-1").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(control().setSessionId("s id").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(control().setDeviceId("d\nv").build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(control().setStreamId("x".repeat(257)).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(control().setFrameSeq(-1).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(control().setSentAtEpochMillis(-5).build()).isOk());
    }

    // ------------------------------------------------------------------
    // Codex post-impl P1/P2 — payload-content validation + null guards
    // ------------------------------------------------------------------

    @Test
    void dataFrameContentValidatesInsideTheEnvelope() {
        DataFrame valid = DataFrame.newBuilder().setStreamId("st-1").setFrameSeq(0)
                .setContentType("image/png").build();
        Envelope.Builder data = Envelope.newBuilder().setChannelType(ChannelType.DATA);
        assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(valid).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(
                valid.toBuilder().setStreamId("bad\nid")).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(
                valid.toBuilder().setStreamId("")).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(
                valid.toBuilder().setFrameSeq(-1)).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(
                valid.toBuilder().setContentType("")).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(data.setDataFrame(
                valid.toBuilder().setContentType("x" + (char) 0 + "y")).build()).isOk());
    }

    @Test
    void heartbeatAndErrorContentValidateInsideTheEnvelope() {
        Envelope.Builder ctrl = Envelope.newBuilder().setChannelType(ChannelType.CONTROL);
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setHeartbeat(
                Heartbeat.newBuilder().setHeartbeatIntervalMillis(0)).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setHeartbeat(
                Heartbeat.newBuilder().setHeartbeatIntervalMillis(5000).setLeaseExpiresAtEpochMillis(-1))
                .build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setHeartbeat(
                Heartbeat.newBuilder().setHeartbeatIntervalMillis(5000).setProtocolVersion("v\n1"))
                .build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setError(
                ErrorFrame.newBuilder().setCode("")).build()).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setError(
                ErrorFrame.newBuilder().setCode("backoff").setDetail("d" + (char) 7 + "tail")).build()).isOk());
        assertTrue(RemoteBridgeProtoAdapter.validateEnvelope(ctrl.setError(
                ErrorFrame.newBuilder().setCode("backoff").setDetail("retry in 5s").setRetryable(true))
                .build()).isOk());
    }

    @Test
    void nullProtoInputsRejectInsteadOfThrowing() {
        assertFalse(RemoteBridgeProtoAdapter.decode((AgentHello) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((SessionRequest) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((ConsentPrompt) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((ConsentResult) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((OperationRequest) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((Kill) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode((AuditEvent) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.decode(
                (com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit) null).isOk());
        assertFalse(RemoteBridgeProtoAdapter.validateEnvelope(null).isOk());
    }
}
