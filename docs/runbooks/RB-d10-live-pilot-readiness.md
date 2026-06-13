# RB — Faz 22.6 D10 Live-Pilot Readiness Pack v0 (turnkey owner guide)

> **Amaç:** the 3 live-pilot **authority CODE blockers are closed** (#626 operator JWT auth + #627 approval→owner-grant + #628 duress pilot-policy). This pack is the **turnkey guide for the OWNER** to activate the broker + run the first live attended pilot. It separates what is **✅ code-ready (agent-done)** from **🔒 owner-config** and **🔒 owner-execution**.
>
> **Companion:** [RB-d10-acceptance-evidence-map.md](./RB-d10-acceptance-evidence-map.md) (the 14 D10 acceptance criteria). **Owner decision:** ADR-0034 §11/§13 (owner-signed 2026-06-11, #1388 CLOSED, PR #1444). **Broker scaffold:** gitops #1483 (`edfb34d8`).

---

## 0. Status snapshot (2026-06-13)

- ✅ **Approval WRITE-PATH code-complete** — operator JWT auth (#626) + dual-control approval→grant (#627) + duress pilot-policy (#628) + approval-chain wiring (#630) + approval REST endpoint (#631) + write-path hardening/audit (#632); plus the operator REST transport (slice-4c). All Codex-AGREE'd. With the owner opting each in (§2), an approver records a dual-control approval.
- ⚠️ **Full operation-PERMIT is NOT config-only achievable yet** (Codex 019ec25c). With the current code the broker correctly **fail-closes** (DENY/KILL) on three real gaps — **tracked in #634** (broker PERMIT enablement, reference-trust): (1) signer/anchor config (permit `kid` + recording anchor key as a file mount), (2) consent→ACTIVE lifecycle, (3) reference trust-evidence parser/verifier (NOT a synthetic-trust bypass). So a config-only activation proves **Up + mTLS transport + approval write-path + secured-negatives** (the operation fail-closing is correct evidence); full Functional-PERMIT awaits #634.
- ✅ **4-role KVKK signed** (owner 2026-06-11, #1388 CLOSED, PR #1444 — D1 legitimate-interest+contract, D3 mandatory fail-closed recording, D6 attended-only, D8 narrow view+PTY).
- ✅ **Broker isolation scaffold** (gitops #1483: separate Deployment + SA + ExternalSecret + NetworkPolicy + egress ACL + namespace isolation; activation overlay `kustomize/overlays/test/activation/endpoint-admin-remote-bridge/` NOT wired into the synced overlay).
- 🔒 **Remaining:** owner activation (config + Vault + Keycloak) + physical pilot PCs + the attended run. **Legal-counsel review recommended pre-prod (not a hard gate for the narrow pilot).**

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
```

> ⚠️ **Each line is a deliberate, owner-accepted reduction for the narrow pilot.** `APPROVAL_BACKED_IN_MEMORY` and `PILOT_RISK_ACCEPTED_DISABLED` are **code-forbidden in a production-like profile** — the activation profile MUST be non-prod. Omitting any line leaves that gate at its fail-closed default.

---

## 3. Owner-input contract

| Input | Detail |
|---|---|
| **Keycloak realm role** | create `remote-bridge-operator` (realm role) |
| **Keycloak audience** | a client/audience the operator token carries as `aud` = the bridge audience (so a main-app token can't be replayed) |
| **Keycloak `tenant_id` mapper** | a protocol mapper emitting the operator's tenant as a canonical-UUID `tenant_id` claim |
| **Roster role-assignment** | assign `remote-bridge-operator` to the named-roster operators (only) |
| **Vault** | the broker's secrets per the #1483 ExternalSecret (recording-anchor key, etc.) |
| **Activation overlay** | `kubectl apply -k kustomize/overlays/test/activation/endpoint-admin-remote-bridge/` (gitops #1483) |
| **Pilot PCs** | 2-5 IT-owned Windows, agent-enrolled (cert-bound) |
| **Named roster (D7)** | the pilot operators + endpoint-users (the attended participants) |

---

## 4. D29-EA smoke skeleton (the AGENT runs this after owner activation)

| Layer | Proves | How |
|---|---|---|
| **Up** | broker pod Running + 9444 mTLS listener + imageID digest match | `kubectl get pod` + TLS probe |
| **Functional-transport** | synthetic device mTLS CONTROL stream + AgentHello/heartbeat → operator JWT `openSession` → consent prompt/result → approval-REST records a grant | end-to-end transport + the approval write-path |
| **Functional-PERMIT** | the operation call returns **PERMIT** + a signed permit is pushed | **🔒 blocked by #634** (lifecycle + signer/anchor + reference trust-parser); until then the operation correctly **DENY/KILL**s (fail-closed) |
| **Secured** | no/wrong client cert → refused; plaintext refused; wrong CA / wrong device fingerprint; operator without the role → 401; cross-tenant → uniform 404; no approval grant → DENY; ambiguous duress → KILL; 8096/8081 off-path | the negative tests exercised live (all fail-closed) |
| **Does NOT prove** | real duress detection (disabled, risk-accepted); TPM/HSM non-exportable; CRL/OCSP; durable grant store; production-grade attestation | (post-pilot / owner-gated) |

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

**🔒 Post-pilot (owner-gated live slices):**
- **Durable grant store** + **durable/WORM approval-audit sink** (#632 wired the seam) + **E1 kill→live-grant-revoke** — the in-memory store + TTL + app-log audit is the pilot; durable/OpenFGA-backed store + WORM sink + a live revoke backstop are post-pilot.

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
