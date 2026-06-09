# Rollout Failure Escalation — {{failure_class}} / {{wave_id}}

> **Generated from the backend failed-device queue (`endpoint_rollout_failure`).**
> **The canonical state is the backend queue item — NOT this issue.** This issue
> is an escalation *projection*; do not treat its labels/comments as the source
> of truth. Update state via the backend queue; this issue mirrors it.
>
> v1 NOTE: until the GitHub escalation **generator** lands (contract §9.4), this
> template is filled in MANUALLY by an operator from the wave export — it is not
> auto-generated.

- **failure_id:** `{{failure_id}}`
- **rollout_id / wave_id:** `{{rollout_id}}` / `{{wave_id}}`
- **device_id:** `{{device_id}}`
- **failure class:** `{{failure_class}}`
- **current state:** `{{current_state}}`  (retry {{retry_count}}/{{max_retries}})
- **first-action owner:** `{{owner_role}}`
- **classification:** `{{classification_confidence}}` (`{{classifier_version}}`)
- **stop-line contribution:** `{{stop_line_contribution}}`

## Redacted evidence

> Allowlisted fields only (contract §3/§7). **No raw logs, tokens, cert PEM,
> full SID/UPN, or raw IP.** If a field would carry sensitive data it is hashed/
> prefixed/omitted per the class schema.

```json
{{evidence_redacted_json}}
```

## Requested first action

{{requested_action}}

<!--
Labels (projection, not canonical):
  rollout-failure, class:{{failure_class}}, state:{{current_state}}, wave:{{wave_id}}
Close this issue ONLY after the backend queue item reaches resolved/waived.
-->

## Definition of done

- [ ] Backend queue item transitioned to `resolved` or `waived` (with reason/owner).
- [ ] If `waived`: `waived_until` set and recorded in the backend item.
- [ ] Root-cause + fix captured in `resolution_summary` (redacted).
- [ ] Wave export regenerated; `stop_line_status` re-evaluated.
