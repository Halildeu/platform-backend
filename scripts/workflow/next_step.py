#!/usr/bin/env python3
"""Basit workflow yardımcısı.

Kullanım:
    python scripts/workflow/next_step.py <TICKET-ID>
    python scripts/workflow/next_step.py --project <KOD>

TICKET-ID board.json içinde tanımlı (örn. FE-02, BE-03). Çıktı olarak
durum, sahip ve takip edilmesi gereken bir sonraki adımı gösterir.
--project ile verilen KOD için o projeye ait kartların özetini listeler.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

BOARD_PATH = Path(__file__).resolve().parents[2] / "docs/05-governance/02-roadmap/board.json"


def load_board() -> dict:
    try:
        data = json.loads(BOARD_PATH.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise SystemExit(f"board dosyası bulunamadı: {BOARD_PATH}") from exc
    return data


def show_ticket(board: dict, ticket_id: str) -> None:
    for ticket in board.get("tickets", []):
        if ticket.get("id", "").upper() == ticket_id:
            print(f"Ticket: {ticket['id']} — {ticket['title']}")
            print(f"Durum : {ticket['status']}")
            print(f"Sahip : {ticket.get('owner', 'n/a')}")
            docs = ticket.get("docs") or []
            if docs:
                print("Dokümanlar:")
                for path in docs:
                    print(f"  - {path}")
            if ticket.get("next_step"):
                print(f"Sonraki Adım: {ticket['next_step']}")
            return
    raise SystemExit(f"Ticket bulunamadı: {ticket_id}")


def list_project(board: dict, project_code: str) -> None:
    project_code = project_code.upper()
    items = [t for t in board.get("tickets", []) if t.get("project", "").upper() == project_code]
    if not items:
        raise SystemExit(f"Projeye ait kart bulunamadı: {project_code}")
    print(f"Proje: {project_code} — {len(items)} kart")
    for t in items:
        nid = t.get("id")
        title = t.get("title")
        status = t.get("status")
        next_step = t.get("next_step") or "-"
        print(f"- {nid} [{status}] {title}")
        print(f"  next: {next_step}")


def main(argv: list[str]) -> None:
    if len(argv) == 3 and argv[1] == "--project":
        board = load_board()
        return list_project(board, argv[2])
    if len(argv) != 2:
        raise SystemExit("Kullanım: python scripts/workflow/next_step.py <TICKET-ID> | --project <KOD>")
    ticket_id = argv[1].upper()
    board = load_board()
    show_ticket(board, ticket_id)


if __name__ == "__main__":
    main(sys.argv)
