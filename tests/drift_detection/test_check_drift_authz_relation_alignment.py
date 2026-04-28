"""
Unit tests for DD-5 — Authz Annotation ↔ OpenFGA Model Relation Alignment Guard.

Covers:
- model.fga `type module` block parsing
- @RequireModule annotation discovery (Java source file walk)
- Alias mapping (viewer→can_view etc.)
- Canonical relation pass-through
- Unknown relation detection (negative case)
- Empty model / missing annotation edge cases
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "drift_detection" / "check_drift_authz_relation_alignment.py"
FIXTURES = REPO_ROOT / "tests" / "drift_detection" / "fixtures"

# Import for direct unit calls
sys.path.insert(0, str(REPO_ROOT / "scripts" / "drift_detection"))
from check_drift_authz_relation_alignment import (  # noqa: E402
    RELATION_ALIASES,
    parse_module_relations,
    find_annotations,
)


# --------------------------------------------------------------------------
# parse_module_relations
# --------------------------------------------------------------------------

def test_parse_module_relations_minimal():
    """Minimal model.fga with only module type → 4 relations."""
    text = (FIXTURES / "model_module_only.fga").read_text()
    relations = parse_module_relations(text)
    assert sorted(relations) == ["blocked", "can_edit", "can_manage", "can_view"]


def test_parse_module_relations_full_model():
    """Production model.fga (multi-type) → only module type relations extracted."""
    model_path = REPO_ROOT / "backend" / "openfga" / "model.fga"
    if not model_path.exists():
        pytest.skip("backend/openfga/model.fga not present in this checkout")
    text = model_path.read_text()
    relations = parse_module_relations(text)
    # Other types (company, project, warehouse, branch) have `viewer` —
    # these MUST NOT leak into module relations.
    assert "viewer" not in relations, "Module type leaked relations from other types"
    assert sorted(relations) == ["blocked", "can_edit", "can_manage", "can_view"]


def test_parse_module_relations_no_module_block():
    """Model file without `type module` → empty list."""
    text = """
model
  schema 1.1

type user

type organization
  relations
    define admin: [user]
"""
    relations = parse_module_relations(text)
    assert relations == []


def test_parse_module_relations_empty_string():
    """Empty content → empty list (no crash)."""
    assert parse_module_relations("") == []


# --------------------------------------------------------------------------
# RELATION_ALIASES — mirrors RequireModuleInterceptor RELATION_ALIASES (Java)
# --------------------------------------------------------------------------

def test_alias_viewer_maps_to_can_view():
    assert RELATION_ALIASES["viewer"] == "can_view"


def test_alias_manager_maps_to_can_manage():
    assert RELATION_ALIASES["manager"] == "can_manage"


def test_alias_admin_on_module_maps_to_can_manage():
    assert RELATION_ALIASES["admin"] == "can_manage"


def test_alias_editor_maps_to_can_edit():
    assert RELATION_ALIASES["editor"] == "can_edit"


def test_alias_canonical_not_in_aliases_dict():
    """Canonical names like can_view should NOT be alias keys (not redundantly mapped)."""
    for canonical in ["can_view", "can_manage", "can_edit", "blocked"]:
        assert canonical not in RELATION_ALIASES, f"{canonical} should not be alias key"


# --------------------------------------------------------------------------
# find_annotations — Java source walk
# --------------------------------------------------------------------------

def test_find_annotations_canonical_fixture(tmp_path: Path):
    """Positive canonical fixture → 4 annotations all canonical relations."""
    # Copy fixture into isolated tmp dir to avoid finding production annotations
    fix_src = FIXTURES / "positive_canonical" / "SampleController.java"
    fix_dst = tmp_path / "SampleController.java"
    fix_dst.write_text(fix_src.read_text())

    annotations = list(find_annotations(tmp_path))
    assert len(annotations) == 4
    relations = sorted({a.declared_relation for a in annotations})
    assert relations == ["can_edit", "can_manage", "can_view"]


def test_find_annotations_alias_fixture(tmp_path: Path):
    """Positive alias fixture → 4 annotations all legacy alias relations."""
    fix_src = FIXTURES / "positive_alias" / "AliasController.java"
    fix_dst = tmp_path / "AliasController.java"
    fix_dst.write_text(fix_src.read_text())

    annotations = list(find_annotations(tmp_path))
    assert len(annotations) == 4
    declared = sorted({a.declared_relation for a in annotations})
    assert declared == ["admin", "editor", "manager", "viewer"]
    # Every alias should resolve to canonical
    resolved = sorted({a.resolved_relation for a in annotations})
    assert all(r in {"can_view", "can_manage", "can_edit"} for r in resolved)


def test_find_annotations_negative_fixture(tmp_path: Path):
    """Negative fixture → annotations with unknown relations detected."""
    fix_src = FIXTURES / "negative_unknown" / "BadController.java"
    fix_dst = tmp_path / "BadController.java"
    fix_dst.write_text(fix_src.read_text())

    annotations = list(find_annotations(tmp_path))
    assert len(annotations) == 2
    declared = sorted({a.declared_relation for a in annotations})
    assert declared == ["owner", "super-admin"]
    # Resolved should equal declared (no alias for unknown — passthrough)
    for a in annotations:
        assert a.resolved_relation == a.declared_relation


def test_find_annotations_no_java_files(tmp_path: Path):
    """Empty tree → no annotations (no crash)."""
    annotations = list(find_annotations(tmp_path))
    assert annotations == []


def test_find_annotations_skips_target_dir(tmp_path: Path):
    """Files under /target/ should be skipped (build artifacts)."""
    target_dir = tmp_path / "module" / "target" / "generated-sources"
    target_dir.mkdir(parents=True)
    (target_dir / "Generated.java").write_text(
        '@RequireModule(value = "X", relation = "viewer")\nclass G {}\n'
    )
    annotations = list(find_annotations(tmp_path))
    assert annotations == []


# --------------------------------------------------------------------------
# CLI integration
# --------------------------------------------------------------------------

def test_cli_pass_on_canonical_fixture(tmp_path: Path):
    """CLI script returns 0 on canonical-only fixture + minimal model."""
    fix_src = FIXTURES / "positive_canonical" / "SampleController.java"
    (tmp_path / "SampleController.java").write_text(fix_src.read_text())

    model_path = FIXTURES / "model_module_only.fga"
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--repo-root", str(tmp_path),
            "--model-path", str(model_path),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, f"stderr={result.stderr}\nstdout={result.stdout}"
    data = json.loads(result.stdout)
    assert data["overall_pass"] is True
    assert data["annotation_count"] == 4


def test_cli_pass_on_alias_fixture(tmp_path: Path):
    """CLI returns 0 on alias-only fixture (alias bridge works)."""
    fix_src = FIXTURES / "positive_alias" / "AliasController.java"
    (tmp_path / "AliasController.java").write_text(fix_src.read_text())

    model_path = FIXTURES / "model_module_only.fga"
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--repo-root", str(tmp_path),
            "--model-path", str(model_path),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0
    data = json.loads(result.stdout)
    assert data["overall_pass"] is True
    assert data["annotation_count"] == 4


def test_cli_fail_on_unknown_relation(tmp_path: Path):
    """CLI returns non-zero on unknown relation usage."""
    fix_src = FIXTURES / "negative_unknown" / "BadController.java"
    (tmp_path / "BadController.java").write_text(fix_src.read_text())

    model_path = FIXTURES / "model_module_only.fga"
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--repo-root", str(tmp_path),
            "--model-path", str(model_path),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 1
    data = json.loads(result.stdout)
    assert data["overall_pass"] is False
    # Both checks 3 (declared) and 4 (resolved) should fail for "owner"/"super-admin"
    failed_check_names = [c["name"] for c in data["checks"] if not c["passed"]]
    assert "declared_relations_canonical_or_alias" in failed_check_names
    assert "resolved_relations_in_model" in failed_check_names


def test_cli_pass_on_full_repo():
    """Full backend repo CLI run should PASS post 2026-04-29 fix (sha-12480ef)."""
    if not (REPO_ROOT / "backend" / "openfga" / "model.fga").exists():
        pytest.skip("Full backend repo not in expected layout")
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--repo-root", str(REPO_ROOT),
            "--json",
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, f"DD-5 should PASS post-fix; stdout={result.stdout[:500]}"
    data = json.loads(result.stdout)
    assert data["overall_pass"] is True
    assert data["annotation_count"] >= 20, "Backend should have ≥20 @RequireModule usages"
