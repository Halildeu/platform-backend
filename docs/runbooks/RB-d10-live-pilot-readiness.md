# RB — Faz 22.6 D10 Live-Pilot Readiness Pack v0 (turnkey owner guide)

> **Amaç:** the 3 live-pilot **authority CODE blockers are closed** (#626 operator JWT auth + #627 approval→owner-grant + #628 duress pilot-policy). This pack is the **turnkey guide for the OWNER** to activate the broker + run the first live attended pilot. It separates what is **✅ code-ready (agent-done)** from **🔒 owner-config** and **🔒 owner-execution**.
>
> **Companion:** [RB-d10-acceptance-evidence-map.md](./RB-d10-acceptance-evidence-map.md) (the 14 D10 acceptance criteria). **Owner decision:** ADR-0034 §11/§13 (owner-signed 2026-06-11, #1388 CLOSED, PR #1444). **Broker scaffold:** gitops #1483 (`edfb34d8`).

---

## 0. Status snapshot (2026-06-13)

- ✅ **Authority CODE path complete** — operator JWT auth + dual-control approval→grant + duress pilot-policy (all Codex-AGREE'd, thread `019ebe06`).
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
| **Up** | broker pod Running + 9444 reachable | `kubectl get pod` + TCP probe |
| **Functional** | an operator JWT (Keycloak, with the role) authenticates → a dual-control approval records a grant → the broker PERMITs view/PTY | end-to-end with a roster operator + approver |
| **Secured** | operator without the role → 401; cross-tenant → denied; expired/replayed token → uniform DENY; recording fail-closed; revoke→kill-within-SLO | the negative tests exercised live |
| **Does NOT prove** | real duress detection (disabled, risk-accepted); TPM/HSM non-exportable; CRL/OCSP; durable grant store | (post-pilot / owner-gated) |

---

## 5. The attended pilot run (owner-executed; agent captures evidence)

**Envelope (the D8 / risk-acceptance boundary):** named-roster + attended-only + IT-owned + no-file-transfer + view+constrained-PTY only + duress-disabled-risk-accepted.

**Flow:** operator requests a session → **approver approves** (dual-control, distinct identity) → **endpoint-user consents** → operator **views / constrained-PTY** → session **ends or is killed** → **audit + recording** (record-before-permit).

**Agent captures:** D29-EA evidence (above) + browser smoke (HARD RULE: end-to-end browser-verified) + the [14 D10 criteria](./RB-d10-acceptance-evidence-map.md) checklist.

---

## 6. Deferred consumers (next agent slices, owner-gated to activate)

- **Approval REST endpoint** — the approver's console calls `RemoteSessionApprovalRecorder` (slice-2) with the **server-side authenticated approver identity** (its own JWT). MUST NOT leak the `DENIED_*` reasons as an external oracle. This is the write path that fills the grant store.
- **Operator REST transport** (slice-4c) — gated by `operator-rest.enabled`; the operator console connects through it.
- **Durable grant store** + **E1 kill→live-grant-revoke** — the in-memory store + TTL is the pilot; a durable/OpenFGA-backed store + a live revoke backstop are post-pilot.

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
