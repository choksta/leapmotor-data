"""对 dump/ 下的接口原始响应做脱敏：保留结构与字段名，抹掉个人与车辆标识。

保留原文件结构是为了以后还能照着它写解析；被替换的只是"值"。
"""
import json
import os
import re

DUMP = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "..", "dump")

VIN_RE = re.compile(r"\bLFZ[0-9A-Z]{14}\b")
PHONE_RE = re.compile(r"\b1[3-9]\d{9}\b")
# 车端位置：珠三角一带的经纬度，只抹小数位足够长的坐标，避免误伤版本号
COORD_RE = re.compile(r"\b(1[0-3]\d\.\d{4,}|2\d\.\d{4,})\b")
JWT_RE = re.compile(r"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{4,}\b")
LONG_B64_RE = re.compile(r"\b[A-Za-z0-9+/_\-]{40,}={0,2}\b")

SENSITIVE_KEYS = {
    "vin": "LFZ00000000000000",
    "sourceVin": "LFZ00000000000000",
    "source_vin": "LFZ00000000000000",
    "phone": "13800138000",
    "phoneNo": "13800138000",
    "mobile": "13800138000",
    "nickName": "用户",
    "nick_name": "用户",
    "fileUrl": "https://example.invalid/redacted",
    "accountId": "0",
    "account_id": "0",
    "userId": "0",
}

# 车端信号号里属于位置的三组（GCJ/WGS），值换成公开参考点
LOCATION_SIGNAL_IDS = {"2", "3", "2190", "2191", "3724", "3725"}


def scrub_string(s: str) -> str:
    s = VIN_RE.sub("LFZ00000000000000", s)
    s = JWT_RE.sub("REDACTED_JWT", s)
    s = re.sub(r"(Signature=)[^&\s\"]+", r"\1REDACTED", s)
    s = re.sub(r"(OSSAccessKeyId=)[^&\s\"]+", r"\1REDACTED", s)
    s = PHONE_RE.sub("13800138000", s)
    # 坐标：只处理带 4 位以上小数的，且限定在国内经纬度区间
    def _coord(m):
        v = float(m.group(1))
        if 113.0 <= v <= 114.5 or 22.5 <= v <= 24.5:
            return "0.000000"
        return m.group(1)

    s = re.sub(r"\b(1[0-3]\d\.\d{4,}|2[0-9]\.\d{4,})\b", _coord, s)
    return s


def scrub(obj, key=None):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if k in SENSITIVE_KEYS and isinstance(v, (str, int)):
                out[k] = SENSITIVE_KEYS[k]
            elif k in LOCATION_SIGNAL_IDS and isinstance(v, (str, int, float)):
                out[k] = "0.000000"
            else:
                out[k] = scrub(v, k)
        return out
    if isinstance(obj, list):
        return [scrub(x, key) for x in obj]
    if isinstance(obj, str):
        # 过长的 base64 / 十六进制串一律视为密钥材料
        if len(obj) >= 40 and LONG_B64_RE.fullmatch(obj):
            return "REDACTED"
        return scrub_string(obj)
    return obj


def main():
    total = changed = 0
    for name in sorted(os.listdir(DUMP)):
        if not name.endswith(".json"):
            continue
        p = os.path.join(DUMP, name)
        total += 1
        raw = open(p, encoding="utf-8").read()
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            print("跳过（非 JSON）:", name)
            continue
        new = json.dumps(scrub(data), ensure_ascii=False, indent=1)
        if new != raw:
            open(p, "w", encoding="utf-8", newline="\n").write(new + "\n")
            changed += 1
    print(f"处理 {total} 个文件，改写 {changed} 个")


if __name__ == "__main__":
    main()
