#!/usr/bin/env python3
"""SCiPNET button + folder/doc icon textures for the terminal GUI.

Every key button and folder/doc icon in the terminal gets a bespoke 16x16
green-CRT / phosphor texture, emitted on a base item through the item_model
component (the same unshadowable hook the app icons use in gen_apps.py):

    btn_login   - LOG IN button (power / arrow-in glyph)
    btn_new     - NEW ENTRY / NEW PERSONAL DOC (page with a + )
    btn_prev    - Previous page (left chevron)
    btn_next    - Next page (right chevron)
    btn_back    - back / return (curved back arrow)
    btn_cameras - CCTV grid / cameras (little camera)
    folder      - a personal folder (My Files)
    doc         - a document / record (a sheet with text lines)
    btn_release - RELEASE to public database (upload / arrow-up out of a tray)

item MODELS land in assets/terminal/models/item/, item DEFINITIONS (the
item_model component) in assets/terminal/items/. The plugin references these
via meta.setItemModel(new NamespacedKey("terminal", <id>)); if the pack is
absent the item just falls back to its base material.

Run from the repo root:  python3 tools/gen_buttons.py
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

def dot(px, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        px[y][x] = color

# ---------------------------------------------------------------- palette
DARK = (18, 22, 26, 255)          # tile body
FRAME = (40, 46, 52, 255)         # bezel
GREEN = (60, 220, 120, 255)       # bright phosphor
GREEN_DIM = (26, 96, 54, 255)     # dim phosphor
AMBER = (230, 180, 70, 255)       # release accent
PAPER = (238, 238, 230, 255)
INK = (70, 76, 84, 255)
FOLDER = (210, 178, 96, 255)
FOLDER_HI = (236, 208, 130, 255)
FOLDER_EDGE = (150, 118, 52, 255)

def tile(accent=FRAME):
    """A dark CRT button tile with a thin bezel to draw a glyph onto."""
    px = blank()
    rect(px, 1, 1, 15, 15, accent)
    rect(px, 2, 2, 14, 14, DARK)
    return px

# ----------------------------------------------------------------- LOG IN
# power / enter: a bright ring gap + a downstroke, phosphor green
login = tile()
box(login, 4, 4, 12, 12, GREEN_DIM)
rect(login, 7, 3, 9, 8, GREEN)             # power stem
rect(login, 5, 6, 11, 7, DARK)             # ring gap under the stem
png(os.path.join(RP, "textures", "item", "btn_login.png"), login)

# ---------------------------------------------------------------- NEW ENTRY
# a document sheet with a green + in the corner
new = tile()
rect(new, 4, 3, 12, 13, PAPER)
rect(new, 5, 5, 11, 6, INK)
rect(new, 5, 7, 11, 8, INK)
rect(new, 5, 9, 9, 10, INK)
rect(new, 9, 9, 14, 10, GREEN)             # plus, horizontal
rect(new, 11, 7, 12, 13, GREEN)            # plus, vertical
png(os.path.join(RP, "textures", "item", "btn_new.png"), new)

# ------------------------------------------------------------------- PREV
prev = tile()
for i in range(5):                          # left chevron
    rect(prev, 9 - i, 8 - i, 10 - i, 9 - i, GREEN)
    rect(prev, 9 - i, 8 + i, 10 - i, 9 + i, GREEN)
png(os.path.join(RP, "textures", "item", "btn_prev.png"), prev)

# ------------------------------------------------------------------- NEXT
nxt = tile()
for i in range(5):                          # right chevron
    rect(nxt, 6 + i, 8 - i, 7 + i, 9 - i, GREEN)
    rect(nxt, 6 + i, 8 + i, 7 + i, 9 + i, GREEN)
png(os.path.join(RP, "textures", "item", "btn_next.png"), nxt)

# ------------------------------------------------------------------- BACK
# a back arrow: shaft + arrowhead pointing left
back = tile()
rect(back, 5, 8, 12, 9, GREEN)             # shaft
for i in range(3):                          # arrowhead
    rect(back, 5 + i, 8 - i, 6 + i, 9 - i, GREEN)
    rect(back, 5 + i, 8 + i, 6 + i, 9 + i, GREEN)
png(os.path.join(RP, "textures", "item", "btn_back.png"), back)

# ---------------------------------------------------------------- CAMERAS
# a little CCTV camera body + lens, phosphor green
cam = tile()
rect(cam, 4, 6, 11, 10, GREEN_DIM)         # body
box(cam, 4, 6, 11, 10, GREEN)
rect(cam, 11, 7, 13, 9, GREEN)             # lens barrel
rect(cam, 6, 10, 7, 12, GREEN_DIM)         # mount
rect(cam, 5, 11, 8, 12, GREEN)             # base
dot(cam, 6, 7, GREEN)                       # rec dot
png(os.path.join(RP, "textures", "item", "btn_cameras.png"), cam)

# ------------------------------------------------------------------ FOLDER
# a manila personal folder (My Files) - warmer than the Records app folder
folder = blank()
rect(folder, 2, 5, 14, 14, FOLDER)
rect(folder, 2, 3, 8, 6, FOLDER)           # tab
box(folder, 2, 3, 8, 6, FOLDER_EDGE)
box(folder, 2, 5, 14, 14, FOLDER_EDGE)
rect(folder, 2, 11, 14, 14, FOLDER_HI)     # front flap highlight
dot(folder, 11, 8, GREEN)                   # little green status LED
png(os.path.join(RP, "textures", "item", "folder.png"), folder)

# --------------------------------------------------------------------- DOC
# a single document / record sheet with text lines
doc = blank()
rect(doc, 3, 2, 13, 15, PAPER)
box(doc, 3, 2, 13, 15, INK)
rect(doc, 5, 4, 11, 5, INK)
rect(doc, 5, 6, 11, 7, INK)
rect(doc, 5, 8, 11, 9, INK)
rect(doc, 5, 10, 9, 11, INK)
dot(doc, 11, 12, GREEN)                     # clearance LED
png(os.path.join(RP, "textures", "item", "doc.png"), doc)

# ----------------------------------------------------------------- RELEASE
# upload / file-to-public: an up arrow rising out of a tray, amber accent
rel = tile()
rect(rel, 4, 11, 12, 13, AMBER)            # tray
rect(rel, 7, 4, 9, 11, GREEN)              # arrow shaft
for i in range(3):                          # arrowhead up
    rect(rel, 8 - i, 4 + i, 8 + i + 1, 5 + i, GREEN)
png(os.path.join(RP, "textures", "item", "btn_release.png"), rel)

# --------------------------------------------------------- models + defs
os.makedirs(os.path.join(RP, "models", "item"), exist_ok=True)
os.makedirs(os.path.join(RP, "items"), exist_ok=True)
IDS = ("btn_login", "btn_new", "btn_prev", "btn_next", "btn_back",
       "btn_cameras", "folder", "doc", "btn_release")
for name in IDS:
    json.dump({"parent": "minecraft:item/generated",
               "textures": {"layer0": "terminal:item/" + name}},
              open(os.path.join(RP, "models", "item", name + ".json"), "w"), indent=2)
    json.dump({"model": {"type": "minecraft:model", "model": "terminal:item/" + name}},
              open(os.path.join(RP, "items", name + ".json"), "w"), indent=2)
    print(name + " model + item definition")
