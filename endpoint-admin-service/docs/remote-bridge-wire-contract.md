# Remote-Bridge Wire Contract (Faz 22.6 transport, T-1a)

> **Status:** T-1 domain model (pure Java records); the protobuf wire encoding is **T-2**.
> **Decided by:** [ADR-0038](../../../docs/adr/0038-faz-22-6-remote-access-transport.md) (gRPC/mTLS, broker-authoritative); Codex architecture thread `019eb9fb`.

This is the **shadow wire spec** (Codex guardrail): the T-1 Java records in
`com.example.endpointadmin.remoteaccess.bridge.contract` are designed to map **1:1** onto the future protobuf
`RemoteBridgeService` (T-2). T-1 owns **meaning** (records, canonicalisation, validation); T-2's protobuf
adapters own **bytes**. Reserved proto field numbers are fixed here so the encoding can be added in T-2 without
re-numbering.

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

## Reserved for T-2 (DATA-plane / stream framing — declarative only in T-1)
`channelType` (CONTROL/DATA), `frameSeq`, `streamId`, `heartbeatInterval`, `leaseExpiresAt`, and the screen/PTY
DATA payload fields are **reserved** (proto numbers to be assigned in T-2) — no streaming semantics exist in T-1.
