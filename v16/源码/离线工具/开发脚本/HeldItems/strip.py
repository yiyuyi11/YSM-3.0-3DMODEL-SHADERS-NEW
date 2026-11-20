"""strip.py <prefix> <clip> <item> <left0/1> <tool0/1> <shield0/1> <out.png> [frames]: front+side views over a cycle."""
import sys
import numpy as np
from PIL import Image
import fk
from collide import render
prefix, clip, item, left, tool, shield, out = sys.argv[1:8]
n = int(sys.argv[8]) if len(sys.argv) > 8 else 4
fk.ENV.update(tool=tool == "1", shield=shield == "1")
m = fk.Model(prefix)
L = m.anims[clip].get("animation_length", 1)
tiles = []
for i in range(n):
    render(prefix, [clip, "parallel4"], item, left == "1", L * i / n, "/tmp/_t.png")
    im = Image.open("/tmp/_t.png")
    tiles.append(im.crop((0, 0, im.width * 2 // 3, im.height)).resize((im.width * 2 // 3 // 2, im.height // 2)))
w = sum(t.width for t in tiles)
sheet = Image.new("RGB", (w, tiles[0].height), "white")
x = 0
for t in tiles:
    sheet.paste(t, (x, 0)); x += t.width
sheet.save(out)
print(out)
