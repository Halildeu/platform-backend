#!/usr/bin/env bash
# Faz 35 ES-403 — grant or revoke an Etik Speak subscription (#885).
#
# A subscription is a commercial fact granted by the vendor, so it is written here rather
# than through the product's own API. An HTTP write — even behind the manager role — would
# let an organisation grant itself SUBJECT_REVEAL, the capability the catalog refuses to
# bundle precisely because its misuse cannot be undone.
#
# Every change writes two rows in one transaction: the subscription, and an audit event that
# carries the approval reference into the append-only ledger. A grant with no recorded
# justification is a grant nobody can account for later, which is why --approval-ref is
# required rather than optional.
#
# The change becomes visible to the running service within one entitlement cache TTL
# (10 minutes). Nothing here reaches into the service to invalidate it: an emergency
# withdrawal is the kill-switch, not a subscription edit.
#
#   ./etik-speak-subscription.sh --org <uuid> --product <id> --approval-ref <ref> [--revoke]
#   Add --apply to write. Without it the script only reports what it would do.
set -euo pipefail

CONTAINER="${ETHICS_PG_CONTAINER:-platform-pg-test}"
DB="${ETHICS_DB:-ethics}"
DB_USER="${ETHICS_DB_USER:-ethics_app}"
SCHEMA="ethics_service"

# Kept in step with EthicsProductCatalog by SubscriptionScriptCatalogParityTest. A product id
# that is not in the catalog would be accepted by the database and then carry no capability at
# all — a grant that looks done and does nothing.
PRODUCTS=(
  "etik-speak-core"
  "etik-speak-plus"
  "etik-speak-subject-reveal"
)

ORG=""; PRODUCT=""; APPROVAL_REF=""; ACTION="GRANT"; APPLY="false"

usage() {
  cat >&2 <<EOF
kullanim: $(basename "$0") --org <uuid> --product <id> --approval-ref <ref> [--revoke] [--apply]

  --org           kurum kimligi (uuid)
  --product       ${PRODUCTS[*]}
  --approval-ref  onay referansi; deftere yazilir. Bosluk icermez ve kisi adi
                  tasimamalidir - bu alan silinemeyen bir kayda gider.
  --revoke        aboneligi kapat (satir silinmez, revoked_at yazilir)
  --apply         gercekten yaz. Verilmezse yalnizca ne yapilacagi raporlanir.
EOF
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --org)          ORG="${2:-}"; shift 2 ;;
    --product)      PRODUCT="${2:-}"; shift 2 ;;
    --approval-ref) APPROVAL_REF="${2:-}"; shift 2 ;;
    --revoke)       ACTION="REVOKE"; shift ;;
    --apply)        APPLY="true"; shift ;;
    --check)        APPLY="false"; shift ;;
    -h|--help)      usage ;;
    *) echo "bilinmeyen argüman: $1" >&2; usage ;;
  esac
done

[[ -n "$ORG" && -n "$PRODUCT" && -n "$APPROVAL_REF" ]] || usage

[[ "$ORG" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || {
  echo "gecersiz kurum kimligi (uuid bekleniyor): $ORG" >&2; exit 1; }

printf '%s\n' "${PRODUCTS[@]}" | grep -qx -- "$PRODUCT" || {
  echo "katalogda olmayan urun: $PRODUCT" >&2
  echo "bilinen urunler: ${PRODUCTS[*]}" >&2
  exit 1; }

# No spaces, so a sentence — and with it a person's name — cannot be pasted into a row that
# can never be edited or deleted. A contract or ticket reference is what belongs here.
[[ "$APPROVAL_REF" =~ ^[A-Za-z0-9._/-]{3,64}$ ]] || {
  echo "onay referansi bicimi gecersiz: bosluksuz, 3-64 karakter, [A-Za-z0-9._/-]" >&2
  exit 1; }

psql_run() {
  docker exec -i "$CONTAINER" psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" -t -A -F'|' "$@"
}

echo "== mevcut durum"
psql_run <<SQL
SELECT product_id, active, granted_at, COALESCE(revoked_at::text, '-')
  FROM ${SCHEMA}.ethics_org_subscription
 WHERE org_id = '${ORG}'
 ORDER BY granted_at;
SQL

if [[ "$APPLY" != "true" ]]; then
  echo "== kuru calisma: ${ACTION} ${PRODUCT} -> ${ORG} (yazilmadi; --apply ile uygulanir)"
  exit 0
fi

if [[ "$ACTION" == "GRANT" ]]; then
  psql_run <<SQL
BEGIN;
WITH inserted AS (
    INSERT INTO ${SCHEMA}.ethics_org_subscription (id, org_id, product_id, active, granted_at)
    VALUES (gen_random_uuid(), '${ORG}', '${PRODUCT}', true, now())
    RETURNING id, org_id
)
INSERT INTO ${SCHEMA}.ethics_audit_outbox
    (id, org_id, aggregate_id, event_type, payload, status, created_at, attempt_count)
SELECT gen_random_uuid(), i.org_id, i.id, 'ethics.subscription.granted',
       json_build_object(
           'action', 'GRANT',
           'product_id', '${PRODUCT}',
           'approval_ref', '${APPROVAL_REF}',
           'subscription_id', i.id)::text,
       'PENDING', now(), 0
  FROM inserted i;
COMMIT;
SQL
else
  # The row survives; only the window closes. A deleted subscription would erase the fact
  # that the organisation ever held the capability, which is the part an auditor asks about.
  psql_run <<SQL
BEGIN;
WITH revoked AS (
    UPDATE ${SCHEMA}.ethics_org_subscription
       SET active = false, revoked_at = now()
     WHERE org_id = '${ORG}' AND product_id = '${PRODUCT}' AND active
    RETURNING id, org_id
)
INSERT INTO ${SCHEMA}.ethics_audit_outbox
    (id, org_id, aggregate_id, event_type, payload, status, created_at, attempt_count)
SELECT gen_random_uuid(), r.org_id, r.id, 'ethics.subscription.revoked',
       json_build_object(
           'action', 'REVOKE',
           'product_id', '${PRODUCT}',
           'approval_ref', '${APPROVAL_REF}',
           'subscription_id', r.id)::text,
       'PENDING', now(), 0
  FROM revoked r;
COMMIT;
SQL
fi

echo "== yazildi; sonuc"
psql_run <<SQL
SELECT product_id, active, granted_at, COALESCE(revoked_at::text, '-')
  FROM ${SCHEMA}.ethics_org_subscription
 WHERE org_id = '${ORG}'
 ORDER BY granted_at;
SQL

echo "== not: calisan servis bu degisikligi en gec 10 dakika icinde gorur (entitlement TTL)"
