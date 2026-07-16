#!/usr/bin/env python3
"""SCiPNET desktop app icons for the post-login terminal desktop.

Two flat item icons rendered on a base PAPER item through the item_model
component (assets/terminal/items/app_cctv.json / app_records.json - the same
unshadowable hook the CCTV monitor uses):

    app_cctv     - a green CRT camera glyph   ("CCTV Feeds")
    app_records  - a folder / records glyph   ("Records")
    app_myfiles  - a personal folder w/ badge ("My Files")

Both are desktop-icon flat textures (item/generated), so they read clearly as
little program tiles in the desktop GUI.

Run from the repo root:  python3 tools/gen_apps.py
"""
import json, os, struct, zlib

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

def blank():
    return [[(0, 0, 0, 0)] * 16 for _ in range(16)]

def rect(px, x0, y0, x1, y1, color):
    for y in range(y0, y1):
        for x in range(x0, x1):
            if 0 <= x < 16 and 0 <= y < 16:
                px[y][x] = color

def box(px, x0, y0, x1, y1, color):
    rect(px, x0, y0, x1, y0 + 1, color)
    rect(px, x0, y1 - 1, x1, y1, color)
    rect(px, x0, y0, x0 + 1, y1, color)
    rect(px, x1 - 1, y0, x1, y1, color)

# ------------------------------------------------------------- CCTV Feeds icon
# a dark tile with a green phosphor CRT screen and a little REC dot
DARK = (18, 22, 26, 255)
FRAME = (40, 46, 52, 255)
GREEN = (60, 220, 120, 255)
GREEN_DIM = (26, 96, 54, 255)
REC = (220, 40, 40, 255)

cctv = blank()
rect(cctv, 1, 1, 15, 15, FRAME)          # tile body
rect(cctv, 2, 2, 14, 14, DARK)
rect(cctv, 3, 4, 13, 12, GREEN_DIM)      # CRT glass
for y in range(4, 12):                   # scanlines
    if y % 2 == 0:
        rect(cctv, 3, y, 13, y + 1, GREEN)
box(cctv, 3, 4, 13, 12, GREEN)           # screen bezel glow
cctv[5][12] = REC                        # REC dot
rect(cctv, 6, 12, 10, 14, FRAME)         # little stand
png(os.path.join(RP, "textures", "item", "app_cctv.png"), cctv)

# -------------------------------------------------------------- Records icon
# a manila folder with a tab and a couple of "document" lines
FOLDER = (210, 178, 96, 255)
FOLDER_HI = (236, 208, 130, 255)
FOLDER_EDGE = (150, 118, 52, 255)
PAPER = (238, 238, 230, 255)
INK = (70, 76, 84, 255)

rec = blank()
rect(rec, 2, 5, 14, 14, FOLDER)          # folder body
rect(rec, 2, 3, 8, 6, FOLDER)            # tab
box(rec, 2, 3, 8, 6, FOLDER_EDGE)
box(rec, 2, 5, 14, 14, FOLDER_EDGE)
rect(rec, 4, 6, 12, 12, PAPER)           # peeking sheet
rect(rec, 5, 7, 11, 8, INK)              # text lines
rect(rec, 5, 9, 11, 10, INK)
rect(rec, 5, 11, 9, 12, INK)
rect(rec, 2, 12, 14, 14, FOLDER_HI)      # front flap highlight
png(os.path.join(RP, "textures", "item", "app_records.png"), rec)

# --------------------------------------------------------------- My Files icon
# a personal folder (bluer manila) with a green "owner" LED badge - reads as
# the player's OWN private drawer, distinct from the public Records folder
MY_FOLDER = (120, 168, 176, 255)
MY_FOLDER_HI = (150, 198, 206, 255)
MY_FOLDER_EDGE = (66, 104, 110, 255)
GREEN = (60, 220, 120, 255)

mine = blank()
rect(mine, 2, 5, 14, 14, MY_FOLDER)      # folder body
rect(mine, 2, 3, 8, 6, MY_FOLDER)        # tab
box(mine, 2, 3, 8, 6, MY_FOLDER_EDGE)
box(mine, 2, 5, 14, 14, MY_FOLDER_EDGE)
rect(mine, 4, 6, 12, 12, PAPER)          # peeking sheet
rect(mine, 5, 7, 11, 8, INK)             # text lines
rect(mine, 5, 9, 9, 10, INK)
rect(mine, 2, 12, 14, 14, MY_FOLDER_HI)  # front flap highlight
mine[6][11] = GREEN                      # owner LED badge
png(os.path.join(RP, "textures", "item", "app_myfiles.png"), mine)

# --------------------------------------------------------------- models + defs
os.makedirs(os.path.join(RP, "models", "item"), exist_ok=True)
for name in ("app_cctv", "app_records", "app_myfiles"):
    json.dump({"parent": "minecraft:item/generated",
               "textures": {"layer0": "terminal:item/" + name}},
              open(os.path.join(RP, "models", "item", name + ".json"), "w"), indent=2)
    # item DEFINITION for the item_model component (assets/terminal/items/)
    os.makedirs(os.path.join(RP, "items"), exist_ok=True)
    json.dump({"model": {"type": "minecraft:model", "model": "terminal:item/" + name}},
              open(os.path.join(RP, "items", name + ".json"), "w"), indent=2)
    print(name + " model + item definition")
