#!/usr/bin/env python3
"""
DD-5 — Authz Annotation ↔ OpenFGA Model Relation Alignment Guard

Tetikleyici: 2026-04-29 D35-3 closure flow tespit edildi — backend
@RequireModule annotations OpenFGA model'de olmayan relation isimleri
gönderiyordu (`viewer`/`manager`/`admin` vs model'in `can_view`/
`can_manage`/`can_edit`/`blocked`). Sonuç: tüm guarded REST endpoint'ler
HTTP 400 `relation 'module#X' not found` → 403 fail-closed → ALL users
denied (admin@example.com dahil).

Bu script ADR-0011 §4 PR sequence extension olarak (DD-5) backend
repo'sunda CI guard koşar:

1. Tüm `@RequireModule(value = "X", relation = "Y")` annotation
   usage'larını grep et (Java source files).
2. OpenFGA `model.fga` dosyasını parse et — `module` type relations'unu
   çıkar.
3. RequireModuleInterceptor RELATION_ALIASES map'i ile aynı mapping
   uygulanır (alias → canonical):
     viewer → can_view
     manager → can_manage
     admin → can_manage
     editor → can_edit
4. Her annotation relation için: alias-mapped resolved relation
   model'in module type'ında tanımlı mı?

PASS = tüm annotation relations canonical (or alias) ve model ile uyumlu.
FAIL = bir veya daha fazla annotation model'de olmayan relation kullanıyor
(potansiyel runtime 400 / 403 fail).

Codex thread: `019dd409` (D35-3 prereq strategy + cross-repo drift coordination).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Iterable


# Mirrors RequireModuleInterceptor.RELATION_ALIASES map (Java).
# Source of truth: permission-service/.../config/RequireModuleInterceptor.java
RELATION_ALIASES: dict[str, str] = {
    "viewer": "can_view",
    "manager": "can_manage",
    "admin": "can_manage",
    "editor": "can_edit",
}

# Annotation pattern: @RequireModule(value = "X", relation = "Y")
# Tolerates whitespace + optional value/relation arg ordering, default relation.
ANNOTATION_RE = re.compile(
    r'@RequireModule\s*\(\s*'
    r'(?:value\s*=\s*)?"(?P<value>[^"]+)"\s*'
    r'(?:,\s*relation\s*=\s*"(?P<relation>[^"]+)"\s*)?'
    r'\)'
)

# OpenFGA model.fga `type module` block + `define <relation>:` parse.
# Matches `define can_view: [user] or can_manage but not blocked`.
RELATION_DEFINE_RE = re.compile(r'^\s*define\s+(\w+)\s*:', re.MULTILINE)


@dataclass
class AnnotationUsage:
    file: str
    line: int
    module: str
    declared_relation: str
    resolved_relation: str  # after alias mapping


@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str


@dataclass
class Report:
    repo_root: str
    model_path: str
    module_relations: list[str] = field(default_factory=list)
    annotations: list[AnnotationUsage] = field(default_factory=list)
    checks: list[CheckResult] = field(default_factory=list)

    @property
    def overall_pass(self) -> bool:
        return all(c.passed for c in self.checks)


def parse_module_relations(model_text: str) -> list[str]:
    """Parse `type module` block from model.fga and extract relation names."""
    in_module = False
    relations: list[str] = []
    for raw_line in model_text.splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()
        if stripped.startswith("type "):
            in_module = stripped == "type module"
            continue
        if not in_module:
            continue
        if stripped.startswith("type ") and stripped != "type module":
            in_module = False
            continue
        m = RELATION_DEFINE_RE.match(line)
        if m:
            relations.append(m.group(1))
    return relations


def find_annotations(repo_root: Path) -> Iterable[AnnotationUsage]:
    """Walk Java source files and find @RequireModule usages."""
    java_files = list(repo_root.rglob("*.java"))
    for jf in java_files:
        # Skip generated/target/test fixtures
        s_path = str(jf)
        if "/target/" in s_path or "/build/" in s_path:
            continue
        if "/tests/drift_detection/fixtures/" in s_path:
            continue
        try:
            text = jf.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for line_no, line in enumerate(text.splitlines(), start=1):
            m = ANNOTATION_RE.search(line)
            if not m:
                continue
            module = m.group("value")
            declared = m.group("relation") or "can_view"  # annotation default
            resolved = RELATION_ALIASES.get(declared, declared)
            yield AnnotationUsage(
                file=str(jf.relative_to(repo_root)),
                line=line_no,
                module=module,
                declared_relation=declared,
                resolved_relation=resolved,
            )


def evaluate(report: Report) -> Report:
    # Check 1: model loaded + module relations parsed
    if report.module_relations:
        report.checks.append(CheckResult(
            name="model_module_type_loaded",
            passed=True,
            detail=f"OpenFGA model module type relations: {sorted(report.module_relations)}",
        ))
    else:
        report.checks.append(CheckResult(
            name="model_module_type_loaded",
            passed=False,
            detail=f"OpenFGA model.fga not parsed or has no `type module` block ({report.model_path})",
        ))

    # Check 2: at least one annotation present (sanity — backend uses interceptor)
    if report.annotations:
        report.checks.append(CheckResult(
            name="annotations_present",
            passed=True,
            detail=f"@RequireModule usages found: {len(report.annotations)}",
        ))
    else:
        report.checks.append(CheckResult(
            name="annotations_present",
            passed=False,
            detail="No @RequireModule annotation found — interceptor may be unused (regression risk)",
        ))

    # Check 3: each declared relation is canonical OR alias
    canonical = set(report.module_relations)
    aliases = set(RELATION_ALIASES.keys())
    valid_declared = canonical | aliases
    invalid_declared = [
        a for a in report.annotations
        if a.declared_relation not in valid_declared
    ]
    if not invalid_declared:
        report.checks.append(CheckResult(
            name="declared_relations_canonical_or_alias",
            passed=True,
            detail=(
                f"All {len(report.annotations)} declared relations are canonical "
                f"({sorted(canonical)}) or alias ({sorted(aliases)})"
            ),
        ))
    else:
        details = [
            f"{a.file}:{a.line} module={a.module} declared={a.declared_relation}"
            for a in invalid_declared
        ]
        report.checks.append(CheckResult(
            name="declared_relations_canonical_or_alias",
            passed=False,
            detail=f"Unknown declared relations: {details}",
        ))

    # Check 4: each resolved (post-alias) relation EXISTS in model
    invalid_resolved = [
        a for a in report.annotations
        if a.resolved_relation not in canonical
    ]
    if not invalid_resolved:
        report.checks.append(CheckResult(
            name="resolved_relations_in_model",
            passed=True,
            detail=(
                f"All {len(report.annotations)} resolved relations exist in OpenFGA "
                f"module type relations: {sorted(canonical)}"
            ),
        ))
    else:
        details = [
            f"{a.file}:{a.line} module={a.module} declared={a.declared_relation} "
            f"→ resolved={a.resolved_relation} (NOT IN MODEL)"
            for a in invalid_resolved
        ]
        report.checks.append(CheckResult(
            name="resolved_relations_in_model",
            passed=False,
            detail=f"Annotation relations not in model: {details}",
        ))

    return report


def main() -> int:
    parser = argparse.ArgumentParser(
        description="DD-5 authz annotation ↔ OpenFGA model relation alignment check",
    )
    parser.add_argument(
        "--repo-root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="Backend repo root (default: 2 levels up from script)",
    )
    parser.add_argument(
        "--model-path",
        type=Path,
        default=None,
        help="OpenFGA model.fga path (default: <repo>/backend/openfga/model.fga)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Print per-check detail",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Emit JSON report to stdout",
    )
    args = parser.parse_args()

    repo_root: Path = args.repo_root.resolve()
    model_path: Path = args.model_path or (repo_root / "backend" / "openfga" / "model.fga")

    report = Report(
        repo_root=str(repo_root),
        model_path=str(model_path),
    )

    if model_path.exists():
        try:
            model_text = model_path.read_text(encoding="utf-8")
            report.module_relations = parse_module_relations(model_text)
        except OSError as ex:
            report.module_relations = []
            sys.stderr.write(f"warn: cannot read model.fga: {ex}\n")
    else:
        sys.stderr.write(f"warn: model.fga not found at {model_path}\n")

    report.annotations = list(find_annotations(repo_root))

    evaluate(report)

    if args.json:
        out = {
            "repo_root": report.repo_root,
            "model_path": report.model_path,
            "module_relations": report.module_relations,
            "annotation_count": len(report.annotations),
            "annotations": [asdict(a) for a in report.annotations],
            "checks": [asdict(c) for c in report.checks],
            "overall_pass": report.overall_pass,
        }
        print(json.dumps(out, indent=2, ensure_ascii=False))
    else:
        passed = sum(1 for c in report.checks if c.passed)
        total = len(report.checks)
        for c in report.checks:
            mark = "✓" if c.passed else "✗"
            print(f"  {mark} {c.name}: {c.detail}" if args.verbose else f"  {mark} {c.name}")
        verdict = "PASS" if report.overall_pass else "FAIL"
        print(f"\nDD-5 authz relation alignment: {verdict} ({passed} pass, {total - passed} fail)")

    return 0 if report.overall_pass else 1


if __name__ == "__main__":
    sys.exit(main())
