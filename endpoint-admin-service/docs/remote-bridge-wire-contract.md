# Remote-Bridge Wire Contract (Faz 22.6 transport, T-1a + T-2a)

Tenant policy configuration, signing-key rotation and rollout order are documented in
[`remote-view-tenant-policy-runtime.md`](remote-view-tenant-policy-runtime.md).

> **Status:** T-1 domain model + T-2a protobuf encoding + T-2b grpc server runtime + **T-2c mutual-TLS
> credential wiring LANDED** (`…bridge.server`: Netty lifecycle behind `remote-bridge.enabled=false` — the
> DEFAULT context has ZERO remote-bridge beans; an ENABLED server is mTLS-only, fail-closed). Real cert
> MATERIAL + the TLS-passthrough L4 edge + broker wiring (SessionContext assembly) remain
> **T-4 / owner-pilot-gated** (ADR-0034 §13/D10).
> **T-4 `OperationDispatch` (broker→agent CONSTRAINED_PTY command transport) is DESIGN-SPEC** below (Codex
> thread `019ecd07`, B2): proto encoding + broker push + agent dispatch are the follow-on slices; LIVE
> dispatch is owner-pilot-gated. The agent-side execution path (verify→gate→ConPTY→stream) is already
> code-complete (platform-agent `internal/remotebridge/{operation,ptyexec}`).
> **Decided by:** [ADR-0038](../../../docs/adr/0038-faz-22-6-remote-access-transport.md) (gRPC/mTLS, broker-authoritative); Codex architecture thread `019eb9fb`; T-4 dispatch extension `019ecd07`.

This started as the **shadow wire spec** (Codex guardrail) and is now the contract documentation for the real
encoding: the T-1 Java records in `com.example.endpointadmin.remoteaccess.bridge.contract` map **1:1** onto the
protobuf messages in `remote_bridge.proto` (generated into
`com.example.endpointadmin.remoteaccess.bridge.proto`; generated sources are never committed). T-1 owns
**meaning** (records, canonicalisation, validation); the T-2a adapters in
`com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter` own **bytes** — every decode
re-validates (ids, enum default-deny, proto3 default normalisation) and returns an explicit
`DecodeResult.ok/reject`, so a generated proto class never reaches the broker.

**T-1 acceptance boundary:** the protobuf DTO adapters in T-2 must replace these records on the wire **without
changing** `RemoteBridgeBroker`, `RemoteBridgeSessionStateMachine`, `RemoteBridgePermitSigner`, or the
policy-dry-run tests. The signed `OperationPermit.canonicalPayload()` is independent of BOTH `toString()` and
the protobuf binary encoding — T-2 adapters must reproduce those exact bytes.

## Capability enum (proto: `Capability`)

| proto # | value | notes |
|---|---|---|
| 0 | `CAPABILITY_UNSPECIFIED` | default-deny (an unset/unknown capability is refused) |
| 1 | `VIEW_ONLY` | pilot-enabled |
| 2 | `CONSTRAINED_PTY` | pilot-enabled |
| 3–15 | *reserved* | the non-pilot `RemoteSessionCapability` values stay OFF-wire for the pilot |

The Java side reuses `RemoteSessionCapability`; `WireContract` rejects any value not in `PILOT_ALLOWED`
(future enum additions default-deny).

## Identifier validation

Every required wire identifier (`sessionId`, `deviceId`, `operatorSubject`, `operationId`, …) MUST match the
allowlist `^[A-Za-z0-9._:@+=-]{1,256}$` (`WireContract.isValidId`). This bars control characters, newlines, and
NUL — an id flows into the audit recorder, logs, policy detail, and the future proto adapters, so an unbounded
id is an audit/log-injection surface. The protobuf adapters (T-2) MUST re-apply this validation on decode.

## Messages

Required/optional + validation per field; `req` = required (validated), `opt` = optional.

### `AgentHello` (proto `AgentHello`) — **ADVISORY ONLY, never an authorization input**
| # | field | type | r/o | notes |
|---|---|---|---|---|
| 1 | agentVersion | string | req | |
| 2 | deviceId | string | req | the broker re-derives device trust via B1.4d, never trusts this |
| 3 | certFingerprint | string | req | advisory; the broker uses the real mTLS peer cert |
| 4 | attestationEvidenceB64 | string | req | passed to the B1.4d verifier; never trusted as a boolean; max 16 KiB |
| 5 | protocolVersion | string | req | |
| 6 | advertisedCapabilities | repeated Capability | opt | advisory; the broker computes the granted set |
| 7 | supportedFeatures | repeated string | opt | advisory compatibility gate; may only narrow/deny. Policy mode requires `remote-view-session-policy-envelope-v1` |

#### `AgentHello.attestationEvidenceB64` payloads

The field is an outer Base64-encoded UTF-8 payload. It has two accepted parse forms:

1. **Legacy bounded-pilot provenance:** `binaryDigest|builderId|slsaPredicateHash|predicateSignature`.
   This remains accepted for the existing enrollment-backed staging path and produces only
   `AttestationEvidence`; it never produces `DeviceKeyAttestation`.
2. **B1.4d v1 envelope:** a strict JSON object with only `v`, `slsa`, and `deviceKey` fields. `v` must be
   exactly `1`. `slsa`, when present, uses the same four provenance fields as the legacy form. `deviceKey`, when
   present, must contain:

   ```json
   {
     "keyDer": "<base64 DER public key>",
     "protectionLevel": "SECURE_ELEMENT_OR_TPM",
     "nonExportable": true,
     "signature": "<base64 attestation signature>",
     "algorithm": "SHA256withECDSA",
     "chainDer": ["<base64 DER attestation CA leaf>", "..."]
   }
   ```

Malformed Base64, invalid UTF-8, unknown envelope versions, unknown JSON fields, missing required device-key
fields, unsupported protection levels, and oversized payloads all fail closed to empty parsed evidence. This parser
only extracts structured evidence; it does not mark the device trusted. `DeviceIdentityVerifier` and the configured
device-attestation roots still decide trust.

Session policy consumes the parsed result only through explicit `remote-bridge.device-trust.verifier` modes:
`FAIL_CLOSED` is the default, `MACHINE_CERT_ENROLLMENT` is enrollment-only and non-prod, `DEVICE_KEY_ATTESTATION`
promotes the **non-live, replay-prone STATIC CA device-key attestation** (#732) carried in the AgentHello envelope
— non-prod diagnostics only, NOT #548 closure — and `REQUIRE_ENROLLMENT_AND_DEVICE_KEY` composes machine-cert
enrollment with that CA-static path: non-prod only, **REFUSED in a production-like profile** until the canonical
#548 TPM-native live challenge-response verifier (`DEVICE_KEY_ATTESTATION_REAL`, forthcoming) backs the
composite's hardware leg. Parser/verifier presence is therefore not a closure signal by itself.

**#548 boundary:** this backend wire parser is necessary but not sufficient for true TPM/device-key readiness. The
issue remains open until an agent producer presents real hardware-backed device-key evidence on this field, the
broker verifies it against provisioned device-attestation roots, and live negative/positive field evidence is
accepted.

### `SessionRequest` (proto `SessionRequest`)
| # | field | type | r/o | validation |
|---|---|---|---|---|
| 1 | sessionId | string | req | `isValidId` |
| 2 | deviceId | string | req | `isValidId` |
| 3 | operatorSubject | string | req | `isValidId` |
| 4 | reason | string | opt | |
| 5 | requestedCapabilities | repeated Capability | req | `allPilotCapabilities` (non-empty, all pilot) |

### `ConsentPrompt` (proto `ConsentPrompt`)
| # | field | type | r/o |
|---|---|---|---|
| 1 | sessionId | string | req |
| 2 | operatorDisplayName | string | req |
| 3 | reason | string | opt |
| 4 | capabilities | repeated Capability | req |
| 5 | expiryEpochMillis | int64 | req |
| 6 | sessionPolicyEnvelope | SessionPolicyEnvelope | opt | absent only when server policy mode is disabled; enabled mode denies a non-advertising agent before prompt delivery |

`SessionPolicyEnvelope.canonicalJson` is the exact UTF-8
`remote-view-session-policy-envelope-v1` JSON artifact. The endpoint strict-parses it, validates the
schema, recomputes the RFC 8785 payload digest and verifies the Ed25519 signature before rendering consent.
Proto unknown-field tolerance is not compatibility acceptance: an old agent that did not advertise the
feature is denied and receives no legacy prompt when server policy mode is enabled.

### `ConsentResult` (proto `ConsentResult`) → becomes a `ConsentLease`
| # | field | type | r/o |
|---|---|---|---|
| 1 | sessionId | string | req |
| 2 | granted | bool | req |
| 3 | windowsInteractiveSession | string | req |
| 4 | grantedAtEpochMillis | int64 | req |
| 5 | expiryEpochMillis | int64 | req |

### `OperationRequest` (proto `OperationRequest`)
| # | field | type | r/o | notes |
|---|---|---|---|---|
| 1 | sessionId | string | req | |
| 2 | operationId | string | req | |
| 3 | operation | RemoteOperation enum | req | the broker normalizes |
| 4 | commandLine | string | opt | PTY only; null for non-PTY; canonicalised by `CanonicalCommand` |

### `OperationPermit` (proto `OperationPermit`) — broker-signed (T-1b signer), agent-verified
Permit v1 `canonicalPayload()` covers fields 1–14. Permit v2 uses a distinct domain and also covers field 16.
The signature field is never part of its own signed payload. Asymmetric key (broker-private/agent-public).

| # | field | type | signed | notes |
|---|---|---|---|---|
| 1 | alg | string | ✓ | allowlisted signature alg |
| 2 | kid | string | ✓ | broker permit-signing key id |
| 3 | permitVersion | int32 | ✓ | |
| 4 | policyVersion | string | ✓ | |
| 5 | decisionId | string | ✓ | |
| 6 | sessionId | string | ✓ | |
| 7 | operationId | string | ✓ | |
| 8 | deviceId | string | ✓ | |
| 9 | operatorSubject | string | ✓ | |
| 10 | capability | Capability | ✓ | |
| 11 | commandHash | string | ✓ | `CanonicalCommand.hash()`; empty for a non-command op |
| 12 | issuedAtEpochMillis | int64 | ✓ | |
| 13 | expiresAtEpochMillis | int64 | ✓ | short; agent enforces `isFresh` |
| 14 | seq | int64 | ✓ | monotonic per-session (replay guard) |
| 15 | signatureB64 | string | ✗ | v1 signs fields 1–14; v2 signs fields 1–14 and field 16 |
| 16 | policyEnvelopeDigest | string | v2: ✓ | required lowercase `sha256:` digest for policy-bound permit v2; empty on legacy v1 |

### `OperationDispatch` (proto `OperationDispatch`) — broker→agent CONTROL, T-4 CONSTRAINED_PTY command transport

> **Status: design-spec (T-4 wire extension), NOT yet encoded.** Extends the ADR-0038 wire under ADR-0034 D8
> `CONSTRAINED_PTY`; disabled-by-default; a LIVE dispatch is owner-pilot-gated (§13/D10). Defined here so the
> broker-push impl + the agent re-vendor + the agent dispatch (slice-5b) bind to ONE Codex-ratified shape
> (thread `019ecd07`, B2). Field numbers below are PROPOSED — finalized when the proto lands.

The signed `OperationPermit` (hash only) cannot, by itself, drive an agent execution: the agent needs the
PLAINTEXT command to resolve the no-shell ExecPlan and to re-derive the hash. The raw command still NEVER
rides inside the permit (the signed bytes are unchanged); it is transported in a wrapper that pairs it with
the signed permit in ONE self-contained broker→agent frame. The command is authorization-NEUTRAL: it is
trusted only once `CanonicalCommand.hash(commandLine)` equals the SIGNED `permit.commandHash`.

| # | field | type | r/o | notes |
|---|---|---|---|---|
| 1 | permit | OperationPermit | req | the broker-signed permit, byte-for-byte as signed (see integrity rule) |
| 2 | commandLine | string | opt | plaintext, carried EXACT/raw — `CanonicalCommand` canonicalises only for the hash-check, the wire never normalises it. For `CONSTRAINED_PTY`: **required non-empty** (whitespace-only rejected) + ≤256 chars + no control chars (defense-in-depth: a NUL/newline/over-long command never reaches a log/exec path). For `VIEW_ONLY`: **empty** (decodes ≡ `null`). |

**Agent fail-closed acceptance order (no step may be reordered; any failure → no execute, no DATA stream):**
1. **Disabled-by-default gate** — if the agent's PTY operation handler is not configured, `OperationDispatch`
   is REFUSED inbound (a CONTROL `ErrorFrame` + no-op; never silently consumed). Only an explicitly-enabled
   agent accepts it.
2. **Permit signature** — verification MUST run the SAME `OperationPermit.canonicalPayload()` canonicalizer
   over the permit's fields (parse the inner permit out of the wrapper, then canonicalize via the identical
   function); never treat the `OperationDispatch` wrapper bytes as the signed message. `canonicalPayload()`
   is independent of the protobuf/wrapper encoding, so the signed bytes are unchanged by being transported
   inside `OperationDispatch`.
3. **Freshness + replay** — `isFresh` (`issuedAt ≤ now < expiresAt`) + per-`sessionId` monotonic `seq`
   (existing gate). The TTL is owned by `permit.issuedAt/expiresAt` + `seq`; `operationId` serves correlation
   (to the DATA stream) + single-use replay protection, NOT the TTL itself.
4. **Capability ↔ command consistency** — `CONSTRAINED_PTY` ⇒ `commandLine` non-empty AND
   `permit.commandHash` non-empty; `VIEW_ONLY` ⇒ `commandLine` empty AND `permit.commandHash` empty.
5. **Command binding** — `CanonicalCommand.hash(commandLine)` MUST equal `permit.commandHash`, computed with
   the IDENTICAL `CanonicalCommand` canonicalization profile (delimiter/trim/normalization) that minted the
   signed hash — the cross-language vector (`pty-permit-vector.json`, platform-agent #184 + drift-guard #667)
   anchors this profile on both sides; any profile drift ⇒ mismatch ⇒ reject. Mismatch ⇒ reject, no execute
   (the command is never trusted unbound from the signed hash).
6. **Execute + stream** — gated `Executor` runs the allowlisted no-shell command; output streams over the
   DATA stream keyed by `permit.operationId` (`DataFrame.streamId = operationId`).

**One-dispatch-per-operation:** an `operationId` is single-use. A re-sent dispatch for an already-handled
`operationId` MUST NOT cause a second execution; the agent returns a deterministic fail-closed response that
references the already-handled operation (never re-running it) — combined with the `seq` monotonic guard,
this prevents retry/replay amplification and closes the double-execute window.

### `Kill` (proto `Kill`) — CONTROL channel
| # | field | type |
|---|---|---|
| 1 | sessionId | string |
| 2 | killReason | string |
| 3 | issuedAtEpochMillis | int64 |

### `AuditEvent` (proto `AuditEvent`) — control-plane metadata + content hash, never raw payload
| # | field | type |
|---|---|---|
| 1 | sessionId | string |
| 2 | eventType | string |
| 3 | contentHash | string |
| 4 | epochMillis | int64 |

### `DeviceKeyChallenge` (proto `DeviceKeyChallenge`) — broker→agent CONTROL, Faz 22.6 #548 Path A
One-shot, TTL-bounded device-key liveness challenge. ADVISORY transport frame; full design in
`docs/faz22.6-device-key-session-attestation-design.md` (Codex 019efada). The agent answers with a
`DeviceKeyAttestationResponse` signed over a canonical binding context derived from these fields.
| # | field | type | notes |
|---|---|---|---|
| 1 | challengeId | string | wire-id; broker replay-cache key |
| 2 | nonceB64 | string | base64 (32 raw bytes), bounded |
| 3 | issuedAtEpochMillis | int64 | > 0 |
| 4 | expiresAtEpochMillis | int64 | > issuedAt (positive validity window; short TTL) |
| 5 | transportPeerKey | string | broker-observed mTLS leaf SHA-256 (binding anchor) |
| 6 | protocolVersion | string | `device-key-session-v1` |

### `DeviceKeyAttestationResponse` (proto `DeviceKeyAttestationResponse`) — agent→broker CONTROL, Faz 22.6 #548 Path A
Fresh device-key session attestation. ADVISORY shape — the `DEVICE_KEY_ATTESTATION_REAL` verifier (later slice)
re-derives every fact (deviceKeyPub == mTLS leaf key; deviceKeySig over the binding context; AK-certify;
EK→pinned-root). `ekCert*` OPTIONAL at the wire (bounded-lab pilot may omit). NEVER carries secrets.
| # | field | type | notes |
|---|---|---|---|
| 1 | challengeId | string | wire-id; echoes the challenge |
| 2 | schema | string | `faz22.6.device-key-session.v1` |
| 3 | deviceKeyPubB64 | string | required b64; verifier checks == mTLS leaf key |
| 4–6 | akPubB64, akNameB64, ekPubB64 | string | required b64 |
| 7–8 | ekCertB64, ekCertChainB64[] | string / repeated | OPTIONAL (strong path); chain ≤ 10 entries |
| 9–10 | certifyInfoB64, certifySigB64 | string | required b64 (AK-certify of device key) |
| 11–12 | quoteB64, quoteSigB64 | string | required b64 (quote.extraData = hash(bindingContext)) |
| 13 | bindingContextB64 | string | required b64; canonical `label‖challengeId‖nonce‖transportPeerKey‖expiry` |
| 14 | deviceKeySigB64 | string | required b64; device key signs bindingContext (live possession) |
| 15 | signedAtEpochMillis | int64 | > 0 |

## Service + Envelope (FINALIZED in T-2a — Codex 019eb9fb)

```proto
service RemoteBridge {
  rpc Connect(stream Envelope) returns (stream Envelope); // CONTROL — never-drop
  rpc Data(stream Envelope) returns (stream Envelope);    // DATA — drop-tolerant
}
```

**KILL is NOT a separate RPC** — it is `Envelope.kill` on the CONTROL stream. CONTROL and DATA are separate
HTTP/2 streams (T-2b may place them on separate connections), so DATA backpressure can never delay a KILL.
The agent initiates both streams (outbound-only, NAT-friendly); the broker pushes on the response side.

### `Envelope`
| # | field | type | notes |
|---|---|---|---|
| 1 | sessionId | string | optional routing header; empty until a session exists; validates when set |
| 2 | deviceId | string | optional routing header; validates when set |
| 3 | channelType | ChannelType | **required** — `CHANNEL_TYPE_UNSPECIFIED`(0) rejects (default-deny) |
| 4 | frameSeq | int64 | ≥ 0 |
| 5 | streamId | string | optional; validates when set |
| 6 | sentAtEpochMillis | int64 | ≥ 0 |
| 10–23 | oneof payload | | agent_hello(10), session_request(11), consent_prompt(12), consent_result(13), operation_request(14), operation_permit(15), kill(16), audit_event(17), heartbeat(18), data_frame(19), error(20), operation_dispatch(21), **device_key_challenge(22)** (broker→agent), **device_key_attestation_response(23)** (agent→broker) — Faz 22.6 #548 Path A device-key session attestation |

**Channel/payload compatibility** (statically enforced by `RemoteBridgeProtoAdapter.validateEnvelope`, tested):

| payload | CONTROL | DATA |
|---|---|---|
| data_frame | ✗ | ✓ |
| heartbeat, error | ✓ | ✓ |
| everything else (incl. **kill**, **operation_dispatch**) | ✓ | ✗ |

### `ChannelType`
0 = `CHANNEL_TYPE_UNSPECIFIED` (reject), 1 = `CONTROL`, 2 = `DATA`.

### `WireOperation` (proto twin of the pilot slice of `RemoteOperation`)
0 = `WIRE_OPERATION_UNSPECIFIED` (reject), 1 = `SCREEN_VIEW`, 2 = `PTY_COMMAND`, 3–15 reserved. Only the
broker-allowlisted pilot operations exist on the wire at all; decode of anything else rejects.

### `Heartbeat`
| # | field | type |
|---|---|---|
| 1 | heartbeatIntervalMillis | int64 |
| 2 | leaseExpiresAtEpochMillis | int64 |
| 3 | protocolVersion | string |

### `DataFrame` (declared; semantically inert until T-2b/T-4)
| # | field | type |
|---|---|---|
| 1 | streamId | string |
| 2 | frameSeq | int64 |
| 3 | contentType | string |
| 4 | payload | bytes |
| 5 | endStream | bool |

T-2b enforces a max frame byte size at the stream layer (no decode-time size guard in T-2a — no config yet).

### `ErrorFrame`
| # | field | type | notes |
|---|---|---|---|
| 1 | code | string | |
| 2 | detail | string | internal/debug only — never secrets, never raw session content |
| 3 | retryable | bool | |

## T-2b stream rules (`…bridge.server.RemoteBridgeConnectService`, tested in-process)

- **No anonymous streams**: Connect/Data without an authenticated `PeerIdentity` (mTLS leaf-fingerprint via
  `PeerIdentityInterceptor`; injected context key in tests) is refused before any payload is read.
- **Directional allowlist (inbound = agent side)**: CONTROL accepts ONLY agent_hello / consent_result /
  audit_event / heartbeat / error — the broker-originated payloads (operation_permit, consent_prompt, kill,
  **operation_dispatch**) and the operator-console payloads (session_request, operation_request — they do NOT
  ride the agent tunnel) are refused inbound, so a semi-trusted agent can never inject broker authority. DATA
  accepts ONLY data_frame / heartbeat / error.
- **OperationDispatch is the lone broker→agent command path** (T-4, design-spec): it flows ONLY broker→agent
  on CONTROL; the broker REFUSES it inbound (above). On the agent it is accepted ONLY by an explicitly-enabled
  PTY operation handler — disabled-by-default the agent fail-closes it (ErrorFrame, no execute, no DATA
  stream). It is the ONLY frame that carries a plaintext command toward the agent, always paired with its
  signed permit and bound by the hash-match (no raw-command trust).
- **Sequencing**: CONTROL = `Envelope.frameSeq`, strictly increasing from 0 per stream. DATA sequencing
  authority is **`DataFrame.frameSeq` per `DataFrame.streamId`** (proto3 int64 has no presence, so the
  envelope counter cannot be optional); the DATA `Envelope.frameSeq` MUST be 0 and `Envelope.streamId` must
  be empty or equal to the frame's. Replay/duplicate → ErrorFrame + close.
- **Byte cap**: `DataFrame.payload` over `remote-bridge.max-data-frame-bytes` closes the stream (the stream
  layer owns the cap, deferred here from T-2a by design).
- **Advisory-hello identity rule**: an `AgentHello.deviceId` contradicting the cert-bound device id closes
  the stream; the `ControlStreamRegistry` is keyed by the AUTHENTICATED `transportPeerKey`, never by an
  advisory deviceId — and a reconnect replaces only the SAME peer's stream.
- **KILL path**: `ControlStreamRegistry.killPeer` pushes `Envelope.kill` on CONTROL then completes the
  stream (terminal). Tested: KILL lands < 1s while DATA is saturated. A transport-scoped kill without a
  broker session uses the `transport-kill` sentinel session id (satisfies the Kill wire contract).
- **Heartbeat**: server-push on CONTROL only; `leaseExpiresAt=0` until the broker lease wiring (T-4);
  missed-heartbeat policy is the agent's (T-3).
- **Seam**: decoded domain records + `PeerIdentity` go to `ControlPlaneHandler` (INERT in T-2b);
  `RemoteBridgeBroker` is NOT called — SessionContext assembly is T-4.

## T-2c transport mTLS (tested in `RemoteBridgeMtlsTest` over the REAL Netty transport)

- **Secure by default:** an enabled server REQUIRES the complete TLS triple —
  `remote-bridge.tls.cert-chain-pem-path` + `private-key-pem-path` + `client-ca-pem-path` (FILE paths;
  K8s secret mounts at the pilot; PEM bodies are never inline and never committed) — loaded into grpc
  `TlsServerCredentials` with `clientAuth=REQUIRE` against the device CA. Partial config, missing/unreadable
  files, or garbage PEM all refuse BEFORE any bind (the port is provably never opened).
- **Plaintext is a loopback-only test mode:** `remote-bridge.allow-insecure-plaintext=true` AND a
  provably-loopback bind host (literal `127.0.0.1`/`::1` — hostnames incl. `localhost` and wildcards
  `0.0.0.0`/`::` are refused: what a name resolves to is ambient state, not proof).
- **Identity vs trust:** transport mTLS authenticates the peer and feeds the unchanged T-2b
  `PeerIdentityInterceptor` (a CA-signed client's `PeerIdentity` materializes with the leaf SHA-256
  fingerprint — full-stack tested); device TRUST (revocation/CRL, EKU, identity decision) stays with the
  B1.4 `CertTrustEvaluator` at the application layer. Deliberately ONE revocation authority — there is no
  transport-level CRL and no custom TrustManager (Codex 019ebb6c).
- A certless or wrong-CA client fails the handshake; nothing reaches the control-plane seam and no registry
  slot is claimed.
- Pilot flip = config + mounted files only: set the three paths (+ `enabled=true`, non-loopback bind, the
  L4 TLS-passthrough edge) — no code change. Device-CA issuance/distribution stays B1.4/T-4 operational.

## T-2a adapter invariants (tested in `RemoteBridgeProtoAdapterTest`)

- **Ids re-validated on decode** (`WireContract.isValidId`); the permit `decisionId` is the broker composite
  `sessionId:operationId`, so its bound is 513 chars on the same character allowlist.
- **Enums default-deny in both directions**: `*_UNSPECIFIED`, `UNRECOGNIZED` (unknown wire number — e.g. a
  peer speaking a newer contract), and non-pilot values reject the whole message; `encodeCapability`/
  `encodeOperation` throw on a non-pilot value (it must never reach the wire).
- **proto3 implicit defaults explicit**: optional text (reason, commandLine) empty↔null normalised; an empty
  repeated `requestedCapabilities` (the proto3 default!) rejects a SessionRequest.
- **OperationPermit strict decode**: permitVersion pin (=1 legacy or =2 policy-bound), alg pin (`SHA256withECDSA`),
  v1 requires an empty policy-envelope digest and v2 requires a canonical `sha256:` digest; capability↔commandHash
  consistency re-enforced at the wire (CONSTRAINED_PTY ⇒ sha-256 lowercase hex; VIEW_ONLY ⇒ empty), positive
  validity window, seq ≥ 0, non-empty base64-checked signature.
- **Canonical-payload byte stability**: sign → encode → real wire bytes → parse → decode →
  `canonicalPayload()` byte-equal AND the broker signature still verifies (the T-1 acceptance boundary);
  any field changed on the wire fails verification.
- **No raw command in a permit** — the raw command line never rides inside `OperationPermit` (the signed
  bytes carry only the canonical hash). Operator→broker it travels in `OperationRequest`; broker→agent it
  travels in `OperationDispatch` (T-4), always paired with the signed permit and accepted only after the
  agent re-derives `CanonicalCommand.hash(commandLine) == permit.commandHash`.
