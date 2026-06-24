# Faz 22.6 — Device-Key Session Attestation Wire-Contract (DESIGN)

> **#548 Path A foundation.** Converts the `SessionDeviceTrustVerifierFactory` "future mode"
> (`DEVICE_KEY_ATTESTATION_REAL`) comment into a concrete, buildable, replay-resistant contract so the
> broker can set `deviceTrusted=true` on a **fresh, live, hardware-bound** proof — not enrollment identity.
>
> **3-AI co-design:** Claude (impl) + Codex `019efada` (architecture REVISE → this spec, owner Halil).
> Companion: `RB-faz22.6-548-hardware-attestation-gap-audit.md` (gitops) — the gap this closes.
> Wire source-of-truth: `remote-bridge-wire-contract.md` + `src/main/proto/remote_bridge.proto`.

## 0. Why not the obvious shapes (rejected, with reason)
- **Overload `AgentHello.attestation_evidence_b64` (field 4)** → REJECTED. Field 4 is SLSA/build-provenance
  (`TransportBoundPeerEvidenceParser.java:99-105`); device trust is a separate axis.
- **Add a load-bearing `AgentHello` field 7** → REJECTED. `AgentHello` is the agent's FIRST message; there is
  no broker nonce yet → any attestation carried there is **replayable**. AgentHello stays ADVISORY.
- **Transport-binding-only freshness** (`leaf_thumbprint‖session_id‖timestamp`) → REJECTED for the pilot. A
  copied mTLS leaf private key + an old response replays within the TTL — exactly the class #548 closes. (A
  real TLS-exporter channel binding *would* be acceptable but is not the pilot baseline.)
- **Reuse enrollment `TpmAttestEnvelope` / agent `tpmenroll.AttestEnvelope`** → REJECTED. Those carry one-time
  issuance fields (`enrollmentToken`, `nonceId`, `activatedSecret`, `csrDer`) — session ≠ enrollment.

## 1. Mechanism — explicit challenge-response (broker-nonced, fresh)
Two NEW `Envelope` CONTROL payloads (broker→agent, then agent→broker):

```proto
// Broker → agent (CONTROL). One-shot, TTL-bounded device-key liveness challenge.
message DeviceKeyChallenge {
  string challenge_id = 1;             // single-use; broker replay-cache keyed on this
  string nonce_b64 = 2;                // 32 random bytes, base64 (as merged in #741; not raw `bytes`)
  int64  issued_at_epoch_millis = 3;
  int64  expires_at_epoch_millis = 4;  // short TTL (e.g. 30s)
  string transport_peer_key = 5;       // broker-observed mTLS leaf SHA-256 (the binding anchor)
  string protocol_version = 6;         // "device-key-session-v1"
}

// Agent → broker (CONTROL). The fresh device-key session attestation.
message DeviceKeyAttestationResponse {
  string challenge_id = 1;
  string schema = 2;                   // "faz22.6.device-key-session.v1"
  string device_key_pub_b64 = 3;       // MUST equal the mTLS leaf cert public key (pin one encoding)
  string ak_pub_b64 = 4;
  string ak_name_b64 = 5;
  string ek_pub_b64 = 6;
  string ek_cert_b64 = 7;              // strong path: present + chains to pinned root
  repeated string ek_cert_chain_b64 = 8;
  string certify_info_b64 = 9;         // TPM2_Certify(device_key) by AK  → device key is TPM-resident
  string certify_sig_b64 = 10;
  string quote_b64 = 11;               // quote.extraData = hash(binding_context)  (V5/V6 + freshness)
  string quote_sig_b64 = 12;
  string binding_context_b64 = 13;     // canonical: label‖challenge_id‖nonce‖transport_peer_key‖expiry
  string device_key_sig_b64 = 14;      // device key signs binding_context  → live possession NOW
  int64  signed_at_epoch_millis = 15;
}
```

`Envelope` oneof: next free tags — `device_key_challenge = 22`, `device_key_attestation_response = 23`
(field numbers FROZEN once merged in the backend source-of-truth proto; agent regenerates the vendored copy).
Field 4's 8192 cap does NOT apply here → set an explicit per-field + total-envelope size cap for the response.

## 2. Verifier — `VerifierType.DEVICE_KEY_ATTESTATION_REAL`
New `SessionDeviceTrustVerifier` impl. Returns `deviceTrusted=true` (Basis = `HARDWARE_KEY_ATTESTATION`;
or `COMPOSITE` if enrollment AND hardware are both required) **iff ALL** hold (else fail-closed, uniform-deny):
1. **Live possession** — `device_key_sig` valid over the exact canonical `binding_context`
   (`F22.6_DEVICE_KEY_SESSION_V1` label ‖ challenge_id ‖ nonce ‖ transport_peer_key ‖ expiry).
2. **Freshness/anti-replay** — `challenge_id` known, unexpired, single-use (replay-cache consume); `nonce`
   + `transport_peer_key` match the issued challenge; not future-dated.
3. **Session binding** — `device_key_pub` == the mTLS **leaf** public key the broker derived from the
   transport (`PeerIdentity`, NOT from the hello). So the attested key IS the key authenticating this session.
4. **TPM residency** — `certify_info/certify_sig`: device key certified by the AK (TPM-resident, fixedTPM).
5. **AK→EK + root policy** — AK attested under EK; EK cert chains to a **pinned manufacturer/owner-CA root**
   (strong path mandatory). `quote`/`quote_sig` fresh over the same binding context; PCR allow-set if mandated.

## 3. Hard distinctions (carried from the gap-audit runbook)
- `MACHINE_CERT_ENROLLMENT` ≠ `HARDWARE_KEY_ATTESTATION` — never label one as the other in the Basis audit.
- vTPM/swtpm + pinned **lab** CA = **bounded-lab** evidence (honest marker), NOT `hardware-attested-device`.
- source proof (these tests pass) ≠ acceptance marker (live broker `device=true` + field matrix + owner sign).

## 4. Invariants (build gates)
- **Domain separation:** signed canonical payload begins with `F22.6_DEVICE_KEY_SESSION_V1`; never collides
  with enrollment payloads.
- **Directionality:** `DeviceKeyChallenge` only broker→agent CONTROL; `DeviceKeyAttestationResponse` only
  agent→broker CONTROL. Agent sender allowlist (`sender.go`) + broker inbound allowlist
  (`RemoteBridgeConnectService.java`) extended exactly by these two, fail-closed otherwise.
- **No secrets on wire/log:** no `activatedSecret`, enrollment token, private key, raw cert secret; response
  never logged raw.
- **Size caps:** explicit per-field + total cap on the response envelope.
- **EK-cert NV-read:** strong path requires the agent EK-cert (chains to pinned root); the agent
  `tpmdevice_windows.go:107-113` EK-cert NV-read is currently stubbed (nil) → MUST be implemented for the
  strong path (bounded path may pin a lab/device-CA root instead, labeled as such).
- **PCR scope:** if PCR policy is mandatory, a missing/short PCR set fails closed; if advisory in the bounded
  pilot, do NOT present the result as hardware-complete.
- **Negative matrix (each must DENY, uniform):** missing field, malformed b64, stale challenge, replayed
  challenge, wrong `transport_peer_key`, `device_key_pub` ≠ mTLS leaf key, weak/exportable key, untrusted EK
  chain, bad AK certify, bad quote, wrong tenant/device, old agent (no response).

## 5. Implementation sequence (each its own PR + cross-AI)

> **Status (2026-06-24):** steps 1-3 + the reconciliation guard are MERGED; the AK↔EK prerequisite (step 4) is
> the next slice. See §7 for the precise next-session P0.

1. ✅ **DONE — PR #741.** Backend (source-of-truth): proto (the 2 CONTROL payloads) + domain records
   (`DeviceKeyChallenge`, `DeviceKeyAttestationResponse`) + wire adapters (decode + re-validate, fail-closed) +
   directional allowlists + round-trip tests. No verifier.
   - ✅ **Reconciliation guard — PR #743** (Codex `019efada`): a parallel session (#732) landed a CA-static,
     AgentHello-carried device-key path; this guard quarantines it to non-prod — the prod composite is REFUSED
     until `DEVICE_KEY_ATTESTATION_REAL` lands — so it can never read as production hardware trust. See §6.
2. ✅ **DONE — PR #744.** Backend: map a validated `DeviceKeyAttestationResponse` → the TPM-native
   `TpmDeviceKeySessionAttestation` (deeply immutable, shape-only, fail-closed) — **NOT** `ParsedEvidence.deviceKey`.
3. ✅ **DONE — PR #746.** Backend: `DeviceKeyChallengeStore` — broker-nonced issue + atomic single-use/TTL
   consume (peer-bound, no-oracle). The freshness/replay guard.
4. ⛔ **NEXT — prerequisite (Codex `019efada` decision A): TPM enrollment binding persistence.** Step 5's strong
   path needs the AK↔EK binding; it CANNOT rest on `device_key_pub==leaf` + `Certify` + `EK-root` alone — a
   software AK, or a genuine EK cert *borrowed from another TPM*, would otherwise pass. The enrollment proves
   AK↔EK at V10 but persists nothing queryable today (only the Vault-issued mTLS cert; `endpoint_machine_certs`
   has no TPM-binding column, and `TpmEnrollmentCompletionService` notes "device-record binding by ek_pub_sha256
   is a follow-up"). So FIRST persist — on successful `/attest`, after Vault issuance, transactionally with
   `markConsumed` — at least: `{tenantId, deviceId, endpointEnrollmentId, akName (RAW bytes — the TPM Name is the
   canonical compare input), akPubHash, ekCertSha256 and/or ekPubHash, deviceKeySpkiSha256, enrolledAt,
   rootPolicyResult, revokedAt}` (digests suffice except `akName`). A `CONSUMED-but-no-binding` row stays
   fail-closed in the strong path; a null-`deviceId` row is NOT a trustable active binding.
5. **Backend: `DEVICE_KEY_ATTESTATION_REAL` verifier** (§2) + factory mode + profile gating + negative matrix.
   Verifier order (Codex): challenge consume + TTL + single-use → recompute binding context + `deviceKeySig` →
   mTLS leaf SPKI == response `deviceKeyPub` → active machine-cert tenant/device match → **persisted TPM-binding
   lookup** by tenant/device + leaf-key digest → `akPub.computeName()` == response `akName` == persisted `akName`
   → EK cert/root policy pass + EK fingerprint == persisted EK → `verifyCertify(ak, deviceKey)` + quote/liveness
   → negative matrix. Only when ALL hold → `hardwareKeyAttested()`.
6. **Agent (Go):** regenerate vendored proto (`scripts/proto/generate.sh`; update `descriptor_guard_test.go`
   Len 11→13, range >20→>23, +2 oneof entries); produce the `DeviceKeyAttestationResponse` (fresh Certify/Quote
   + device-key sig over the canonical binding context) from `internal/tpmenroll`; sender allowlist; finish the
   EK-cert NV-read (`tpmdevice_windows.go:107-113` stub) for the strong path. Gated, default-off.
7. **Live run + marker:** real-hardware (or labeled bounded-lab) end-to-end → broker `device=true` →
   `RB-faz22.6-548-hardware-attestation-gap-audit.md` strong marker.

> Do NOT ship a field-7-only / AgentHello-carried variant — it would re-open the "source proof but no session
> freshness" finding. Steps 4-6 are agent-doable; step 7 needs operator (live Vault) + real-hardware + owner sign.

## 6. Reconciliation with #732 (CA-static AgentHello path) — Codex `019efada`

A parallel session landed **PR #732** ("Wire device-key attestation into session trust") concurrently. It took a
**different evidence family**: `TransportBoundPeerEvidenceParser` now parses `AgentHello.attestation_evidence_b64`
as a JSON envelope `{v, slsa, deviceKey:{keyDer, signature, chainDer, protectionLevel, nonExportable, algorithm}}`
→ `DeviceIdentityVerifier.DeviceKeyAttestation` (a **CA-attestation** model: a device-attestation CA signs over
the device key; the chain builds to a configured device root) → `PeerTrustLedger.deviceTrusted` →
`PeerDeviceKeyAttestationSessionDeviceTrustVerifier` → `DeviceTrustDecision.hardwareKeyAttested()`. It added the
factory modes `DEVICE_KEY_ATTESTATION` + `REQUIRE_ENROLLMENT_AND_DEVICE_KEY`. #732's own body states it does
**not** close #548 (no live TPM material, no seeded device-attestation roots, no live positive/negative evidence).

This is precisely the AgentHello-carried shape **§0 rejected** for the canonical path (first message, no broker
nonce → replayable). Reconciliation (Codex decision authority, CLAUDE.md §8):

- **Canonical #548 strong path stays this TPM-native challenge-response** (PR #741 wire-contract + the
  `DEVICE_KEY_ATTESTATION_REAL` verifier, §2). It is **not** retracted.
- **#732 is NOT wholesale-reverted.** Its `SessionDeviceTrustVerifier` seam + the CA-envelope parser are kept as
  an **auxiliary, non-live, CA-static** path — valuable later for multi-platform (Android/Apple/MDM) CA
  attestation, behind its own broker-challenge protocol if it ever goes production.
- **Quarantine (guard PR, this PR):** the CA-static composite `REQUIRE_ENROLLMENT_AND_DEVICE_KEY` is **REFUSED in
  a production-like profile** until the live `DEVICE_KEY_ATTESTATION_REAL` verifier backs the composite's hardware
  leg. This closes a **latent false-acceptance**: the composite's "hardware" leg promoted the non-live,
  replay-prone `peerTrust.deviceTrusted`, which in a prod-like profile would have read as production-grade
  hardware device trust.
- **The two models must never collapse under one `deviceTrusted` boolean.** The TPM-native flow uses its own slot
  (`TpmDeviceKeySessionAttestation`, §5 step 2) + store + the `DEVICE_KEY_ATTESTATION_REAL` verifier; it does
  **not** consume `peerTrust.deviceTrusted` (the CA-static boolean).
- **Critical verifier invariant (Codex):** an EK-cert chaining to a trusted root does **not** by itself prove the
  AK belongs to that EK/TPM. The `DEVICE_KEY_ATTESTATION_REAL` verifier must bind AK↔EK either by matching the
  session response against the **stored enrollment TPM record** (the enrollment `MakeCredential/ActivateCredential`
  already proved AK↔EK at V10) or by a fresh session-time credential activation. "AK signed certify/quote + EK
  chains to root" alone is insufficient.

## 7. Status + next-session P0 (2026-06-24, session `b8c81a4c`)

**Done this session (all Codex `019efada` AGREE, cross-AI):** PR #741 (wire-contract) · #743 (reconciliation
guard + the latent false-acceptance fix) · #744 (TPM-native evidence mapper) · #746 (challenge store). §5 steps
1-3 + the guard are merged. Nothing yet establishes device trust at runtime — by design, each is a fail-closed
foundation slice.

**Resume at §5 step 4 — the AK↔EK prerequisite (Codex decision A), then step 5 (verifier), then step 6 (agent):**
1. **§5 step 4 — TPM enrollment binding persistence** (the blocking prerequisite). New entity + repository +
   Flyway migration + wiring into `TpmEnrollmentCompletionService` (transactional with `markConsumed`). This is
   a DB + enrollment-path change, not a remote-bridge change — design the entity per §5 step 4, cross-AI review.
2. **§5 step 5 — the verifier** (`DEVICE_KEY_ATTESTATION_REAL`) in the exact order in §5 + the factory mode
   (`SessionDeviceTrustVerifierFactory` — the prod gating the §6 guard reserved) + the composite hardware leg +
   the full negative matrix.
3. **§5 step 6 — agent (Go)** in `platform-agent` (cross-repo): vendored-proto regen + response production +
   EK-cert NV-read.

**Why handed off here:** the prerequisite + verifier are the security-critical crux — a wrong AK↔EK binding is
*fake* hardware attestation, exactly what #548 must never ship. Codex (decision authority) explicitly sanctioned
a clean handoff at this point rather than rush the crypto + DB migration in a context-loaded tail. The full
resolution is in Codex thread `019efada`. Rejected alternatives: **(B)** a session-time `MakeCredential`/
`ActivateCredential` is deferred — it is cryptographically clean but a larger wire/control-flow extension than
reusing the enrollment-time V10 proof; **(C)** a bounded-lab path may exist only as a non-prod diagnostic and
must NOT produce a strong marker or production `hardwareKeyAttested()`.
