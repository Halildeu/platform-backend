# RB — Faz 22.6 D10 Live-Pilot Readiness Pack v0 (turnkey owner guide)

> **Amaç:** the 3 live-pilot **authority CODE blockers are closed** (#626 operator JWT auth + #627 approval→owner-grant + #628 duress pilot-policy). This pack is the **turnkey guide for the OWNER** to activate the broker + run the first live attended pilot. It separates what is **✅ code-ready (agent-done)** from **🔒 owner-config** and **🔒 owner-execution**.
>
> **Companion:** [RB-d10-acceptance-evidence-map.md](./RB-d10-acceptance-evidence-map.md) (the 14 D10 acceptance criteria). **Owner decision:** ADR-0034 §11/§13 (owner-signed 2026-06-11, #1388 CLOSED, PR #1444). **Broker scaffold:** gitops #1483 (`edfb34d8`).

---

## 0. Status snapshot (2026-06-14)

- ✅ **Broker PERMIT path — COMPOSITION PROVEN** (#641 e2e, Codex 019ec29a). The full real trust chain composes into a **real signed broker PERMIT**: transport-bound cert evidence (3a #637) + machine-cert enrollment device trust (3b #638) + `KEY_BASED` attestation + a real WebAuthn step-up + consent→ACTIVE (#636) + the approval-backed grant + clean-duress + durable audit + the signer. A 12-case inverse matrix proves **no single gate is bypassable** (remove any → DENY/KILL), and freshness/revoke/policy-rotation semantics are proven (3c #642). So the broker PERMIT **code path works** — what remains for a *live* PERMIT is **live trust material + the signer/anchor key**, NOT missing code. (This supersedes the earlier "NOT config-only achievable / three gaps" note — consent→ACTIVE and the reference parser/verifier gaps are now CLOSED; only the signer/anchor key + live material remain.)
- ✅ **Approval WRITE-PATH code-complete** — operator JWT auth (#626) + dual-control approval→grant (#627) + duress pilot-policy (#628) + approval-chain wiring (#630) + approval REST endpoint (#631) + write-path hardening/audit (#632) + the operator REST transport (slice-4c). All Codex-AGREE'd. With the owner opting each in (§2), an approver records a dual-control approval.
- ✅ **4-role KVKK signed** (owner 2026-06-11, #1388 CLOSED, PR #1444 — D1 legitimate-interest+contract, D3 mandatory fail-closed recording, D6 attended-only, D8 narrow view+PTY).
- ✅ **Broker isolation scaffold** (gitops #1483: separate Deployment + SA + ExternalSecret + NetworkPolicy + egress ACL + namespace isolation; activation overlay `kustomize/overlays/test/activation/endpoint-admin-remote-bridge/` NOT wired into the synced overlay).
- 🔒 **Remaining for a LIVE PERMIT (owner-gated):** (1) the **signer/anchor key** — the permit `kid` + the recording-anchor key as a file mount (Vault custody); (2) **live trust material** — a real device-PKI cert chain (the `REAL_PKI` anchor) + a real `KEY_BASED` attestation provenance key + a real enrolled machine-cert row + a real WebAuthn operator credential; (3) the complete **§2 activation config** (below); (4) physical pilot PCs + the attended run. **Legal-counsel review recommended pre-prod (not a hard gate for the narrow pilot).**

---

## 1. Authority gap matrix

| Component | Status | What unlocks it |
|---|---|---|
| Operator auth (JWT/Keycloak) | ✅ code (#626) | Keycloak: `remote-bridge-operator` realm role + bridge audience + `tenant_id` claim mapper + roster role-assignment |
| Owner-grant (approval-backed) | ✅ code (#627) | the approval REST endpoint (deferred consumer — §6) records a dual-control approval |
| Duress (pilot-disabled) | ✅ code (#628) | set `pilot-risk-accepted=true` (non-prod only) |
| Operator REST transport | ✅ code (slice-4c) | `remote-bridge.operator-rest.enabled=true` |
| Recording / WORM | ✅ code (#591) | Vault recording-anchor key + WORM object-lock storage |
| mTLS + cert-bound token | ✅ code (T-2c/B1.1) | agent device-PKI certs (machine channel) |
| WebAuthn per-action step-up | ✅ code (D step-up) | operator WebAuthn public key (config) |
| Transport-bound cert evidence (3a) | ✅ code (#637) | `peer-evidence.parser=TRANSPORT_BOUND` + a `REAL_PKI` cert anchor (§2) |
| Machine-cert enrollment device trust (3b) | ✅ code (#638) | `device-trust.verifier=MACHINE_CERT_ENROLLMENT` + an enrolled machine-cert row |
| `KEY_BASED` attestation + builder-revoke / policy-rotation (3a/3c) | ✅ code (#637/#642) | `attestation.verifier=KEY_BASED` + the provenance key/builder/policy (§2) |
| **PERMIT composition** | ✅ **proven** (#641 e2e) | the §2 trust-substrate config + the signer/anchor key + live material |
| **Signer/anchor key** | 🔒 owner | the permit signing key + the recording-anchor key (Vault file mounts — §2) |
| **Broker deploy (D29-EA O4)** | 🔒 owner | activate the overlay (#1483) + seed Vault secrets |
| **Endpoint enrollment** | 🔒 owner | enroll 2-5 pilot PCs |
| **Live producers** (real duress, TPM attestation, CRL/OCSP) | 🔒 owner / deferred | pilot uses disabled-duress (risk-accepted); the rest are post-pilot |

---

## 2. Activation config (test-cluster, NON-PROD)

The broker is disabled-by-default; **every opt-in is explicit** and the defaults (without these) are fail-closed (DENY/KILL). Set on the **activation Deployment only** (`endpoint-admin-remote-bridge`, #1483), never the primary `endpoint-admin-service`:

```properties
# --- broker + operator transport ---
remote-bridge.enabled=true
remote-bridge.operator-rest.enabled=true

# --- slice-1: operator JWT auth (Keycloak) ---
remote-bridge.operator-auth.type=JWT_BEARER
remote-bridge.operator-auth.jwt.jwk-set-uri=<Keycloak realm JWKS URI>
remote-bridge.operator-auth.jwt.issuer=<Keycloak realm issuer URI>
remote-bridge.operator-auth.jwt.audience=<the bridge audience>
remote-bridge.operator-auth.jwt.tenant-claim=tenant_id           # canonical-UUID claim
remote-bridge.operator-auth.jwt.subject-claim=sub
remote-bridge.operator-auth.jwt.role-claim-path=realm_access.roles
remote-bridge.operator-auth.jwt.required-operator-role=remote-bridge-operator

# --- slice-2: approval-backed owner grant (non-prod in-memory) ---
remote-bridge.owner-grant.gate-type=APPROVAL_BACKED_IN_MEMORY

# --- slice-3: duress disabled for the pilot (non-prod, owner-risk-accepted) ---
remote-bridge.duress.source-type=PILOT_RISK_ACCEPTED_DISABLED
remote-bridge.duress.pilot-risk-accepted=true

# --- approval write-path (#630 chain + #631 endpoint): the approver records a dual-control approval ---
remote-bridge.approval-rest.enabled=true
# explicit principal→canonical mapping (every roster operator + approver; no pass-through):
remote-bridge.approval.canonical-identity.<operator-principal>=<canonical-subject>
remote-bridge.approval.canonical-identity.<approver-principal>=<canonical-subject>
# tenant-scoped grants (canonical-UUID tenants; a principal granted in a tenant may act on ANY session there):
remote-bridge.approval.grants.can-request.<operator-principal>[0]=<tenant-uuid>
remote-bridge.approval.grants.can-approve.<approver-principal>[0]=<tenant-uuid>
remote-bridge.approval.fatigue.max-per-window=5
remote-bridge.approval.fatigue.window-millis=3600000
remote-bridge.approval.grant-ttl-millis=300000

# --- broker machine-channel mTLS — REQUIRED for an enabled bridge (the agent CONTROL stream needs it; an enabled
#     server FAIL-CLOSES at startup without all three). Typically provided by the #1483 deployment ExternalSecret/
#     overlay — set here only if the activation overlay does not already mount them ---
remote-bridge.tls.cert-chain-pem-path=<broker TLS server cert chain PEM file>
remote-bridge.tls.private-key-pem-path=<broker TLS server private key PEM file>
remote-bridge.tls.client-ca-pem-path=<the agent/device client CA PEM file>
# remote-bridge.port=9444                       # default
# remote-bridge.allow-insecure-plaintext=false  # MUST stay false for the pilot (loopback-only test escape)

# --- 3a: transport-bound peer-evidence parser (non-prod) — builds the CertRef from the mTLS transport leaf,
#         so the cert/attestation verifiers below actually run (default FAIL_CLOSED → no evidence → never PERMIT) ---
remote-bridge.peer-evidence.parser=TRANSPORT_BOUND

# --- 3b: machine-cert enrollment device trust (non-prod) — deviceTrusted = the live peer IS the active enrolled
#         machine cert for the tenant/device (default FAIL_CLOSED → deviceTrusted false → never PERMIT) ---
remote-bridge.device-trust.verifier=MACHINE_CERT_ENROLLMENT

# --- cert trust (REAL_PKI): the agent device-PKI chain must build to this anchor (certTrusted). The DISABLED +
#     allow-insecure-no-revocation pair is NON-PROD ONLY (prod MUST use revocation-mode=CRL with a real CRL) ---
endpoint-admin.remote-access.cert-trust.evaluator=REAL_PKI
endpoint-admin.remote-access.cert-trust.trust-anchor-pem=<the device-CA root PEM bundle>
endpoint-admin.remote-access.cert-trust.revocation-mode=DISABLED
endpoint-admin.remote-access.cert-trust.allow-insecure-no-revocation=true

# --- attestation (KEY_BASED): the agent's SLSA build-provenance must verify under this key + builder/policy
#     (attestationVerified). IN_MEMORY is a code-forbidden placeholder in prod — use KEY_BASED with a real key ---
endpoint-admin.remote-access.attestation.verifier=KEY_BASED
endpoint-admin.remote-access.attestation.expected-builder-id=<the trusted builder id>
endpoint-admin.remote-access.attestation.expected-policy-hash=<the expected SLSA policy hash>   # = the agent's slsaPredicateHash (3rd provenance field)
endpoint-admin.remote-access.attestation.public-key-pem=<the provenance signing PUBLIC key PEM>

# --- operator per-action step-up (WebAuthn) — REQUIRED: even SCREEN_VIEW needs a fresh USER_PRESENCE step-up;
#     the JWT operator-auth above is identity only, NOT the per-action step-up. Without this → DENY policy:STEP_UP ---
remote-bridge.step-up.verifier=WEBAUTHN
remote-bridge.step-up.public-key-pem=<the operator's WebAuthn credential PUBLIC key PEM>
remote-bridge.step-up.expected-origin=<the operator console origin, e.g. https://operator.acik.com>
remote-bridge.step-up.expected-rp-id=<the WebAuthn RP id, e.g. operator.acik.com>
remote-bridge.step-up.signature-algorithm=SHA256withECDSA   # default; explicit for the owner
remote-bridge.step-up.challenge-ttl-millis=120000           # default

# --- the re-verify freshness TTL (slice-3c) — a recorded peer-trust older than this is dropped → re-verify ---
remote-bridge.peer-trust.freshness-ttl-millis=30000
# (remote-bridge.peer-trust.device-ca-pem is for FUTURE hardware device-key attestation ONLY — OMIT for the
#  MACHINE_CERT_ENROLLMENT pilot; device trust here is the DB machine-cert enrollment binding, not hardware keys)

# --- the signer/anchor key (the LAST gap — Vault-mounted file paths; a broker that cannot sign refuses to start).
#     BOTH keys MUST be PKCS#8 "BEGIN PRIVATE KEY", NOT SEC1 "BEGIN EC PRIVATE KEY" ---
remote-bridge.permit.signing-key-pem-path=<PKCS#8 EC P-256 permit-signing private key file (Vault-mounted)>
remote-bridge.permit.kid=<the permit key id the agents pin>
remote-bridge.recording.anchor-key.path=<PKCS#8 EC recording-anchor private key file (Vault-mounted)>
remote-bridge.recording.anchor-key.algorithm=SHA256withECDSA   # default
```

> ⚠️ **Each line is a deliberate, owner-accepted reduction for the narrow pilot.** `APPROVAL_BACKED_IN_MEMORY`, `PILOT_RISK_ACCEPTED_DISABLED`, `TRANSPORT_BOUND`, `MACHINE_CERT_ENROLLMENT`, and `REAL_PKI`+`revocation-mode=DISABLED` are **code-forbidden in a production-like profile** — the activation profile MUST be non-prod. Omitting any line leaves that gate at its fail-closed default (and without the trust-substrate + signer keys the broker correctly **never PERMITs** — proven by the #641 composition e2e + its 12-case inverse matrix).

---

## 3. Owner-input contract

| Input | Detail |
|---|---|
| **Keycloak realm role** | create `remote-bridge-operator` (realm role) |
| **Keycloak audience** | a client/audience the operator token carries as `aud` = the bridge audience (so a main-app token can't be replayed) |
| **Keycloak `tenant_id` mapper** | a protocol mapper emitting the operator's tenant as a canonical-UUID `tenant_id` claim |
| **Roster role-assignment** | assign `remote-bridge-operator` to the named-roster operators (only) |
| **Vault — signer/anchor keys** | the permit signing key + the recording-anchor key (PKCS#8 EC; per the #1483 ExternalSecret) |
| **Broker mTLS material** | the broker TLS server cert chain + key + the agent/device client CA (the machine channel — via the #1483 deployment secret) |
| **Device-PKI anchor (REAL_PKI)** | the device-CA root PEM the agent cert chain builds to (`cert-trust.trust-anchor-pem`) |
| **Provenance signing key + builder/policy** | the SLSA provenance signing PUBLIC key + the expected builder id + policy hash (`attestation.*`) |
| **Operator WebAuthn credential** | the operator's WebAuthn PUBLIC key + the console origin + RP id (`step-up.*`) — the per-action step-up |
| **Enrolled machine-cert rows** | each pilot PC's agent cert enrolled (active, in-window) so `MACHINE_CERT_ENROLLMENT` resolves it |
| **Activation overlay** | `kubectl apply -k kustomize/overlays/test/activation/endpoint-admin-remote-bridge/` (gitops #1483) |
| **Pilot PCs** | 2-5 IT-owned Windows, agent-enrolled (cert-bound) |
| **Named roster (D7)** | the pilot operators + endpoint-users (the attended participants) |

---

## 4. D29-EA smoke skeleton (the AGENT runs this after owner activation)

| Layer | Proves | How |
|---|---|---|
| **Up** | broker pod Running + 9444 mTLS listener + imageID digest match | `kubectl get pod` + TLS probe |
| **Functional-transport** | synthetic device mTLS CONTROL stream + AgentHello/heartbeat → operator JWT `openSession` → consent prompt/result → approval-REST records a grant | end-to-end transport + the approval write-path |
| **Functional-PERMIT** | the operation call returns **PERMIT** + a signed permit is pushed | ✅ **code path PROVEN** (#641 composition e2e: a real signed PERMIT issues when every gate is satisfied by real evidence). A *live* PERMIT needs the §2 trust-substrate config + the signer/anchor key + real cert/attestation/enrollment/WebAuthn material; with a gap in any, the operation correctly **DENY/KILL**s (fail-closed, proven by the 12-case inverse matrix) |
| **Secured** | no/wrong client cert → refused; plaintext refused; wrong CA / wrong device fingerprint; operator without the role → 401; cross-tenant → uniform 404; no approval grant → DENY; ambiguous duress → KILL; 8096/8081 off-path | the negative tests exercised live (all fail-closed) |
| **Does NOT prove** | real duress detection (disabled, risk-accepted); TPM/HSM non-exportable; CRL/OCSP; live identity producers (OpenFGA/IdP); production-grade attestation | (post-pilot / owner-gated; the durable grant store #645 + WORM approval audit #646 are agent-done + PG-proven) |

---

## 5. The attended pilot run (owner-executed; agent captures evidence)

**Envelope (the D8 / risk-acceptance boundary):** named-roster + attended-only + IT-owned + no-file-transfer + view+constrained-PTY only + duress-disabled-risk-accepted.

**Flow:** operator requests a session → **approver approves** (dual-control, distinct identity) → **endpoint-user consents** → operator **views / constrained-PTY** → session **ends or is killed** → **audit + recording** (record-before-permit).

**Agent captures:** D29-EA evidence (above) + browser smoke (HARD RULE: end-to-end browser-verified) + the [14 D10 criteria](./RB-d10-acceptance-evidence-map.md) checklist.

---

## 6. Consumers (code-complete) + post-pilot slices

**✅ Code-complete (owner activates via the §2 config):**
- **Approval REST endpoint** (#630 chain + #631 controller) — `POST /internal/remote-bridge/approval/sessions/{sessionId}/approve`; the approver's console posts the approved capabilities, authenticated by the approver's OWN JWT (server-side; never a body). Records a dual-control approval → fills the grant store. No `DENIED_*` oracle (all denials → uniform 404); every outcome audited (#632).
- **Operator REST transport** (slice-4c) — gated by `operator-rest.enabled`; the operator console connects through it.

**✅ Post-pilot hardening DONE (agent-completed, opt-in via §2 config):**
- **Durable grant store** (#645) — `owner-grant.gate-type=APPROVAL_BACKED_DURABLE_DB`: the recorded session grant survives restart / multi-replica (replacing the process-local in-memory store, which is prod-forbidden). PG-IT proven.
- **Durable WORM approval-audit sink** (#646) — `approval.audit-sink=DURABLE_WORM_DB`: every approval decision is appended to a WORM table, and the grant + audit commit in ONE DB transaction (no grant without its audit row). PG-IT proven (incl. the rollback invariant). Requires the durable grant store.

**🔒 Genuinely remaining (owner-gated / deferred):**
- **OpenFGA-backed grant/identity resolver, IdP-backed canonical resolver, durable fatigue limiter** — the live identity/authorization producers (the durable grant STORE is done; these are the upstream resolvers).
- **E1 kill→grant-revoke** — DEFENSE-IN-DEPTH, **deferred** (NOT a correctness gap): reuse-after-kill is already structurally closed by the incarnation-keyed grant (`sessionId + tenant + RAW subject + sessionStart`) + the broker's session-active/consent-lease check before the grant is read + terminal-evict (Codex 019ec29a verified). Implement only if a future distributed/durable session-state design introduces a stale-active-session risk, or live pilot shows grant-row cleanup is operationally required.

---

## 7. Risk-acceptance record (capture in the D29 evidence bundle)

The pilot runs with these **deliberate, owner-accepted reductions** — the D29/pilot evidence bundle MUST record each (Codex: the residual risk is operational-evidence, not code-bypass):

- **Duress detection DISABLED** (`source-type=PILOT_RISK_ACCEPTED_DISABLED`, `pilot-risk-accepted=true`, **non-prod active profile**, named-roster/attended/IT-owned/no-file-transfer).
- **In-memory grant store** (process-local; durable store deferred).
- **In-memory approval-fatigue** (durable/distributed deferred).
- **Legal-counsel review pending** — recommended pre-prod; the 4-role KVKK owner sign-off is done; the pilot is limited to the named IT-owned roster. Becomes a hard gate if scope widens (real end-user devices, broader recording access, external/customer data, retention not proven live).

---

## 8. Sequence (owner ↔ agent)

1. **Owner:** Keycloak (role + audience + mapper + roster) → Vault (secrets) → apply the activation overlay (non-prod, with §2 config).
2. **Agent:** D29-EA smoke (Up / Functional / Secured) + browser smoke + evidence bundle.
3. **Owner:** enroll 2-5 pilot PCs + name the roster.
4. **Owner + agent:** run the attended pilot (owner drives the operator/approver/endpoint-user; agent captures the D10 evidence).
5. **D10 acceptance:** the [evidence map](./RB-d10-acceptance-evidence-map.md) 14 criteria + the risk-acceptance record (§7) → D10 11/11.
