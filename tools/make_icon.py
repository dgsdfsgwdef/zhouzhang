#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""周账 App 图标生成器（定稿版）

设计
  七段环 = 一周七天，首段用琥珀色标记"本周"。
  内孔收紧到 50%，让整体像一枚硬币/徽章，而不是加载动画。

产出
  design/icon/                 PNG / SVG 预览
  design/icon/android/res/     Android 自适应图标全套资源
"""

import math
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "design" / "icon"
RES = OUT / "android" / "res"

SS = 4
BG = "#15191E"
INK = "#EDE7DA"
AMBER = "#F2A93B"

CX = CY = 54.0
R_MID = 25.0
STROKE = 15.0
GAP_DEG = 4.5
SEG_DEG = (360 - 7 * GAP_DEG) / 7
ACCENT_INDEX = 0


def h2(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def segments(scale=1.0):
    """返回 [(x1,y1,x2,y2,start_deg,end_deg,is_accent)]，坐标为 108 视口"""
    out = []
    r = R_MID * scale
    for i in range(7):
        a1 = -90 + i * (SEG_DEG + GAP_DEG)
        a2 = a1 + SEG_DEG
        x1 = CX + (r * math.cos(math.radians(a1)) - CX) * 1 + CX * 0
        # 以画布中心为圆心缩放
        x1 = CX + r * math.cos(math.radians(a1))
        y1 = CY + r * math.sin(math.radians(a1))
        x2 = CX + r * math.cos(math.radians(a2))
        y2 = CY + r * math.sin(math.radians(a2))
        out.append((x1, y1, x2, y2, a1, a2, i == ACCENT_INDEX))
    return out


def render(size, scale=1.0, bg=BG):
    px = size * SS
    img = Image.new("RGBA", (px, px), h2(bg) + (255,))
    d = ImageDraw.Draw(img)
    k = px / 108.0
    r_out = (R_MID * scale + STROKE * scale / 2) * k
    box = [CX * k - r_out, CY * k - r_out, CX * k + r_out, CY * k + r_out]
    w = int(round(STROKE * scale * k))
    for x1, y1, x2, y2, a1, a2, accent in segments(scale):
        d.arc(box, a1, a2, fill=h2(AMBER if accent else INK) + (255,), width=w)
    return img.resize((size, size), Image.LANCZOS)


def path_data(scale=1.0):
    r = R_MID * scale
    return [(f"M{CX + r * math.cos(math.radians(a1)):.2f},{CY + r * math.sin(math.radians(a1)):.2f} "
             f"A{r:.2f},{r:.2f} 0 0 1 "
             f"{CX + r * math.cos(math.radians(a2)):.2f},{CY + r * math.sin(math.radians(a2)):.2f}",
             accent)
            for _, _, _, _, a1, a2, accent in segments(scale)]


def svg_doc(scale=1.0, size=512):
    body = "\n  ".join(
        f'<path d="{d}" fill="none" stroke="{AMBER if a else INK}" '
        f'stroke-width="{STROKE * scale:.2f}" stroke-linecap="butt"/>'
        for d, a in path_data(scale))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">
  <rect width="108" height="108" fill="{BG}"/>
  {body}
</svg>
'''


def vector_xml(monochrome=False, scale=1.0):
    ink = "#FFFFFF" if monochrome else INK
    amber = "#FFFFFF" if monochrome else AMBER
    parts = []
    for d, accent in path_data(scale):
        parts.append(f'''    <path
        android:strokeColor="{amber if accent else ink}"
        android:strokeWidth="{STROKE * scale:.2f}"
        android:strokeLineCap="butt"
        android:pathData="{d}" />''')
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 周账 · 七段环：一周七天，首段高亮 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{chr(10).join(parts)}
</vector>
'''


def mask(img, shape):
    size = img.size[0]
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif shape == "squircle":
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.26), fill=255)
    else:
        d.rectangle([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), m)
    return out


def _font(size=15):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf",
              "/System/Library/Fonts/PingFang.ttc"):
        if Path(p).exists():
            from PIL import ImageFont
            return ImageFont.truetype(p, size)
    return None


def preview(path):
    W, H = 900, 560
    LIGHT_H = 440
    sheet = Image.new("RGB", (W, H), (248, 247, 243))
    d = ImageDraw.Draw(sheet)
    f, fs = _font(15), _font(13)

    big = render(200)
    sheet.paste(big, (40, 40))
    d.text((40, 258), "周账 · 七段环", fill=(45, 45, 45), font=f)
    d.text((40, 282), "一周七天，首段琥珀色标记本周", fill=(110, 110, 110), font=fs)

    x = 330
    for shape, label in (("circle", "圆形遮罩"), ("squircle", "方圆遮罩"), ("square", "方形")):
        im = mask(render(130), shape)
        sheet.paste(im, (x, 50), im)
        d.text((x, 192), label, fill=(90, 90, 90), font=fs)
        x += 155

    d.text((330, 228), "遮罩检验：环形在三种裁切下均未触边", fill=(110, 110, 110), font=fs)
    d.text((330, 252), "自适应图标安全区 66/108，环外径 65", fill=(150, 150, 150), font=fs)

    base = 420
    x = 40
    for s in (72, 56, 40, 32, 24):
        im = render(s)
        sheet.paste(im, (x, base - s), im)
        x += s + 24

    d.rectangle([0, LIGHT_H, W, H], fill=(20, 22, 26))
    base = H - 20
    x = 40
    for s in (72, 56, 40, 32, 24):
        im = render(s)
        sheet.paste(im, (x, base - s), im)
        x += s + 24
    d.text((x + 12, base - 22), "24px 下仍可辨认", fill=(120, 120, 120), font=fs)

    sheet.save(path)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)

    (OUT / "icon.svg").write_text(svg_doc(), encoding="utf-8")
    for s in (512, 192, 48):
        render(s).save(OUT / f"icon-{s}.png")
    preview(OUT / "preview.png")

    # 自适应图标：前景留安全区，背景纯色
    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(vector_xml(), encoding="utf-8")
    (RES / "drawable" / "ic_launcher_monochrome.xml").write_text(
        vector_xml(monochrome=True), encoding="utf-8")
    (RES / "values" / "ic_launcher_background.xml").write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{BG}</color>
</resources>
''', encoding="utf-8")

    adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (RES / "mipmap-anydpi-v26" / name).write_text(adaptive, encoding="utf-8")

    # 旧版（API < 26）位图，标记略微放大以填满方形
    for folder, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)):
        p = RES / f"mipmap-{folder}"
        p.mkdir(parents=True, exist_ok=True)
        render(size, scale=1.18).save(p / "ic_launcher.png")

    print("图标已生成")
    for f in sorted(OUT.rglob("*")):
        if f.is_file():
            print(" ", f.relative_to(ROOT))


if __name__ == "__main__":
    main()
