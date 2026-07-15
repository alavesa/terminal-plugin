#!/usr/bin/env python3
"""CCTV assets for the SCiPNET terminal plugin.

- camera: a wall-mounted element model (housing + lens + fullbright REC dot),
  authored facing SOUTH like every model in this family; textures in
  textures/block/ (auto-atlas).
- monitor: a handheld screen item (flat icon) - the thing you USE to jack in.
- item definitions (assets/terminal/items/) for the item_model component -
  the unshadowable hook, keycard-reader lesson.
- the feed overlays (assets/terminal/font/cctv.json):
    U+E000 scanlines - a low-alpha full-screen wash over live feeds
    U+E001 redacted  - near-opaque black with hazard stripes for feeds the
                       viewer's clearance does not open

Run from the repo root:  python3 tools/gen_cctv.py
"""
import json, math, os, struct, zlib

HERE = os.path.dirname(__file__)
RP = os.path.join(HERE, "..", "resource-pack", "assets", "terminal")

def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(data)
    print(path)

# ---------------------------------------------------------------- textures
def sheet(base, edge=None):
    px = [[tuple(base) + (255,)] * 16 for _ in range(16)]
    e = tuple(edge or [c - 12 for c in base]) + (255,)
    for i in range(16):
        px[0][i] = px[15][i] = e
        px[i][0] = px[i][15] = e
    return px

png(os.path.join(RP, "textures", "block", "cctv_case.png"), sheet((70, 74, 80)))
png(os.path.join(RP, "textures", "block", "cctv_dark.png"), sheet((28, 30, 34)))
lens = [[(16, 18, 22, 255)] * 16 for _ in range(16)]
for y in range(16):
    for x in range(16):
        d = math.hypot(x - 7.5, y - 7.5)
        if d < 5.5:
            lens[y][x] = (10, 24, 40, 255) if d > 3 else (40, 90, 140, 255)
        if d < 1.5:
            lens[y][x] = (150, 200, 235, 255)  # glint
png(os.path.join(RP, "textures", "block", "cctv_lens.png"), lens)
rec = [[(210, 30, 30, 255)] * 16 for _ in range(16)]
png(os.path.join(RP, "textures", "block", "cctv_rec.png"), rec)

# monitor icon (handheld item)
mon = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
for y in range(2, 12):
    for x in range(1, 15):
        edge = y in (2, 11) or x in (1, 14)
        mon[y][x] = (52, 56, 62, 255) if edge else (10, 26, 18, 255)
for y in range(4, 10):  # a little green feed
    for x in range(3, 13):
        mon[y][x] = (18, 60, 34, 255) if (y % 2 == 0) else (24, 84, 46, 255)
mon[4][11] = (210, 30, 30, 255)  # REC dot on screen
for x in range(6, 10):           # handle
    mon[12][x] = mon[13][x] = (40, 42, 48, 255)
png(os.path.join(RP, "textures", "item", "cctv_monitor.png"), mon)

# ------------------------------------------------------------------ models
face = lambda t: {f: {"texture": t} for f in ("north", "south", "east", "west", "up", "down")}
camera = {
    "textures": {"particle": "terminal:block/cctv_case",
                 "case": "terminal:block/cctv_case",
                 "dark": "terminal:block/cctv_dark",
                 "lens": "terminal:block/cctv_lens",
                 "rec": "terminal:block/cctv_rec"},
    "elements": [
        {"from": [6, 10, 0], "to": [10, 14, 2], "faces": face("#dark")},   # wall bracket
        {"from": [4.5, 8.5, 2], "to": [11.5, 14.5, 11], "faces": face("#case")},  # housing
        {"from": [5.5, 9.5, 11], "to": [10.5, 13.5, 12.5],                 # lens ring
         "faces": {**face("#dark"), "south": {"texture": "#lens"}}},
        {"from": [10.2, 13.8, 3], "to": [11.4, 15, 4.2], "shade": False,   # REC light
         "faces": face("#rec")},
    ],
    "display": {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]}}
}
os.makedirs(os.path.join(RP, "models", "entity"), exist_ok=True)
json.dump(camera, open(os.path.join(RP, "models", "entity", "cctv_camera.json"), "w"), indent=2)
os.makedirs(os.path.join(RP, "models", "item"), exist_ok=True)
json.dump({"parent": "minecraft:item/generated",
           "textures": {"layer0": "terminal:item/cctv_monitor"}},
          open(os.path.join(RP, "models", "item", "cctv_monitor.json"), "w"), indent=2)

# item DEFINITIONS for the item_model component
os.makedirs(os.path.join(RP, "items"), exist_ok=True)
json.dump({"model": {"type": "minecraft:model", "model": "terminal:entity/cctv_camera"}},
          open(os.path.join(RP, "items", "cctv_camera.json"), "w"), indent=2)
json.dump({"model": {"type": "minecraft:model", "model": "terminal:item/cctv_monitor"}},
          open(os.path.join(RP, "items", "cctv_monitor.json"), "w"), indent=2)
print("models + item definitions")

# ---------------------------------------------------- fullscreen feed glyphs
# blink-glyph metrics (height 300 / ascent 130 fills the screen through the
# title slot's 4x scale) - proven by the 173 blink and the 914 blackout
def overlay(name, painter):
    w, h = 64, 36
    px = [[(0, 0, 0, 0)] * w for _ in range(h)]
    painter(px, w, h)
    png(os.path.join(RP, "textures", "font", name), px)

def scanlines(px, w, h):
    for y in range(h):
        for x in range(w):
            if y % 3 == 0:
                px[y][x] = (10, 30, 16, 46)      # faint green scan rows
            elif y % 3 == 1:
                px[y][x] = (0, 0, 0, 18)

def redacted(px, w, h):
    for y in range(h):
        for x in range(w):
            px[y][x] = (4, 4, 5, 252)            # the feed exists; you don't clear it
    for y in range(h):
        for x in range(w):
            if (x + y) % 12 < 2 and h // 3 < y < 2 * h // 3:
                px[y][x] = (60, 8, 8, 255)       # hazard slashes across the middle

overlay("cctv_scan.png", scanlines)
overlay("cctv_redacted.png", redacted)
json.dump({"providers": [
    {"type": "bitmap", "file": "terminal:font/cctv_scan.png",
     "ascent": 130, "height": 300, "chars": [""]},
    {"type": "bitmap", "file": "terminal:font/cctv_redacted.png",
     "ascent": 130, "height": 300, "chars": [""]},
]}, open(os.path.join(RP, "font", "cctv.json"), "w"), indent=2, ensure_ascii=False)
print("feed overlays")

# ---- v0.6.0: the split models (bracket fixed, head rotating, ceiling rig)
# kept as a manual section marker; the split model json is authored above in
# the repo (models/entity/cctv_head|cctv_bracket_wall|cctv_bracket_ceiling)
