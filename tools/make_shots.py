"""把真机截图压成 README 用的展示图。

用法：
    python tools/make_shots.py <源目录>

源目录里需要这些文件（本轮用 adb 截的原始 1280×2772 图）：
    car-light.png  car-dark.png
    trend-light.png trend-dark.png
    control-light.png control-dark.png

输出到仓库 docs/images/，宽度压到 540（README 里三张并排、每张显示约 240px，
540 已经是 2 倍图，再大只是浪费流量）。
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "images")
WIDTH = 540
# 裁掉系统状态栏。它本身不含身份信息，但运营商会露出来，
# 而且产品展示图不带状态栏更干净。140px 是按 1280×2772 实测的，够切掉整条。
CROP_TOP = 140

NAMES = [
    "car-light", "car-dark",
    "trend-light", "trend-dark",
    "control-light", "control-dark",
]


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("TEMP", ".")
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name in NAMES:
        p = os.path.join(src, name + ".png")
        if not os.path.exists(p):
            sys.exit("缺图: " + p)
        im = Image.open(p).convert("RGB")
        if im.width > CROP_TOP:
            im = im.crop((0, CROP_TOP, im.width, im.height))
            # 裁完顶边太紧（趋势页是裸标题，没有卡片托底），补一条同色留白，
            # 颜色取左上角像素 —— 那里一定是背景，不会是内容
            bg = im.getpixel((4, 4))
            pad = Image.new("RGB", (im.width, im.height + 30), bg)
            pad.paste(im, (0, 30))
            im = pad
        h = round(im.height * WIDTH / im.width)
        im = im.resize((WIDTH, h), Image.LANCZOS)
        dst = os.path.join(OUT, name + ".png")
        # optimize + 调色板量化：截图是大色块 UI，量化几乎无损，体积能降一半
        im.quantize(colors=192, method=Image.MEDIANCUT).save(dst, optimize=True)
        kb = os.path.getsize(dst) / 1024
        total += kb
        print("%-20s %4.0f KB  %dx%d" % (name + ".png", kb, WIDTH, h))
    print("合计 %.0f KB" % total)


if __name__ == "__main__":
    main()
