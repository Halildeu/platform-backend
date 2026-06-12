# Remote-Bridge Wire Contract (Faz 22.6 transport, T-1a + T-2a)

> **Status:** T-1 domain model + T-2a protobuf encoding + T-2b grpc server runtime + **T-2c mutual-TLS
> credential wiring LANDED** (`…bridge.server`: Netty lifecycle behind `remote-bridge.enabled=false` — the
> DEFAULT context has ZERO remote-bridge beans; an ENABLED server is mTLS-only, fail-closed). Real cert
> MATERIAL + the TLS-passthrough L4 edge + broker wiring (SessionContext assembly) remain
> **T-4 / owner-pilot-gated** (ADR-0034 §13/D10).
> **Decided by:** [ADR-0038](../../../docs/adr/0038-faz-22-6-remote-access-transport.md) (gRPC/mTLS, broker-authoritative); Codex architecture thread `019eb9fb`.

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
| 4 | attestationEvidenceB64 | string | req | passed to the B1.4d verifier; never trusted as a boolean |
| 5 | protocolVersion | string | req | |
| 6 | advertisedCapabilities | repeated Capability | opt | advisory; the broker computes the granted set |

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
The `canonicalPayload()` covers fields 1–14 (NOT the signature). Asymmetric key (broker-private/agent-public).

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
| 15 | signatureB64 | string | ✗ | the broker signature over fields 1–14 |

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
| 10–20 | oneof payload | | agent_hello(10), session_request(11), consent_prompt(12), consent_result(13), operation_request(14), operation_permit(15), kill(16), audit_event(17), heartbeat(18), data_frame(19), error(20) |

**Channel/payload compatibility** (statically enforced by `RemoteBridgeProtoAdapter.validateEnvelope`, tested):

| payload | CONTROL | DATA |
|---|---|---|
| data_frame | ✗ | ✓ |
| heartbeat, error | ✓ | ✓ |
| everything else (incl. **kill**) | ✓ | ✗ |

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
  audit_event / heartbeat / error — the broker-originated payloads (operation_permit, consent_prompt, kill)
  and the operator-console payloads (session_request, operation_request — they do NOT ride the agent tunnel)
  are refused inbound, so a semi-trusted agent can never inject broker authority. DATA accepts ONLY
  data_frame / heartbeat / error.
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
- **OperationPermit strict decode**: permitVersion pin (=1), alg pin (`SHA256withECDSA`), capability↔commandHash
  consistency re-enforced at the wire (CONSTRAINED_PTY ⇒ sha-256 lowercase hex; VIEW_ONLY ⇒ empty), positive
  validity window, seq ≥ 0, non-empty base64-checked signature.
- **Canonical-payload byte stability**: sign → encode → real wire bytes → parse → decode →
  `canonicalPayload()` byte-equal AND the broker signature still verifies (the T-1 acceptance boundary);
  any field changed on the wire fails verification.
- **No raw command in a permit** — the raw command line travels only in `OperationRequest`; a permit carries
  the canonical hash.
