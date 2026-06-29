# Failed-Device Queue Contract — Faz 22.5 (50→800 rollout)

> **Status:** v1 RUNTIME CONTRACT. The backend queue, append-only ledger, manual
> seed path, command-result auto-ingest/classifier, heartbeat-stale ingest,
> CERT_IDENTITY ingest from enrollment/cert state, DNS_EDGE_MTLS ingest from
> strict-allowlisted AG-038 diagnostics DNS/TLS state, orchestrator-snapshot
> threshold evaluator, GitHub escalation projection/publish source, and
> canonical `waveFailureReport` export are implemented. Deployment
> gating/enforcement and live GitHub integration configuration are separate
> controls and remain explicitly non-active unless configured externally.
> Claiming rollout enforcement, EDR/firewall root-cause classification, or
> mTLS peer-certificate proof before the matching structured source lands is a
> contract violation.
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

**`endpoint_rollout_wave_metrics_snapshot`** — append-only orchestrator metrics
snapshot carrying `active_wave_size`, `fleet_size`, and `stale_24h_count` so the
backend can compute stop-line status without inventing rollout membership.

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

**v1 scope:** the table below is the **class-level default** policy. A per-
error-code catalog (`retryable: true|false|conditional`, `backoff`,
`terminal_state_after_exhaustion` keyed on the specific error code) is **deferred**
to the ingest follow-up (§9.2) — the schema carries only `retry_count`/`max_retries`
in v1. Do not cite an error-code catalog as existing.

| Class | max_retries | Notes |
|---|---|---|
| `DNS_EDGE_MTLS` | 3, exp backoff | suspected mTLS identity mismatch → immediate `quarantined` |
| `CERT_IDENTITY` | 1 (after cert refresh / re-enroll) | else `quarantined` or `escalated` |
| `INSTALLER_MSI` | 2 | only if exit code is known-retryable; else `escalated` |
| `SERVICE_HMAC_MODE` | 2 (after heartbeat/config refresh) | HMAC integrity uncertain → `quarantined` |
| `BACKEND_RESULT_SUBMIT` | 3 (idempotency-keyed) | 4xx policy/auth NOT blindly retryable |
| `EDR_NETWORK` | 0–1 | backend cannot remediate policy blocks → normally `escalated` |

## 6. Stop-line thresholds (backend-COMPUTED; deployment enforcement deferred)

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

The backend evaluator is advisory and fail-closed: if the latest orchestrator
metrics snapshot is absent, the canonical export is not emitted. A computed
`stop_expansion` result means "do not add new waves/devices until reviewed"; it
does not by itself roll back, pause, or mutate a deployment controller.

## 7. Classification & redaction

`classification_confidence ∈ {high, medium, low}` + `classifier_version`.
`auto_classified` vs `manual_classified`. Low-confidence auto-classification MAY
create `new`, but must NOT auto-waive or auto-resolve.

**Auto-classifiable signals implemented in v1 runtime:** command result error
codes and payload metadata for `INSTALLER_MSI`, `SERVICE_HMAC_MODE`, and
`BACKEND_RESULT_SUBMIT`; heartbeat staleness with truthful `SERVICE_HMAC_MODE`
evidence; backend-owned certificate/enrollment state for `CERT_IDENTITY`
(expired active certs and device-bound TPM_FAILED enrollments); and
strict-allowlisted AG-038 diagnostics DNS/TLS state for `DNS_EDGE_MTLS`. The
diagnostics path uses only a one-way config hash, backend DNS reachability,
backend TLS validity, and bounded error codes; it does not fabricate mTLS peer
certificate fingerprint evidence. All paths are validated through the same
evidence allowlist used by manual seed.

**Future signal-specific ingesters:** command lifecycle (queued-too-long,
delivered-no-result, repeated transient), richer heartbeat offline/version/mode
mismatch, richer edge/mTLS peer-certificate telemetry, EDR/network telemetry,
and richer agent diagnostics. These signals may still be represented today
through manual seed or confirmed operator evidence, but they are not claimed as
autonomous live ingesters until a structured source exists.

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

## 9. Runtime implementation status and residuals

Implemented backend components:

1. Backend Flyway tables (`endpoint_rollout_failure` + `_event`) + read/report REST.
2. Manual queue seed with typed evidence-redaction validation.
3. Command-result auto-ingest/classifier for `INSTALLER_MSI`, `SERVICE_HMAC_MODE`, and `BACKEND_RESULT_SUBMIT`.
4. Heartbeat-stale autonomous ingest for `SERVICE_HMAC_MODE` when the latest heartbeat carries bounded `agentMode`.
5. CERT_IDENTITY autonomous ingest from active expired machine certs and device-bound TPM_FAILED enrollments.
6. DNS_EDGE_MTLS autonomous ingest from strict-allowlisted AG-038 diagnostics DNS/TLS state; peer certificate fingerprint remains `null` unless a future structured source observes it.
7. EDR_NETWORK autonomous ingest from a strict `COLLECT_INVENTORY` `securityNetwork` block; the backend accepts only pre-redacted EDR/firewall block events and rejects raw IP/host/process path/URL/token evidence before persistence.
8. Orchestrator metrics snapshot + advisory stop-line threshold evaluator.
9. GitHub escalation issue generator + disabled-by-default publish endpoint.
10. Canonical `waveFailureReport` export; emits only when denominator evidence exists.

Residual boundaries:

1. `deployment_enforcement_active` remains false until a separate rollout-gating control actually blocks expansion.
2. Live GitHub issue POST requires an operator-configured GitHub integration/token; the backend generator is the redacted source projection and the POST endpoint remains disabled without config.
3. Live EDR_NETWORK coverage still requires platform-agent or an external EDR/firewall producer to emit the structured `securityNetwork` block; the backend must not infer EDR blocks from generic timeout strings or unstructured diagnostics.
4. Richer edge/mTLS peer-certificate evidence remains future work beyond diagnostics DNS/TLS.
5. UI/reporting surface is platform-web if required by rollout operations.

## 10. Honesty guards (contract self-check)

**Machine-enforced** (not prose): `validate.py` proves the schema is valid Draft
2020-12, the example validates against `$defs/waveFailureReport`, and a battery
of NEGATIVE cases are REJECTED — class-binding (`current_class` ≠ `evidence.class`),
the §4 transition matrix (e.g. `waived→resolved`, `resolved→new` without
`reopened`), missing required evidence, a raw `last_error` injection, a
non-redaction-marker `log_excerpt`, and a false claim that deployment
enforcement is active.
Run `python3 validate.py` (all checks must pass). The schema thus carries the
contract's claims; the table below is the residual prose-level review checklist.

A reference to this contract is **dishonest/unenforceable** if it:
- says a computed `stop_expansion` result is a deployment-enforced block before `deployment_enforcement_active=true` is backed by a rollout-gating control;
- treats GitHub labels as canonical queue state;
- keeps only raw `last_error` strings (fails redaction/classification/audit/retry);
- omits the append-only event ledger (state changes unauditable);
- allows arbitrary evidence JSON without the §3/§7 allowlist;
- keys only by global `device_id` (destroys wave/rerun history);
- uses pure append-only without the active aggregate (breaks operator workflow + stop-line queries);
- auto-classifies `EDR_NETWORK` high-confidence from a generic timeout string.
