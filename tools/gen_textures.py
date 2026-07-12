#!/usr/bin/env python3
"""The terminal model's own textures - assets/terminal/textures/entity/.

The model used to borrow vanilla block textures (polished blackstone etc.),
which meant the terminal could not be reskinned without touching vanilla.
These five 16x16 files ARE the terminal's look now - repaint any of them and
every placed terminal changes with it. Keep the filenames; the model json
points at them by name.

Run from the repo root:  python3 tools/gen_textures.py
"""
import os, random, struct, zlib

def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print(path)

def sheet(base, jitter=6, seed=1):
    rng = random.Random(seed)  # fixed seed - same texture every run
    return [[tuple(max(0, min(255, c + rng.randint(-jitter, jitter))) for c in base) + (255,)
             for _ in range(16)] for _ in range(16)]

out = os.path.join(os.path.dirname(__file__), "..", "resource-pack",
                   "assets", "terminal", "textures", "entity")

# terminal_case - brushed dark gunmetal with a faint panel seam
case = sheet((58, 62, 68), seed=2)
for x in range(16):
    case[4][x] = (44, 47, 52, 255)
for y in range(16):
    case[y][12] = (48, 51, 56, 255)
png(os.path.join(out, "terminal_case.png"), case)

# terminal_dark - the underside / stand, nearly black
png(os.path.join(out, "terminal_dark.png"), sheet((28, 30, 34), seed=3))

# terminal_screen - green phosphor CRT: scanlines, a prompt line, a cursor
screen = [[(6, 14, 10, 255)] * 16 for _ in range(16)]
for y in range(16):
    for x in range(16):
        if y % 2 == 0:
            screen[y][x] = (10, 24, 16, 255)
for x, ch in enumerate("1110110010110000"):  # a line of "text"
    if ch == "1":
        screen[3][x] = (96, 240, 150, 255)
for x, ch in enumerate("0111010110100000"):
    if ch == "1":
        screen[6][x] = (74, 200, 124, 255)
for x, ch in enumerate("1101100000000000"):
    if ch == "1":
        screen[9][x] = (74, 200, 124, 255)
screen[12][1] = screen[12][2] = (140, 255, 180, 255)   # blinking cursor
for i in range(16):                                     # soft edge glow
    screen[0][i] = screen[15][i] = (4, 10, 8, 255)
    screen[i][0] = screen[i][15] = (4, 10, 8, 255)
png(os.path.join(out, "terminal_screen.png"), screen)

# terminal_screen_off - powered down: near-black glass, one faint reflection
off = [[(9, 11, 12, 255)] * 16 for _ in range(16)]
for y in range(16):
    for x in range(16):
        if y % 2 == 0:
            off[y][x] = (7, 9, 10, 255)   # the scanline mask still ghosts through
for i in range(16):                        # diagonal window reflection
    for w in range(2):
        x = i + 3 + w
        if 0 <= x < 16 and 0 <= 15 - i < 16:
            off[15 - i][x] = (16, 20, 22, 255)
for i in range(16):
    off[0][i] = off[15][i] = (5, 6, 7, 255)
    off[i][0] = off[i][15] = (5, 6, 7, 255)
png(os.path.join(out, "terminal_screen_off.png"), off)

# terminal_keys - a key grid on dark plastic
keys = sheet((36, 39, 44), jitter=3, seed=4)
for row in range(0, 16, 4):
    for col in range(0, 16, 3):
        for dy in range(2):
            for dx in range(2):
                if row + dy < 16 and col + dx < 16:
                    keys[row + dy][col + dx] = (70, 76, 84, 255)
png(os.path.join(out, "terminal_keys.png"), keys)

# terminal_vent - horizontal cooling slats on the monitor's back
vent = sheet((50, 54, 60), jitter=3, seed=5)
for y in range(2, 16, 3):
    for x in range(2, 14):
        vent[y][x] = (22, 24, 28, 255)
png(os.path.join(out, "terminal_vent.png"), vent)
