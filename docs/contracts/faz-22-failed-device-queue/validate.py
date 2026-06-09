#!/usr/bin/env python3
"""Machine-enforced verification for the failed-device queue contract (Faz 22.5
#520). Proves the schema enforces what the contract claims — NOT self-attestation
(HARD RULE: long-term machine-enforced). Run: `python3 validate.py`.

Checks: schema is valid Draft 2020-12; the example export validates against
$defs/waveFailureReport; and a battery of NEGATIVE cases are REJECTED — class
binding, transition matrix, required-evidence, redaction allowlist + token
grammar. Exit 0 = contract is honestly enforceable.
"""
import json
import os
import sys

try:
    from jsonschema import Draft202012Validator
    from jsonschema.validators import RefResolver
except Exception as e:  # pragma: no cover
    print("FAIL: jsonschema not installed:", e)
    sys.exit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = json.load(open(os.path.join(HERE, "failed-device-queue.schema.json")))
EXAMPLE = json.load(open(os.path.join(HERE, "wave-failure-export.example.json")))
RESOLVER = RefResolver.from_schema(SCHEMA)
FMT = Draft202012Validator.FORMAT_CHECKER


def V(defname):
    return Draft202012Validator(
        {"$ref": SCHEMA["$id"] + "#/$defs/" + defname},
        resolver=RESOLVER, format_checker=FMT,
    )


def ok(name, cond):
    print(("PASS" if cond else "FAIL") + ": " + name)
    return cond


def valid(defname, doc):
    return not list(V(defname).iter_errors(doc))


# valid building blocks
HMAC_EV = {"class": "SERVICE_HMAC_MODE", "device_id": "d0efb00a-681a-4e32-b7de-a27ef94f2977",
           "service_state": "running", "agent_mode": "hmac", "hmac_error_code": "X",
           "last_heartbeat_at": None, "command_id": None, "agent_version": "0.2.0"}
GOOD_ITEM = {
    "id": "11111111-1111-4111-8111-111111111111", "org_id": "00000000-0000-0000-0000-000000000001",
    "rollout_id": "r", "wave_id": "w", "device_id": "d0efb00a-681a-4e32-b7de-a27ef94f2977",
    "current_class": "SERVICE_HMAC_MODE", "current_state": "retrying", "retry_count": 1, "max_retries": 2,
    "first_detected_at": "2026-06-09T03:55:00Z", "last_observed_at": "2026-06-09T04:25:00Z",
    "last_transition_at": "2026-06-09T04:25:00Z", "evidence_redacted": HMAC_EV,
    "owner_role": "op", "classification_confidence": "high", "version": 2,
}


def event(**over):
    e = {"id": "11111111-1111-4111-8111-111111111111", "failure_id": "22222222-2222-4222-8222-222222222222",
         "event_type": "transition", "from_state": "new", "to_state": "retrying", "class": "SERVICE_HMAC_MODE",
         "actor_type": "auto", "created_at": "2026-06-09T04:00:00Z"}
    e.update(over)
    return e


results = []
# 1. schema valid
try:
    Draft202012Validator.check_schema(SCHEMA)
    results.append(ok("schema is valid Draft 2020-12", True))
except Exception as ex:
    results.append(ok("schema is valid Draft 2020-12 (" + str(ex)[:60] + ")", False))

# 2. positive: example + good item/events
results.append(ok("example export validates (waveFailureReport)", valid("waveFailureReport", EXAMPLE)))
results.append(ok("good queueItem validates", valid("queueItem", GOOD_ITEM)))
results.append(ok("good event new->retrying validates", valid("event", event())))
results.append(ok("good detected event (no from_state) validates",
                  valid("event", event(event_type="detected", from_state=None))))

# 3. NEGATIVE cases must be REJECTED
bad_class_item = dict(GOOD_ITEM, current_class="DNS_EDGE_MTLS")  # evidence is still HMAC
results.append(ok("REJECT: current_class != evidence.class (binding)", not valid("queueItem", bad_class_item)))

results.append(ok("REJECT: transition waived->resolved (not allowed)",
                  not valid("event", event(from_state="waived", to_state="resolved"))))
results.append(ok("REJECT: transition resolved->new without reopened/source_signal",
                  not valid("event", event(from_state="resolved", to_state="new", event_type="transition"))))
results.append(ok("REJECT: non-detected event missing from_state",
                  not valid("event", event(from_state=None))))

raw_leak = dict(HMAC_EV); raw_leak["raw_last_error"] = "secret token leaked"
results.append(ok("REJECT: evidence carrying raw_last_error (allowlist)", not valid("evidence", raw_leak)))

missing_req = {"class": "BACKEND_RESULT_SUBMIT", "device_id": "d0efb00a-681a-4e32-b7de-a27ef94f2977",
               "result_submit_http_status": 500}  # missing command_id/backend_error_code/request_id/...
results.append(ok("REJECT: evidence missing required field", not valid("evidence", missing_req)))

raw_log = {"class": "INSTALLER_MSI", "device_id": "d0efb00a-681a-4e32-b7de-a27ef94f2977",
           "msi_product_code": None, "msi_exit_code": 1603, "agent_version": None, "installer_phase": None,
           "log_excerpt_redacted": "C:\\Users\\halil\\secret.log line 5 token=abc", "gpo_assignment_id": None}
results.append(ok("REJECT: log_excerpt not a redaction marker (token grammar)", not valid("evidence", raw_log)))

bad_enforce = json.loads(json.dumps(EXAMPLE)); bad_enforce["enforcement"]["threshold_evaluator"] = True
results.append(ok("REJECT: enforcement flag flipped true in v1 gate", not valid("waveFailureReport", bad_enforce)))

print("\n%d/%d checks passed" % (sum(results), len(results)))
sys.exit(0 if all(results) else 1)
