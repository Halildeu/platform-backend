# Failed-Device Queue Contract — Faz 22.5 (50→800 rollout)

> **Status:** v1 CONTRACT GATE (spec only). **This document defines the
> contract; it does NOT operate a live queue.** Live ingestion, threshold
> enforcement, and generated GitHub issues are **deferred to follow-up
> implementation issues** (see §9). Claiming any of those is "enforced" before
> the corresponding backend component lands is a contract violation.
>
> **Issue:** platform-backend#520 · **Track:** backend · **Priority:** P0 (gate)
> **Source:** `docs/faz-22-software-deployment-plan.md` §0.5.5 + §0.5.9
> **Unblocks:** M6/M7 rollout expansion · **Cross-AI design:** Codex `019eaac8` (AGREE)

## 1. Problem & decision

Today a failed device surfaces only as a command's `last_error` string — there
is no auditable queue, classification, retry/quarantine/escalation state, or
wave-artifact export. For a 50→800 endpoint-agent rollout that makes failures
ad-hoc support tickets and leaves rollout state unauditable and un-stop-lineable.

**Architecture decision — HYBRID (Codex 019eaac8):**

| Concern | Owner |
|---|---|
| Queue state, evidence, classification, retry, thresholds, export, **audit source-of-truth** | **Backend** (`endpoint_rollout_failure` + event ledger) |
| Human escalation workflow | **GitHub issue** — a *projection only*, generated from an `escalated` backend item; the issue body carries a **redacted snapshot**, never canonical state |

Rejected alternatives:
- **GitHub-only:** stop-line thresholds must be COMPUTED from live heartbeat/
  command/result/enrollment data (backend has it); labels can drift and are not
  computable; redaction can't be enforced by labels. ✗
- **Backend-only:** leaves operators without a human escalation surface. ✗

## 2. Data model (system-of-record)

**`endpoint_rollout_failure`** — one **active aggregate** per device per wave:

```
id, org_id, rollout_id, wave_id, device_id,
current_class, current_state, retry_count, max_retries,
first_detected_at, last_observed_at, last_transition_at,
evidence_redacted_json, owner_role, stop_line_contribution,
escalation_issue_url, waiver_reason, waived_by, waived_until,
resolved_at, resolution_summary, classification_confidence,
classifier_version, version (optimistic lock)
```

**`endpoint_rollout_failure_event`** — **append-only** audit ledger:

```
id, failure_id, event_type, from_state, to_state, class,
source_signal, redacted_evidence_json, actor_type,
actor_subject_hash, classification_confidence, created_at
```

(Optional later) **`endpoint_rollout_wave_snapshot`** — immutable computed
threshold snapshot for export reproducibility.

### Keying (Codex §A)

One **active** aggregate per `(org_id, rollout_id, wave_id, device_id)`, PLUS the
append-only event ledger. NOT pure-`device_id` (destroys wave/rerun history) and
NOT pure-append-only (makes operator workflow + stop-line queries error-prone).

```sql
UNIQUE (org_id, rollout_id, wave_id, device_id)
  WHERE current_state IN ('new','retrying','quarantined','escalated')
```

Resolved/waived rows remain queryable as history. A device that fails DNS then
HMAC keeps ONE active item (`current_class` reflects the latest); prior classes
are preserved in the event ledger. A device re-entering a later wave gets a NEW
item under the new `wave_id`. Org-scoped per the Faz 21.1 org_id canonical model.

## 3. Failure classes (fixed enum)

All evidence fields are **allowlisted, redacted**. No raw `last_error`, JWT,
bearer, full SID, full UPN, raw MSI log, raw cert PEM, or raw IP unless policy
explicitly permits. Free-text evidence is forbidden (see §7 redaction).

| Class | Required redacted evidence | First-action owner |
|---|---|---|
| `DNS_EDGE_MTLS` | endpoint_host_hash, edge_target, dns_error_code, tls_alert, mtls_peer_cert_fingerprint_prefix, observed_at, source | platform/edge operator |
| `CERT_IDENTITY` | device_id, cert_fingerprint_prefix, issuer_id, subject_san_hash, enrollment_status, cert_not_before, cert_not_after, audit_event_id | identity/PKI operator |
| `INSTALLER_MSI` | device_id, msi_product_code, msi_exit_code, agent_version, installer_phase, log_excerpt_redacted, gpo_assignment_id | endpoint packaging / IT desktop |
| `SERVICE_HMAC_MODE` | device_id, service_state, agent_mode, hmac_error_code, last_heartbeat_at, command_id, agent_version | endpoint-agent / backend operator (by code) |
| `BACKEND_RESULT_SUBMIT` | device_id, command_id, result_submit_http_status, backend_error_code, request_id, received_at, idempotency_key_hash | backend endpoint-admin owner |
| `EDR_NETWORK` | device_id, network_segment_id, edr_vendor, blocked_process_hash_prefix, blocked_destination, firewall_rule_id, last_successful_contact_at | IT security / network |

## 4. State machine

States: `new` (detected, not actioned) · `retrying` (retry budget active) ·
`quarantined` (held out of expansion; needs isolation) · `escalated` (human
workflow opened, usually a generated issue) · `resolved` (no longer blocks) ·
`waived` (accepted exception with reason/owner/expiry).

Allowed transitions (every transition writes an event row — no silent changes):

```
new       -> retrying | quarantined | escalated | resolved | waived
retrying   -> retrying | quarantined | escalated | resolved | waived
quarantined-> retrying | escalated | resolved | waived
escalated  -> retrying | quarantined | resolved | waived
resolved   -> new      (ONLY on a new observation after resolution / reopen)
waived     -> new      (ONLY after waiver expiry or explicit revoke)
```

Any transition not in this table is rejected. `resolved`/`waived` are not
terminal-immutable: a fresh failing observation reopens to `new` (audited).

## 5. Retry policy (per-class catalog)

Each class+error-code carries: `retryable: true|false|conditional`,
`max_retries`, `backoff`, `terminal_state_after_exhaustion`, `first_action_owner`.

| Class | max_retries | Notes |
|---|---|---|
| `DNS_EDGE_MTLS` | 3, exp backoff | suspected mTLS identity mismatch → immediate `quarantined` |
| `CERT_IDENTITY` | 1 (after cert refresh / re-enroll) | else `quarantined` or `escalated` |
| `INSTALLER_MSI` | 2 | only if exit code is known-retryable; else `escalated` |
| `SERVICE_HMAC_MODE` | 2 (after heartbeat/config refresh) | HMAC integrity uncertain → `quarantined` |
| `BACKEND_RESULT_SUBMIT` | 3 (idempotency-keyed) | 4xx policy/auth NOT blindly retryable |
| `EDR_NETWORK` | 0–1 | backend cannot remediate policy blocks → normally `escalated` |

## 6. Stop-line thresholds (backend-COMPUTED; enforcement deferred)

Status is `stop_line_status ∈ {clear, stop_expansion, required_review}`.
**STOP expansion ≠ rollback** — it pauses adding new waves/devices, it does not
auto-fail the deployment.

```
active_wave_size   = devices currently assigned to the active wave, in scope
wave_failed_count  = active items in {new,retrying,quarantined,escalated} for the wave
wave_failed_percent = wave_failed_count / active_wave_size * 100
=> stop_expansion if wave_failed_percent > 5

fleet_size         = rollout-target fleet size (e.g. 800)
stale_24h_count    = devices with last_heartbeat_at < now-24h (or no heartbeat past
                     the enrollment deadline), EXCLUDING decommissioned / out-of-scope /
                     valid-waiver
stale_24h_percent  = stale_24h_count / fleet_size * 100
=> stop_expansion if stale_24h_percent > 2
```

> **Deferred:** the threshold EVALUATOR is a follow-up. This contract only
> DEFINES the formulas; it must not be cited as "enforced" until §9.3 lands.

## 7. Classification & redaction

`classification_confidence ∈ {high, medium, low}` + `classifier_version`.
`auto_classified` vs `manual_classified`. Low-confidence auto-classification MAY
create `new`, but must NOT auto-waive or auto-resolve.

**Auto-classifiable signals (v2 ingest):** command result error codes
(INSTALLER_MSI / SERVICE_HMAC_MODE / BACKEND_RESULT_SUBMIT), command lifecycle
(queued-too-long, delivered-no-result, repeated transient), heartbeat
(stale/offline, version/mode mismatch), cert/enrollment audit (issued/rejected/
expired/SAN-mismatch/duplicate-identity), result-submit HTTP telemetry, edge/mTLS
logs (TLS alert, peer-cert reject, DNS class), agent diagnostics (service state,
MSI code, EDR hints).

**Manual / manual-confirmed in v1–v2:** EDR/network exact root cause (unless a
structured diagnostic exists), installer logs beyond the MSI exit code, ambiguous
DNS-vs-VPN-vs-firewall, waiver decisions, quarantine/escalation owner override,
class reclassification on conflicting signals.

**Redaction (hard rule, mirrors the existing command-result redaction policy):**
evidence is an **allowlist of named fields** (§3), enforced by the JSON schema's
per-class `oneOf` + `additionalProperties:false`. Arbitrary evidence JSON is
forbidden — it would eventually leak raw logs, tokens, hostnames, cert material,
or user identifiers.

## 8. Export / escalation surface

- **Wave-failure export** (`wave-failure-export.example.json`): per-wave summary
  + per-class counts + `stop_line_status` + redacted item samples + escalation
  issue-link references. Reproducible from a wave snapshot.
- **GitHub escalation template** (`github-escalation-issue-template.md`):
  generated for an `escalated` item; carries the redacted snapshot + the
  requested first action; states explicitly that **the canonical state is the
  backend queue item**, not the issue.

## 9. Deferred implementation (follow-up issues — NOT in this contract gate)

This contract is the GATE. The following are explicitly **not yet built** and
must be cited as deferred wherever the contract is referenced:

1. Backend Flyway tables (`endpoint_rollout_failure` + `_event`) + read/export REST.
2. Ingest/classifier from command/heartbeat/enrollment signals.
3. Stop-line threshold evaluator + `stop_line_status` computation.
4. GitHub escalation issue generator.
5. UI/reporting surface (if needed).

## 10. Honesty guards (contract self-check)

A reference to this contract is **dishonest/unenforceable** if it:
- says "STOP expansion enforced" before §9.3 lands (correct: "defined; enforcement deferred");
- treats GitHub labels as canonical queue state;
- keeps only raw `last_error` strings (fails redaction/classification/audit/retry);
- omits the append-only event ledger (state changes unauditable);
- allows arbitrary evidence JSON without the §3/§7 allowlist;
- keys only by global `device_id` (destroys wave/rerun history);
- uses pure append-only without the active aggregate (breaks operator workflow + stop-line queries);
- auto-classifies `EDR_NETWORK` high-confidence from a generic timeout string.
