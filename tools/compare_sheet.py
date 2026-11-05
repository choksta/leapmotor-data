"""把三套配色的真机截图拼成一张对照图。

用法：
    python tools/compare_sheet.py

依赖 tools/shot.py 产出的 6 张截图（%TEMP%/palette_shot_*.png）。
输出到桌面，文件名带日期，方便和上一版对比。
"""
import datetime
import os
import sys

from PIL import Image, ImageDraw, ImageFont

TEMP = os.environ.get("TEMP", ".")
DESKTOP = os.path.join(os.path.expanduser("~"), "Desktop")

CASES = [
    ("A  深海青", "palette_shot_A_light.png", "palette_shot_A_dark.png"),
    ("B  电光青", "palette_shot_B_light.png", "palette_shot_B_dark.png"),
    ("C  琥珀暖", "palette_shot_C_light.png", "palette_shot_C_dark.png"),
]

BG = (26, 26, 28)
FG = (240, 240, 240)
DIM = (150, 150, 155)
CARD_W = 420
PAD = 28
LABEL_H = 52
ROW_LABEL_W = 74


def font(size):
    for path in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhbd.ttc"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                pass
    return ImageFont.load_default()


def load(name):
    p = os.path.join(TEMP, name)
    if not os.path.exists(p):
        print("缺图: " + p, file=sys.stderr)
        sys.exit(1)
    im = Image.open(p).convert("RGB")
    h = int(im.height * CARD_W / im.width)
    return im.resize((CARD_W, h), Image.LANCZOS)


def main():
    cols = [(t, load(a), load(b)) for t, a, b in CASES]
    card_h = cols[0][1].height

    total_w = PAD + ROW_LABEL_W + len(cols) * (CARD_W + PAD)
    total_h = PAD + LABEL_H + card_h + PAD + card_h + PAD

    canvas = Image.new("RGB", (total_w, total_h), BG)
    d = ImageDraw.Draw(canvas)
    f_col = font(30)
    f_row = font(24)

    for i, (title, light, dark) in enumerate(cols):
        x = PAD + ROW_LABEL_W + i * (CARD_W + PAD)
        d.text((x, PAD + 8), title, fill=FG, font=f_col)
        canvas.paste(light, (x, PAD + LABEL_H))
        canvas.paste(dark, (x, PAD + LABEL_H + card_h + PAD))

    y_light = PAD + LABEL_H + 10
    y_dark = PAD + LABEL_H + card_h + PAD + 10
    d.text((PAD, y_light), "浅色", fill=DIM, font=f_row)
    d.text((PAD, y_dark), "深色", fill=DIM, font=f_row)

    stamp = datetime.date.today().strftime("%Y%m%d")
    out = os.path.join(DESKTOP, "零跑App配色对照_" + stamp + ".png")
    canvas.save(out)
    print(out)


if __name__ == "__main__":
    main()
