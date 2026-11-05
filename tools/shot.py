"""一键试配色：改源码 → 编译 → 装真机 → 浅色/深色各截一张。

用法（在项目根目录）：
    python tools/shot.py                # 三套各跑一遍
    python tools/shot.py Amber          # 只跑某一套
    python tools/shot.py ElectricMint DeepTeal

跑完看桌面上的对照图：
    python tools/compare_sheet.py

注意：这个脚本会**真的改动** Palette.kt 里的 ActivePalette。跑完最后那个
参数就是最终生效的配色，想回到原来的样子就把它放在最后一个。
"""
import os
import re
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PALETTE_KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "haojay",
                          "leapmotor", "ui", "Palette.kt")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
GRADLEW = os.path.join(ROOT, "gradlew.bat")
PKG = "com.haojay.leapmotor"
ACTIVITY = PKG + "/.MainActivity"
TEMP = os.environ.get("TEMP", ".")
SHORT = {"DeepTeal": "A", "ElectricMint": "B", "Amber": "C"}
ALL = ["DeepTeal", "ElectricMint", "Amber"]


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, **kw)
    if r.returncode != 0:
        sys.stderr.write(r.stdout.decode("utf-8", "replace") + "\n")
        sys.stderr.write(r.stderr.decode("utf-8", "replace") + "\n")
        sys.exit("命令失败: " + " ".join(cmd))
    return r


def adb(*args):
    return run(["adb", *args])


def set_palette(key):
    if key not in SHORT:
        sys.exit("未知配色: %s（可选 %s）" % (key, ", ".join(ALL)))
    with open(PALETTE_KT, encoding="utf-8") as f:
        src = f.read()
    new, n = re.subn(r"val ActivePalette: Palette = Palette\.\w+",
                     "val ActivePalette: Palette = Palette." + key, src)
    if n != 1:
        sys.exit("没找到 ActivePalette 声明（匹配到 %d 处），请手动检查 Palette.kt" % n)
    # 必须显式 LF：Windows 上默认写 CRLF，会让 git diff 从几行膨胀到上千行
    with open(PALETTE_KT, "w", encoding="utf-8", newline="\n") as f:
        f.write(new)


def build_and_install():
    run(["cmd", "/c", GRADLEW, ":app:assembleDebug", "--console=plain", "-q"], cwd=ROOT)
    run(["adb", "install", "-r", APK])


def shot(tag, dark):
    adb("shell", "cmd", "uimode", "night", "yes" if dark else "no")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(5 if not dark else 4)
    raw = run(["adb", "exec-out", "screencap", "-p"]).stdout
    # 旧版 adb 在 Windows 上会把 \n 变成 \r\n，破坏 PNG 头
    if not raw.startswith(b"\x89PNG"):
        raw = raw.replace(b"\r\n", b"\n")
    out = os.path.join(TEMP, "palette_shot_%s_%s.png" % (tag, "dark" if dark else "light"))
    with open(out, "wb") as f:
        f.write(raw)
    print("  " + out)


def main():
    keys = sys.argv[1:] or ALL
    for k in keys:
        print("[%s %s]" % (k, SHORT[k]))
        set_palette(k)
        build_and_install()
        shot(SHORT[k], False)
        shot(SHORT[k], True)
    adb("shell", "cmd", "uimode", "night", "no")
    print("\n当前生效配色: %s" % keys[-1])
    print("出对照图: python tools/compare_sheet.py")


if __name__ == "__main__":
    main()
