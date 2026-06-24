#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


TYPE_LINE_RE = re.compile(r"^type\s+([a-zA-Z_][a-zA-Z0-9_]*)$")
DEFINE_LINE_RE = re.compile(r"^define\s+([a-zA-Z_][a-zA-Z0-9_]*):\s+(.+)$")
# Match the full bracket group; comma-split lets multi-type direct lists like
# `[service_account, user]` render correctly (R23 Codex 019e59ed iter-2).
# Single-type `[user]` still resolves to a single direct related type, so the
# fix is backward-compatible with the legacy DSL slice (lines 1-76).
DIRECT_BRACKET_RE = re.compile(r"\[([^\]]*)\]")
IDENT_RE = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*")


def strip_outer_parens(expr: str) -> str:
  expr = expr.strip()
  while expr.startswith("(") and expr.endswith(")"):
    depth = 0
    balanced_outer = True
    for idx, ch in enumerate(expr):
      if ch == "(":
        depth += 1
      elif ch == ")":
        depth -= 1
        if depth == 0 and idx != len(expr) - 1:
          balanced_outer = False
          break
      if depth < 0:
        balanced_outer = False
        break
    if not balanced_outer or depth != 0:
      break
    expr = expr[1:-1].strip()
  return expr


def split_top_level_or(expr: str) -> list[str]:
  parts: list[str] = []
  start = 0
  depth = 0
  idx = 0
  while idx < len(expr):
    ch = expr[idx]
    if ch == "(":
      depth += 1
      idx += 1
      continue
    if ch == ")":
      depth -= 1
      idx += 1
      continue
    if depth == 0 and expr.startswith(" or ", idx):
      parts.append(expr[start:idx].strip())
      idx += 4
      start = idx
      continue
    idx += 1
  parts.append(expr[start:].strip())
  return [part for part in parts if part]


def parse_term(term: str) -> dict:
  term = strip_outer_parens(term)
  if term.startswith("[") and term.endswith("]"):
    return {"this": {}}

  if " from " in term:
    relation, tupleset = [part.strip() for part in term.split(" from ", 1)]
    return {
      "tupleToUserset": {
        "tupleset": {"object": "", "relation": tupleset},
        "computedUserset": {"object": "", "relation": relation},
      }
    }

  return {"computedUserset": {"object": "", "relation": term}}


def parse_union(expr: str) -> dict:
  expr = strip_outer_parens(expr)
  parts = split_top_level_or(expr)
  if len(parts) == 1:
    return parse_term(parts[0])
  children = []
  for part in parts:
    child = parse_expr(part)
    if "union" in child:
      children.extend(child["union"]["child"])
    else:
      children.append(child)
  return {"union": {"child": children}}


def parse_expr(expr: str) -> dict:
  expr = strip_outer_parens(expr)
  depth = 0
  split_at = -1
  idx = 0
  while idx < len(expr):
    ch = expr[idx]
    if ch == "(":
      depth += 1
    elif ch == ")":
      depth -= 1
    elif depth == 0 and expr.startswith(" but not ", idx):
      split_at = idx
      break
    idx += 1
  if split_at >= 0:
    base = expr[:split_at].strip()
    subtract = expr[split_at + len(" but not "):].strip()
    return {
      "difference": {
        "base": parse_union(base),
        "subtract": parse_term(subtract),
      }
    }
  return parse_union(expr)


def build_type_definition(type_name: str, relations: dict[str, str]) -> dict:
  if not relations:
    return {"type": type_name, "relations": {}, "metadata": None}

  relation_defs: dict[str, dict] = {}
  relation_meta: dict[str, dict] = {}

  for relation_name, expr in relations.items():
    relation_defs[relation_name] = parse_expr(expr)

    direct_types = []
    seen = set()
    for bracket_match in DIRECT_BRACKET_RE.finditer(expr):
      inner = bracket_match.group(1)
      if not inner.strip():
        # `[]` direct type list — official OpenFGA parser rejects.
        raise ValueError(
          f"Empty OpenFGA DSL direct type list in expression: {expr!r}"
        )
      for raw in inner.split(","):
        ident = raw.strip()
        if not ident:
          # R23 Codex 019e59ed iter-3: empty comma token (`[user,]`,
          # `[user,, service_account]`) is a DSL typo the official
          # OpenFGA parser rejects. Fail-fast so renderer-authoritative
          # path can't silently accept malformed direct type lists.
          raise ValueError(
            f"Empty OpenFGA DSL identifier in direct type list: '[{inner}]'"
          )
        if not IDENT_RE.fullmatch(ident):
          raise ValueError(
            f"Unsupported OpenFGA DSL identifier in direct type list: {ident!r}"
          )
        if ident in seen:
          continue
        seen.add(ident)
        direct_types.append({"type": ident, "condition": ""})

    relation_meta[relation_name] = {
      "directly_related_user_types": direct_types,
      "module": "",
      "source_info": None,
    }

  return {
    "type": type_name,
    "relations": relation_defs,
    "metadata": {
      "relations": relation_meta,
      "module": "",
      "source_info": None,
    },
  }


def parse_model(text: str) -> dict:
  type_definitions: list[dict] = []
  current_type: str | None = None
  current_relations: dict[str, str] = {}

  for raw_line in text.splitlines():
    stripped = raw_line.strip()
    if not stripped or stripped.startswith("#") or stripped == "model" or stripped == "relations" or stripped.startswith("schema "):
      continue

    type_match = TYPE_LINE_RE.match(stripped)
    if type_match:
      if current_type is not None:
        type_definitions.append(build_type_definition(current_type, current_relations))
      current_type = type_match.group(1)
      current_relations = {}
      continue

    define_match = DEFINE_LINE_RE.match(stripped)
    if define_match:
      current_relations[define_match.group(1)] = define_match.group(2).strip()
      continue

    raise ValueError(f"Unsupported OpenFGA DSL line: {raw_line}")

  if current_type is not None:
    type_definitions.append(build_type_definition(current_type, current_relations))

  return {
    "schema_version": "1.1",
    "type_definitions": type_definitions,
  }


def main() -> int:
  if len(sys.argv) > 2:
    print("usage: render_model_json.py [model.fga]", file=sys.stderr)
    return 2

  source = Path(sys.argv[1]) if len(sys.argv) == 2 else Path(__file__).with_name("model.fga")
  payload = parse_model(source.read_text())
  json.dump(payload, sys.stdout, indent=2)
  sys.stdout.write("\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
