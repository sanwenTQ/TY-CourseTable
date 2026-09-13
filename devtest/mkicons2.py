#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图标生成 v2：alpha 软边/渐变 + 彩色启动图标。坐标空间 0..24，4x4 超采样。"""
import math, os, struct, zlib

OUT = 'app/res'
SS = 4
U = 24.0

def rrect(x0, y0, x1, y1, r):
    def f(x, y):
        if x < x0 or x > x1 or y < y0 or y > y1: return False
        cx = min(max(x, x0 + r), x1 - r); cy = min(max(y, y0 + r), y1 - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    return f

def circle(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r

def ring(cx, cy, r, w):
    return lambda x, y: abs(math.hypot(x - cx, y - cy) - r) <= w / 2

def seg(x1, y1, x2, y2, w):
    def f(x, y):
        dx, dy = x2 - x1, y2 - y1
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / L2))
        px, py = x1 + t * dx, y1 + t * dy
        return (x - px) ** 2 + (y - py) ** 2 <= (w / 2) ** 2
    return f

def poly(pts):
    def f(x, y):
        inside = False; n = len(pts)
        for i in range(n):
            x1, y1 = pts[i]; x2, y2 = pts[(i + 1) % n]
            if (y1 > y) != (y2 > y):
                xin = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                if x < xin: inside = not inside
        return inside
    return f

def gear(cx, cy, r_body=8.8, r_hole=3.3, teeth=8, r_tooth=11.0, half=0.16):
    def f(x, y):
        dx, dy = x - cx, y - cy
        d = math.hypot(dx, dy)
        if d < r_hole or d > r_tooth: return False
        if d <= r_body: return True
        ang = math.atan2(dy, dx); step = 2 * math.pi / teeth
        a = ((ang % step) + step / 2) % step - step / 2
        return abs(a) <= half
    return f

def star4(cx, cy, r, w=0.62):
    def f(x, y):
        dx, dy = abs(x - cx) / r, abs(y - cy) / r
        return (dx ** 0.55 + dy ** 0.55) <= 1.0 and (dx < w or dy < w or dx ** 0.55 + dy ** 0.55 <= 0.72)
    return f

def arc(cx, cy, r, a0, a1, w):
    def f(x, y):
        dx, dy = x - cx, y - cy
        d = math.hypot(dx, dy)
        if abs(d - r) > w / 2: return False
        ang = math.degrees(math.atan2(dy, dx)) % 360
        a0n, a1n = a0 % 360, a1 % 360
        if a0n <= a1n: return a0n <= ang <= a1n
        return ang >= a0n or ang <= a1n
    return f

def uni(*fs):   return lambda x, y: any(f(x, y) for f in fs)
def sub(a, b):  return lambda x, y: a(x, y) and not b(x, y)
def inter(a, b):return lambda x, y: a(x, y) and b(x, y)

def A(pred, alpha=1.0):
    return lambda x, y: (alpha if pred(x, y) else 0.0)

def soft(cx, cy, r0, r1, a_max=1.0):
    def f(x, y):
        d = math.hypot(x - cx, y - cy)
        if d <= r0: return a_max
        if d >= r1: return 0.0
        return a_max * (r1 - d) / (r1 - r0)
    return f

def over(*layers):
    def f(x, y):
        a = 0.0
        for L in layers:
            v = L(x, y)
            a = v + a * (1 - v)
        return a
    return f

def render_alpha(fn, size, color=(255, 255, 255)):
    out = bytearray(size * size * 4)
    inv = U / size
    for py in range(size):
        for px in range(size):
            cov = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    cov += fn((px + (sx + 0.5) / SS) * inv, (py + (sy + 0.5) / SS) * inv)
            a = int(255 * cov / (SS * SS) + 0.5)
            i = (py * size + px) * 4
            out[i], out[i + 1], out[i + 2], out[i + 3] = color[0], color[1], color[2], a
    return bytes(out)

def render_color(fn, size):
    out = bytearray(size * size * 4)
    inv = U / size
    for py in range(size):
        for px in range(size):
            r = g = b = acc = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    cr, cg, cb, ca = fn((px + (sx + 0.5) / SS) * inv, (py + (sy + 0.5) / SS) * inv)
                    r += cr * ca; g += cg * ca; b += cb * ca; acc += ca
            n = SS * SS
            i = (py * size + px) * 4
            if acc > 0:
                out[i] = int(r / acc + 0.5); out[i + 1] = int(g / acc + 0.5); out[i + 2] = int(b / acc + 0.5)
            out[i + 3] = int(255 * acc / n + 0.5)
    return bytes(out)

def write_png(path, size, rgba):
    raw = b''.join(b'\x00' + rgba[y * size * 4:(y + 1) * size * 4] for y in range(size))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    open(path, 'wb').write(png)

def preview(name, fn, n=24):
    print('--- ' + name + ' ---')
    for py in range(n):
        row = ''
        for px in range(n):
            a = fn((px + 0.5) * U / n, (py + 0.5) * U / n)
            row += '##' if a > 0.6 else ('++' if a > 0.2 else '  ')
        print(row)

I = {}
I['ic_import'] = A(uni(
    seg(12, 3.2, 12, 14.6, 2.3),
    poly([(7.3, 12.6), (16.7, 12.6), (12, 18.6)]),
    seg(3.6, 20.4, 20.4, 20.4, 2.4),
    seg(3.6, 20.4, 3.6, 16.2, 2.4),
    seg(20.4, 20.4, 20.4, 16.2, 2.4)))
I['ic_settings'] = A(sub(uni(gear(12, 12), ring(12, 12, 7.8, 2.0)), circle(12, 12, 3.0)))
I['ic_palette'] = A(sub(
    circle(12, 11.8, 9.3),
    uni(circle(19.0, 16.2, 4.7), circle(7.6, 8.6, 1.55), circle(6.6, 13.8, 1.55),
        circle(10.0, 17.8, 1.55), circle(13.8, 7.2, 1.55))))
I['ic_image'] = A(sub(
    rrect(2.8, 4.6, 21.2, 19.4, 3.2),
    uni(poly([(4.6, 19.4), (10.2, 11.8), (13.6, 15.4), (16.0, 12.8), (20.2, 18.4), (20.2, 19.4)]),
        circle(16.6, 9.6, 2.0))))
I['ic_calendar'] = A(uni(
    sub(rrect(2.8, 4.4, 21.2, 21.0, 3.0), rrect(4.2, 5.8, 19.8, 19.6, 2.0)),
    seg(2.8, 9.2, 21.2, 9.2, 2.0),
    seg(7.6, 2.6, 7.6, 6.4, 2.0), seg(16.4, 2.6, 16.4, 6.4, 2.0),
    circle(8.2, 13.4, 1.4), circle(12, 13.4, 1.4), circle(15.8, 13.4, 1.4),
    circle(8.2, 17.4, 1.4), circle(12, 17.4, 1.4)))
I['ic_today'] = A(uni(
    sub(rrect(3.0, 4.6, 21.0, 21.0, 3.4), rrect(5.2, 6.8, 18.8, 18.8, 2.4)),
    seg(3.0, 9.6, 21.0, 9.6, 1.8),
    seg(7.8, 2.8, 7.8, 6.6, 1.9), seg(16.2, 2.8, 16.2, 6.6, 1.9),
    circle(12, 15.0, 2.6)))
I['ic_clock'] = A(uni(
    sub(ring(12, 12, 9.0, 2.1), circle(12, 12, 7.9)),
    seg(12, 12, 12, 6.6, 1.8), seg(12, 12, 16.0, 13.8, 1.8)))
I['ic_radius'] = A(uni(
    sub(rrect(3.0, 3.0, 21.0, 21.0, 7.0), rrect(4.6, 4.6, 19.4, 19.4, 5.6)),
    seg(3.0, 10.0, 10.0, 10.0, 1.5), seg(10.0, 3.0, 10.0, 10.0, 1.5)))
I['ic_grid'] = A(uni(
    rrect(3.0, 3.0, 10.8, 10.8, 2.4), rrect(13.2, 3.0, 21.0, 10.8, 2.4),
    rrect(3.0, 13.2, 10.8, 21.0, 2.4), rrect(13.2, 13.2, 21.0, 21.0, 2.4)))
I['ic_list'] = A(uni(
    circle(5.0, 6.6, 1.8), circle(5.0, 12.0, 1.8), circle(5.0, 17.4, 1.8),
    seg(9.2, 6.6, 20.6, 6.6, 2.2), seg(9.2, 12.0, 20.6, 12.0, 2.2), seg(9.2, 17.4, 20.6, 17.4, 2.2)))
I['ic_back'] = A(uni(seg(14.6, 5.2, 7.6, 12.0, 2.3), seg(7.6, 12.0, 14.6, 18.8, 2.3)))
I['ic_add'] = A(uni(seg(12, 5.0, 12, 19.0, 2.4), seg(5.0, 12, 19.0, 12, 2.4)))
I['ic_close'] = A(uni(seg(6.4, 6.4, 17.6, 17.6, 2.3), seg(17.6, 6.4, 6.4, 17.6, 2.3)))
I['ic_check'] = A(uni(seg(5.4, 12.6, 10.0, 17.0, 2.4), seg(10.0, 17.0, 18.8, 7.4, 2.4)))
I['ic_more'] = A(uni(circle(12, 5.6, 1.9), circle(12, 12, 1.9), circle(12, 18.4, 1.9)))
I['ic_opacity'] = A(uni(
    sub(ring(12, 12, 9.0, 2.0), circle(12, 12, 7.9)),
    inter(circle(12, 12, 8.0), lambda x, y: y >= 12.0),
    seg(3.4, 12.0, 20.6, 12.0, 1.6)))
I['ic_blur'] = over(
    soft(11.0, 12.0, 4.2, 8.6, 1.0),
    soft(17.8, 8.4, 1.0, 3.6, 0.75),
    soft(18.8, 16.8, 0.9, 3.2, 0.6))
I['ic_sparkle'] = A(star4(12, 12, 9.8))
I['ic_sparkles'] = over(
    A(star4(9.0, 14.0, 7.6)),
    A(star4(18.2, 6.4, 4.0)),
    A(star4(19.0, 17.6, 3.0), 0.85))
I['ic_kawaii'] = A(sub(circle(12, 12, 10.0),
    uni(circle(8.4, 9.4, 2.1), circle(15.6, 9.4, 2.1), arc(12, 12.4, 4.8, 20, 160, 1.7))))

PRIMARY = (0x66, 0xCC, 0xFF)
PRIMARY_D = (0x2E, 0x9B, 0xD6)
BODY = (255, 255, 255)
FACE = (0x2B, 0x3A, 0x46)
BLUSH = (0xFF, 0x9A, 0xB8)
HEADER = (0x33, 0xB0, 0xE8)

def launcher(x, y):
    if not rrect(0.6, 0.6, 23.4, 23.4, 5.4)(x, y):
        return (0, 0, 0, 0.0)
    t = min(1.0, max(0.0, (y - 0.6) / 22.8))
    bg = tuple(int(PRIMARY[i] + (PRIMARY_D[i] - PRIMARY[i]) * t) for i in range(3))
    r, g, b, a = float(bg[0]), float(bg[1]), float(bg[2]), 1.0
    def blend(c, alpha):
        nonlocal r, g, b, a
        r = c[0] * alpha + r * (1 - alpha)
        g = c[1] * alpha + g * (1 - alpha)
        b = c[2] * alpha + b * (1 - alpha)
        a = alpha + a * (1 - alpha)
    if rrect(3.6, 5.4, 20.4, 20.4, 3.0)(x, y):
        blend(BODY if y > 9.6 else HEADER, 1.0)
    if seg(7.6, 3.4, 7.6, 7.0, 1.9)(x, y) or seg(16.4, 3.4, 16.4, 7.0, 1.9)(x, y):
        blend((255, 255, 255), 1.0)
    for ex in (8.9, 15.1):
        if circle(ex, 14.4, 1.75)(x, y): blend(FACE, 1.0)
        if circle(ex - 0.55, 13.85, 0.55)(x, y): blend((255, 255, 255), 1.0)
    for bx in (6.9, 17.1):
        d = math.hypot(x - bx, y - 17.0)
        if d < 2.0: blend(BLUSH, max(0.0, 1.0 - d / 2.0) * 0.85)
    if arc(12, 14.6, 3.3, 35, 145, 1.35)(x, y): blend(FACE, 1.0)
    for (sx, sy, sr) in ((20.8, 3.4, 1.9), (3.2, 3.9, 1.4)):
        if star4(sx, sy, sr)(x, y): blend((255, 255, 255), 0.95)
    return (r, g, b, a)

if __name__ == '__main__':
    import sys
    d = os.path.join(OUT, 'drawable-xxhdpi')
    os.makedirs(d, exist_ok=True)
    only = sys.argv[1:] if len(sys.argv) > 1 else None
    for name, fn in I.items():
        if only and name not in only: continue
        write_png(os.path.join(d, name + '.png'), 96, render_alpha(fn, 96))
        preview(name, fn)
    if not only:
        write_png(os.path.join(OUT, 'drawable', 'ic_launcher.png'), 192, render_color(launcher, 192))
        # 启动图标也用 ASCII 看一眼（按亮度）
        print('--- launcher (ascii) ---')
        for py in range(32):
            row = ''
            for px in range(32):
                r, g, b, a = launcher((px + 0.5) * U / 32, (py + 0.5) * U / 32)
                if a < 0.4: row += '  '
                else:
                    lum = (0.299 * r + 0.587 * g + 0.114 * b)
                    row += '##' if lum > 200 else ('++' if lum > 120 else '..')
            print(row)
    print('icons ready')
