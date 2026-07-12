#!/usr/bin/env python3
"""GUI overlay textures for the SCiPNET terminal screens.

The overlays are drawn INTO THE INVENTORY TITLE as font glyphs: a negative
space advance rewinds the cursor to the GUI's top-left corner, then one big
bitmap glyph (ascent 13 puts its top edge exactly at the container's 0,0)
paints a full futuristic panel over the vanilla chrome. Item icons render at
a higher z-level than text, so buttons and papers stay visible on top.

Run from the repo root:  python3 tools/gen_gui.py
"""
import json, os, struct, zlib

BG = (8, 12, 16, 255)          # near-black panel
BORDER = (0, 150, 140, 255)    # cyan frame
CELL_BG = (14, 22, 20, 255)    # slot well
CELL_EDGE = (30, 74, 58, 255)  # slot outline, dim phosphor green
HEADER = (120, 255, 170, 255)  # CRT text
DIVIDER = (24, 58, 46, 255)

FONT3X5 = {
    "S": ["111","100","111","001","111"], "C": ["111","100","100","100","111"],
    "I": ["111","010","010","010","111"], "P": ["111","101","111","100","100"],
    "N": ["101","111","111","101","101"], "E": ["111","100","111","100","111"],
    "T": ["111","010","010","010","010"], "L": ["100","100","100","100","111"],
    "O": ["111","101","101","101","111"], "G": ["111","100","101","101","111"],
    "D": ["110","101","101","101","110"], "A": ["111","101","111","101","101"],
    "B": ["110","101","110","101","110"], "U": ["101","101","101","101","111"],
    "W": ["101","101","101","111","101"], " ": ["000","000","000","000","000"],
}

class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[(0, 0, 0, 0)] * w for _ in range(h)]

    def fill(self, x, y, w, h, color):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if 0 <= xx < self.w and 0 <= yy < self.h:
                    self.px[yy][xx] = color

    def box(self, x, y, w, h, color):
        self.fill(x, y, w, 1, color); self.fill(x, y + h - 1, w, 1, color)
        self.fill(x, y, 1, h, color); self.fill(x + w - 1, y, 1, h, color)

    def text(self, x, y, s, color, scale=2):
        for ch in s:
            rows = FONT3X5.get(ch, FONT3X5[" "])
            for ry, row in enumerate(rows):
                for rx, bit in enumerate(row):
                    if bit == "1":
                        self.fill(x + rx * scale, y + ry * scale, scale, scale, color)
            x += 4 * scale

    def cell(self, x, y):
        self.fill(x, y, 18, 18, CELL_BG)
        self.box(x, y, 18, 18, CELL_EDGE)

    def grid(self, x, y, cols, rows):
        for r in range(rows):
            for c in range(cols):
                self.cell(x + c * 18, y + r * 18)

    def frame(self, header):
        self.fill(0, 0, self.w, self.h, BG)
        self.box(0, 0, self.w, self.h, BORDER)
        self.box(1, 1, self.w - 2, self.h - 2, (0, 70, 66, 255))
        self.fill(3, 14, self.w - 6, 1, DIVIDER)
        self.text(8, 3, header, HEADER)

    def png(self, path):
        rows = b"".join(
            b"\x00" + b"".join(bytes(p) for p in line) for line in self.px)
        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
        data = (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print(f"{path} ({self.w}x{self.h})")

out = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "terminal")
tex = os.path.join(out, "textures", "font")

# 6-row chest (entries / editor / admin): 176x222
c = Canvas(176, 222)
c.frame("SCIPNET DATABASE")
c.grid(7, 17, 9, 6)
c.fill(3, 136, 170, 1, DIVIDER)
c.grid(7, 139, 9, 3)
c.grid(7, 197, 9, 1)
c.png(os.path.join(tex, "gui_chest54.png"))

# 3-row chest (login): 176x166
c = Canvas(176, 166)
c.frame("SCIPNET LOGIN")
c.grid(7, 17, 9, 3)
c.fill(3, 80, 170, 1, DIVIDER)
c.grid(7, 83, 9, 3)
c.grid(7, 141, 9, 1)
c.png(os.path.join(tex, "gui_chest27.png"))

# anvil (line prompts): 176x166 - three slots, a text field, player inv
c = Canvas(176, 166)
c.frame("SCIPNET INPUT")
c.cell(26, 46); c.cell(75, 46); c.cell(133, 46)
c.box(59, 19, 52, 14, CELL_EDGE)          # where the rename field sits
c.fill(60, 20, 50, 12, CELL_BG)
c.text(114, 49, "T", HEADER, 2)            # a little arrow-ish tick toward result
c.fill(3, 80, 170, 1, DIVIDER)
c.grid(7, 83, 9, 3)
c.grid(7, 141, 9, 1)
c.png(os.path.join(tex, "gui_anvil.png"))

# the font that carries the overlays
font = {
    "providers": [
        {"type": "space", "advances": {"": -8, "": -60}},
        {"type": "bitmap", "file": "terminal:font/gui_chest54.png",
         "ascent": 13, "height": 222, "chars": [""]},
        {"type": "bitmap", "file": "terminal:font/gui_chest27.png",
         "ascent": 13, "height": 166, "chars": [""]},
        {"type": "bitmap", "file": "terminal:font/gui_anvil.png",
         "ascent": 13, "height": 166, "chars": [""]},
    ]
}
os.makedirs(os.path.join(out, "font"), exist_ok=True)
with open(os.path.join(out, "font", "gui.json"), "w") as f:
    json.dump(font, f, indent=2, ensure_ascii=False)
print(os.path.join(out, "font", "gui.json"))
