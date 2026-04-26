#!/usr/bin/env python3
"""
Migrate existing permission-service data to OpenFGA tuples.

Reads from PostgreSQL (permission_service schema):
  - user_role_assignments → user-role-scope tuples
  - scopes → hierarchy tuples (company→project, company→warehouse)

Writes to OpenFGA API:
  - Relationship tuples matching the model.fga schema

Usage:
  python3 migrate-permissions.py [--dry-run] [--pg-url URL] [--fga-url URL] [--store-id ID]

Environment variables (fallback):
  PERMISSION_DB_URL     (default: postgresql://postgres:postgres@localhost:5432/users)
  OPENFGA_API_URL       (default: http://localhost:4000)
  OPENFGA_STORE_ID      (required)
"""

import argparse
import json
import os
import sys
import urllib.request
import urllib.error

# Optional: psycopg2 for PostgreSQL
try:
    import psycopg2
    HAS_PSYCOPG2 = True
except ImportError:
    HAS_PSYCOPG2 = False


def parse_args():
    p = argparse.ArgumentParser(description="Migrate permission-service data to OpenFGA")
    p.add_argument("--dry-run", action="store_true", help="Print tuples without writing")
    p.add_argument("--pg-url", default=os.getenv("PERMISSION_DB_URL",
                   "postgresql://postgres:postgres@localhost:5432/users"))
    p.add_argument("--pg-schema", default=os.getenv("PERMISSION_DB_SCHEMA", "permission_service"))
    p.add_argument("--fga-url", default=os.getenv("OPENFGA_API_URL", "http://localhost:4000"))
    p.add_argument("--store-id", default=os.getenv("OPENFGA_STORE_ID", ""))
    return p.parse_args()


def connect_pg(url, schema):
    if not HAS_PSYCOPG2:
        print("ERROR: psycopg2 not installed. Run: pip install psycopg2-binary")
        sys.exit(1)
    conn = psycopg2.connect(url, options=f"-c search_path={schema},public")
    return conn


# Role name mapping: DB role name → OpenFGA relation
ROLE_MAP = {
    "ADMIN": "admin",
    "MANAGER": "manager",
    "EDITOR": "editor",
    "VIEWER": "viewer",
    "OPERATOR": "operator",
    "USER": "viewer",  # basic user → viewer
}


def fetch_role_assignments(conn):
    """Fetch active user-role-scope assignments."""
    cur = conn.cursor()
    cur.execute("""
        SELECT ura.user_id, r.name as role_name,
               ura.company_id, ura.project_id, ura.warehouse_id
        FROM user_role_assignments ura
        JOIN roles r ON r.id = ura.role_id
        WHERE ura.active = true
        ORDER BY ura.user_id, r.name
    """)
    rows = cur.fetchall()
    cur.close()
    return rows


def fetch_scopes(conn):
    """Fetch scope hierarchy (company→project, company→warehouse)."""
    cur = conn.cursor()
    cur.execute("""
        SELECT s.scope_type, s.ref_id,
               ps.scope_type as parent_type, ps.ref_id as parent_ref_id
        FROM scopes s
        LEFT JOIN scopes ps ON ps.id = s.parent_scope_id
        ORDER BY s.scope_type, s.ref_id
    """)
    rows = cur.fetchall()
    cur.close()
    return rows


def build_tuples(role_assignments, scopes):
    """Convert DB records to OpenFGA tuple format."""
    tuples = []
    seen = set()

    def add_tuple(user, relation, obj):
        key = (user, relation, obj)
        if key not in seen:
            seen.add(key)
            tuples.append({
                "user": user,
                "relation": relation,
                "object": obj
            })

    # Role assignments → tuples
    for user_id, role_name, company_id, project_id, warehouse_id in role_assignments:
        relation = ROLE_MAP.get(role_name.upper(), "viewer")

        if company_id:
            add_tuple(f"user:{user_id}", relation, f"company:{company_id}")
        if project_id:
            add_tuple(f"user:{user_id}", relation, f"project:{project_id}")
        if warehouse_id:
            add_tuple(f"user:{user_id}", relation, f"warehouse:{warehouse_id}")
        if not company_id and not project_id and not warehouse_id:
            # Global assignment → organization level
            add_tuple(f"user:{user_id}", relation, "organization:default")

    # Scope hierarchy → tuples
    for scope_type, ref_id, parent_type, parent_ref_id in scopes:
        if parent_type and parent_ref_id:
            parent_obj = f"{parent_type.lower()}:{parent_ref_id}"
            child_obj = f"{scope_type.lower()}:{ref_id}"
            # company:5 company project:2
            add_tuple(parent_obj, parent_type.lower(), child_obj)

    return tuples


def write_tuples_to_openfga(tuples, fga_url, store_id, dry_run=False):
    """Write tuples to OpenFGA in batches of 40 (API limit)."""
    if dry_run:
        print(f"\n[DRY RUN] Would write {len(tuples)} tuples:")
        for t in tuples:
            print(f"  {t['user']} {t['relation']} {t['object']}")
        return 0, 0

    batch_size = 40  # OpenFGA max per write
    written = 0
    skipped = 0

    for i in range(0, len(tuples), batch_size):
        batch = tuples[i:i + batch_size]
        body = json.dumps({"writes": {"tuple_keys": batch}}).encode()

        req = urllib.request.Request(
            f"{fga_url}/stores/{store_id}/write",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST"
        )

        try:
            with urllib.request.urlopen(req) as resp:
                written += len(batch)
                print(f"  Written batch {i // batch_size + 1}: {len(batch)} tuples")
        except urllib.error.HTTPError as e:
            error_body = e.read().decode()
            if "cannot write a tuple which already exists" in error_body.lower():
                # Try one by one to find which exist
                for t in batch:
                    single_body = json.dumps({"writes": {"tuple_keys": [t]}}).encode()
                    single_req = urllib.request.Request(
                        f"{fga_url}/stores/{store_id}/write",
                        data=single_body,
                        headers={"Content-Type": "application/json"},
                        method="POST"
                    )
                    try:
                        with urllib.request.urlopen(single_req):
                            written += 1
                    except urllib.error.HTTPError:
                        skipped += 1
            else:
                print(f"  ERROR: {e.code} {error_body}")
                skipped += len(batch)

    return written, skipped


def main():
    args = parse_args()

    if not args.store_id and not args.dry_run:
        print("ERROR: --store-id or OPENFGA_STORE_ID required (unless --dry-run)")
        sys.exit(1)

    print("=== Permission → OpenFGA Migration ===")
    print(f"PostgreSQL: {args.pg_url} (schema: {args.pg_schema})")
    print(f"OpenFGA:    {args.fga_url} (store: {args.store_id})")
    print(f"Mode:       {'DRY RUN' if args.dry_run else 'LIVE'}")
    print()

    # Connect and fetch data
    conn = connect_pg(args.pg_url, args.pg_schema)
    try:
        print("Fetching role assignments...")
        assignments = fetch_role_assignments(conn)
        print(f"  Found {len(assignments)} active assignments")

        print("Fetching scope hierarchy...")
        scopes = fetch_scopes(conn)
        print(f"  Found {len(scopes)} scopes")
    finally:
        conn.close()

    # Build tuples
    tuples = build_tuples(assignments, scopes)
    print(f"\nGenerated {len(tuples)} unique tuples")

    # Write to OpenFGA
    written, skipped = write_tuples_to_openfga(tuples, args.fga_url, args.store_id, args.dry_run)

    print(f"\n=== Migration Complete ===")
    print(f"  Written:  {written}")
    print(f"  Skipped:  {skipped} (already exist)")
    print(f"  Total:    {len(tuples)}")


if __name__ == "__main__":
    main()
