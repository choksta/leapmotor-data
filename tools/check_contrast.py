"""校验配色方案的 WCAG 对比度。

用法：python tools/check_contrast.py
任何一行 FAIL 都不许进 Theme.kt —— 这类问题脚本能查出来，肉眼查不出来。
"""
import sys

def lum(h):
    h = h.lstrip('#')
    if len(h) == 8:
        h = h[2:]
    c = [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    c = [x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4 for x in c]
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]


def cr(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


A_L = dict(primary='15607A', onPrimary='FFFFFF', primaryContainer='C6E9F5', onPrimaryContainer='001F27',
           secondary='4C635B', onSecondary='FFFFFF', secondaryContainer='CFE9DE', onSecondaryContainer='09201A',
           tertiary='7A5900', onTertiary='FFFFFF', tertiaryContainer='FFDF9B', onTertiaryContainer='261A00',
           background='F7F9FA', onBackground='191C1D', surface='F7F9FA', onSurface='191C1D',
           surfaceContainer='EBEFF0', surfaceVariant='DBE4E8', onSurfaceVariant='3F484B',
           outline='6F797C', outlineVariant='BFC8CB', error='BA1A1A', errorContainer='FFDAD6', onErrorContainer='410002')
A_D = dict(primary='7BD1EE', onPrimary='00363F', primaryContainer='004E5C', onPrimaryContainer='C6E9F5',
           secondary='B3CCC1', onSecondary='1E352D', secondaryContainer='354B43', onSecondaryContainer='CFE9DE',
           tertiary='EBC248', onTertiary='3F2E00', tertiaryContainer='5C4300', onTertiaryContainer='FFDF9B',
           background='101415', onBackground='E0E3E4', surface='101415', onSurface='E0E3E4',
           surfaceContainer='1B1F20', surfaceVariant='3F484B', onSurfaceVariant='BFC8CB',
           outline='899396', outlineVariant='3F484B', error='FFB4AB', errorContainer='93000A', onErrorContainer='FFDAD6')

B_L = dict(primary='006A66', onPrimary='FFFFFF', primaryContainer='9CF2EC', onPrimaryContainer='00201E',
           secondary='4A6361', onSecondary='FFFFFF', secondaryContainer='CCE8E4', onSecondaryContainer='071F1D',
           tertiary='8B5000', onTertiary='FFFFFF', tertiaryContainer='FFDDB8', onTertiaryContainer='2C1600',
           background='F6FAF9', onBackground='171D1C', surface='F6FAF9', onSurface='171D1C',
           surfaceContainer='E9EFEE', surfaceVariant='DAE5E3', onSurfaceVariant='3F4947',
           outline='6F7978', outlineVariant='BEC9C7', error='BA1A1A', errorContainer='FFDAD6', onErrorContainer='410002')
B_D = dict(primary='80D5CF', onPrimary='003733', primaryContainer='004F4B', onPrimaryContainer='9CF2EC',
           secondary='B0CCC8', onSecondary='1B3532', secondaryContainer='324B48', onSecondaryContainer='CCE8E4',
           tertiary='FFB960', onTertiary='4A2800', tertiaryContainer='6A3C00', onTertiaryContainer='FFDDB8',
           background='0E1514', onBackground='DCE5E3', surface='0E1514', onSurface='DCE5E3',
           surfaceContainer='1A2120', surfaceVariant='3F4948', onSurfaceVariant='BFC9C7',
           outline='899392', outlineVariant='3F4948', error='FFB4AB', errorContainer='93000A', onErrorContainer='FFDAD6')

C_L = dict(primary='7A5900', onPrimary='FFFFFF', primaryContainer='FFDF9B', onPrimaryContainer='261A00',
           secondary='6B5D45', onSecondary='FFFFFF', secondaryContainer='F3E0C3', onSecondaryContainer='251A06',
           tertiary='14656F', onTertiary='FFFFFF', tertiaryContainer='A8EFF8', onTertiaryContainer='001F24',
           background='FFF8F0', onBackground='1E1B14', surface='FFF8F0', onSurface='1E1B14',
           surfaceContainer='F5ECE0', surfaceVariant='E6DFD1', onSurfaceVariant='4A4639',
           outline='7F7663', outlineVariant='D1C7B8', error='BA1A1A', errorContainer='FFDAD6', onErrorContainer='410002')
C_D = dict(primary='EBC248', onPrimary='3F2E00', primaryContainer='5C4300', onPrimaryContainer='FFDF9B',
           secondary='D6C4A6', onSecondary='3A2F1B', secondaryContainer='524633', onSecondaryContainer='F3E0C3',
           tertiary='5FD5E6', onTertiary='00363D', tertiaryContainer='004F58', onTertiaryContainer='A8EFF8',
           background='16120C', onBackground='E9E2D6', surface='16120C', onSurface='E9E2D6',
           surfaceContainer='241F18', surfaceVariant='4C4636', onSurfaceVariant='CFC6B4',
           outline='998F7C', outlineVariant='4C4636', error='FFB4AB', errorContainer='93000A', onErrorContainer='FFDAD6')

PAIRS = [
    ('onPrimary', 'primary', 4.5),
    ('onPrimaryContainer', 'primaryContainer', 4.5),
    ('onSecondary', 'secondary', 4.5),
    ('onSecondaryContainer', 'secondaryContainer', 4.5),
    ('onTertiary', 'tertiary', 4.5),
    ('onTertiaryContainer', 'tertiaryContainer', 4.5),
    ('onSurface', 'surface', 4.5),
    ('onSurfaceVariant', 'surface', 4.5),
    ('onSurface', 'surfaceContainer', 4.5),
    ('onSurfaceVariant', 'surfaceContainer', 4.5),
    ('onBackground', 'background', 4.5),
    ('onErrorContainer', 'errorContainer', 4.5),
    ('primary', 'surface', 4.5),
    ('primary', 'surfaceContainer', 3.0),
    ('tertiary', 'surface', 4.5),
    ('secondary', 'surface', 4.5),
    ('onSurfaceVariant', 'surfaceVariant', 4.5),
    ('outline', 'surface', 3.0),
    ('outline', 'surfaceContainer', 3.0),
    ('error', 'surface', 4.5),
]

SETS = [('A 深海青 Light', A_L), ('A 深海青 Dark', A_D),
        ('B 电光青 Light', B_L), ('B 电光青 Dark', B_D),
        ('C 琥珀暖 Light', C_L), ('C 琥珀暖 Dark', C_D)]

fails = 0
for name, cs in SETS:
    bad = []
    worst_text = 99.0   # 4.5 门槛组（正文/小图标）
    worst_non = 99.0    # 3.0 门槛组（非文本图形）
    for fg, bg, need in PAIRS:
        v = cr(cs[fg], cs[bg])
        if need >= 4.5:
            worst_text = min(worst_text, v)
        else:
            worst_non = min(worst_non, v)
        if v < need:
            bad.append('%s on %s = %.2f < %.1f' % (fg, bg, v, need))
    if bad:
        fails += 1
        print('[FAIL] %s' % name)
        for b in bad:
            print('        ' + b)
    else:
        print('[ OK ] %-14s 正文组最低 %.2f   图形组最低 %.2f' % (name, worst_text, worst_non))

print('')
print('结论：%d 套通过 / %d 套共 %d 组' % (len(SETS) - fails, len(SETS), len(SETS)))
sys.exit(1 if fails else 0)
