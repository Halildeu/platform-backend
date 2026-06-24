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
  bytes  nonce = 2;                    // 32 random bytes
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
1. **Backend (source-of-truth):** proto (the 2 CONTROL payloads) + domain records (`DeviceKeyChallenge`,
   `DeviceKeySessionAttestationV1`) + wire adapters (decode + re-validate, fail-closed) + directional
   allowlists + round-trip/byte tests. **No verifier yet.**
2. **Backend:** parser populates `ParsedEvidence.deviceKey` from a validated response (still no trust set).
3. **Backend:** challenge issuance + TTL store + single-use replay-cache.
4. **Backend:** `DEVICE_KEY_ATTESTATION_REAL` verifier (§2) + factory mode + profile gating + negative matrix.
5. **Agent (Go):** regenerate vendored proto; produce the `DeviceKeyAttestationResponse` (fresh Certify/Quote
   + device-key sig over binding context) from `internal/tpmenroll`; sender allowlist; finish EK-cert NV-read
   for the strong path. Gated, default-off.
6. **Live run + marker:** real-hardware (or labeled bounded-lab) end-to-end → broker `device=true` →
   `RB-faz22.6-548-hardware-attestation-gap-audit.md` strong marker.

> Do NOT ship a field-7-only / AgentHello-carried variant — it would re-open the "source proof but no session
> freshness" finding. Steps 1-5 are agent-doable; step 6 needs operator (live Vault) + real-hardware + owner sign.
