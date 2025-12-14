"""
Utility script to regenerate Theme Tokens preview images without Storybook.

Run:
    python docs/03-delivery/guides/theme/img/generate_theme_previews.py
"""
from __future__ import annotations

import struct
import zlib
from pathlib import Path
from typing import Dict, List

WIDTH = 640
HEIGHT = 360
OUTPUT_DIR = Path(__file__).parent

THEMES: Dict[str, Dict[str, str]] = {
    "theme-tokens-light-comfortable": {
        "bg": "#FFFFFF",
        "surface": "#FFFFFF",
        "topbar": "#0B1220",
        "filter": "#F8FAFC",
        "filterAccent": "#E2E8F0",
        "tableOdd": "#FFFFFF",
        "tableEven": "#F1F5F9",
        "text": "#0F172A",
        "accent": "#2B6CB0",
        "density": "comfortable",
    },
    "theme-tokens-dark-comfortable": {
        "bg": "#0B1220",
        "surface": "#0B1220",
        "topbar": "#111827",
        "filter": "#1F2937",
        "filterAccent": "#374151",
        "tableOdd": "#111827",
        "tableEven": "#1F2937",
        "text": "#E2E8F0",
        "accent": "#4096FF",
        "density": "comfortable",
    },
    "theme-tokens-high-contrast-comfortable": {
        "bg": "#000000",
        "surface": "#000000",
        "topbar": "#000000",
        "filter": "#1F1F1F",
        "filterAccent": "#2F2F2F",
        "tableOdd": "#000000",
        "tableEven": "#0F0F0F",
        "text": "#FFFFFF",
        "accent": "#FFFF00",
        "density": "comfortable",
    },
    "theme-tokens-light-compact": {
        "bg": "#FFFFFF",
        "surface": "#FFFFFF",
        "topbar": "#0B1220",
        "filter": "#F8FAFC",
        "filterAccent": "#E2E8F0",
        "tableOdd": "#FFFFFF",
        "tableEven": "#F1F5F9",
        "text": "#0F172A",
        "accent": "#2B6CB0",
        "density": "compact",
    },
}


def hex_to_rgba(value: str, alpha: int = 255) -> List[int]:
    value = value.lstrip("#")
    return [int(value[i : i + 2], 16) for i in (0, 2, 4)] + [alpha]


def new_canvas(color: str) -> List[List[List[int]]]:
    rgba = hex_to_rgba(color)
    return [[rgba[:] for _ in range(WIDTH)] for _ in range(HEIGHT)]


def draw_rect(canvas: List[List[List[int]]], x0: int, y0: int, x1: int, y1: int, color: str) -> None:
    rgba = hex_to_rgba(color)
    x0 = max(0, int(x0))
    y0 = max(0, int(y0))
    x1 = min(WIDTH, int(x1))
    y1 = min(HEIGHT, int(y1))
    for y in range(y0, y1):
        row = canvas[y]
        for x in range(x0, x1):
            row[x] = rgba[:]


def draw_text_line(canvas: List[List[List[int]]], x: int, y: int, length: int, color: str) -> None:
    rgba = hex_to_rgba(color)
    for yy in range(max(0, y), min(HEIGHT, y + 5)):
        row = canvas[yy]
        for xx in range(max(0, x), min(WIDTH, x + length)):
            row[xx] = rgba[:]


def draw_layout(key: str, config: Dict[str, str]) -> None:
    canvas = new_canvas(config["bg"])
    top_bar_height = 56
    filter_height = 96 if config["density"] == "comfortable" else 68
    row_height = 56 if config["density"] == "comfortable" else 40
    padding = 24
    gap = 16 if config["density"] == "comfortable" else 10

    draw_rect(canvas, 0, 0, WIDTH, top_bar_height, config["topbar"])
    draw_rect(canvas, padding, 14, padding + 200, 42, config["accent"])
    draw_text_line(canvas, padding + 220, 22, 140, config["text"])

    filter_top = top_bar_height + gap
    draw_rect(canvas, padding, filter_top, WIDTH - padding, filter_top + filter_height, config["filter"])
    for i in range(3):
        left = padding + 20 + i * 150
        draw_rect(canvas, left, filter_top + 18, left + 120, filter_top + 50, config["filterAccent"])
        draw_text_line(canvas, left + 10, filter_top + 30, 70, config["text"])

    button_height = 40 if config["density"] == "comfortable" else 32
    draw_rect(
        canvas,
        WIDTH - padding - 140,
        filter_top + filter_height - button_height - 14,
        WIDTH - padding - 16,
        filter_top + filter_height - 14,
        config["accent"],
    )

    table_top = filter_top + filter_height + gap
    for i in range(4):
        y0 = table_top + i * (row_height + 2)
        color = config["tableOdd"] if i % 2 == 0 else config["tableEven"]
        draw_rect(canvas, padding, y0, WIDTH - padding, y0 + row_height, color)
        draw_text_line(canvas, padding + 14, y0 + 10, 200, config["text"])
        draw_text_line(canvas, padding + 14, y0 + 24, 120, config["text"])
        draw_rect(canvas, padding + 250, y0 + 12, padding + 340, y0 + 28, config["accent"])

    save_png(OUTPUT_DIR / f"{key}.png", canvas)


def save_png(path: Path, canvas: List[List[List[int]]]) -> None:
    raw = bytearray()
    for row in canvas:
        raw.append(0)
        for pixel in row:
            raw.extend(pixel)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    path.write_bytes(png)


if __name__ == "__main__":
    for theme_key, cfg in THEMES.items():
        draw_layout(theme_key, cfg)
        print(f"generated {theme_key}")
