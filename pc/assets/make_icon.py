# -*- coding: utf-8 -*-
"""从定稿源图规整 1:1（保留界面深紫底色），输出 512 PNG 预览与多尺寸 ICO。"""
from PIL import Image
import os

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, "icon-source.png")
ICO = os.path.join(BASE, "icon.ico")
PREVIEW = os.path.join(BASE, "icon-512.png")
BG = (26, 20, 64, 255)  # #1A1440 界面背景色

im = Image.open(SRC).convert("RGBA")
w, h = im.size
side = max(w, h)
canvas = Image.new("RGBA", (side, side), BG)
canvas.paste(im, ((side - w) // 2, (side - h) // 2), im)

canvas.resize((512, 512), Image.LANCZOS).save(PREVIEW)

sizes = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
master = canvas.resize((256, 256), Image.LANCZOS)
master.save(ICO, format="ICO", sizes=sizes)

print("source:", im.size, "canvas:", canvas.size)
print("ico:", ICO, os.path.getsize(ICO), "bytes")
print("preview:", PREVIEW, os.path.getsize(PREVIEW), "bytes")
