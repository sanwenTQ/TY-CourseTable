#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把用户提供的吉祥物图（图二）解码 → 缩放 → 圆角 → 输出图标资源。
   纯标准库 PNG 解码（支持 8bit 灰度/RGB/调色板/RGBA + 全部 filter）。"""
import struct, zlib, sys, os

SRC = sys.argv[1] if len(sys.argv) > 1 else '/root/.dsh/attachments/v1/objects/db/db412aa4c6cb8cdfdcdc08696069e27194bf314f4cae71f699c3d6e59344d283'
OUTDIR = 'app/res'

def read_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos = 8
    idat = b''
    plte = None
    trns = None
    w = h = bd = ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', data)
            assert inter == 0, 'interlaced not supported'
        elif typ == b'PLTE':
            plte = data
        elif typ == b'tRNS':
            trns = data
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
    assert bd == 8, 'only 8-bit supported, got %s' % bd
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    stride = w * ch
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if f == 1:
            for i in range(ch, stride): line[i] = (line[i] + line[i - ch]) & 255
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    # → RGBA
    rgba = bytearray(w * h * 4)
    for y in range(h):
        for x in range(w):
            i = y * stride + x * ch
            o = (y * w + x) * 4
            if ct == 0:
                v = out[i]; rgba[o:o + 4] = bytes([v, v, v, 255])
            elif ct == 2:
                rgba[o:o + 3] = out[i:i + 3]; rgba[o + 3] = 255
            elif ct == 3:
                idx = out[i]
                rgba[o:o + 3] = plte[idx * 3:idx * 3 + 3]
                rgba[o + 3] = trns[idx] if (trns and idx < len(trns)) else 255
            elif ct == 4:
                v = out[i]; rgba[o:o + 3] = bytes([v, v, v]); rgba[o + 3] = out[i + 1]
            else:
                rgba[o:o + 4] = out[i:i + 4]
    return w, h, rgba

def downscale(w, h, rgba, nw, nh):
    out = bytearray(nw * nh * 4)
    for y in range(nh):
        y0 = y * h // nh; y1 = max(y0 + 1, (y + 1) * h // nh)
        for x in range(nw):
            x0 = x * w // nw; x1 = max(x0 + 1, (x + 1) * w // nw)
            r = g = b = a = n = 0
            for yy in range(y0, y1):
                base = yy * w
                for xx in range(x0, x1):
                    i = (base + xx) * 4
                    al = rgba[i + 3]
                    r += rgba[i] * al; g += rgba[i + 1] * al; b += rgba[i + 2] * al
                    a += al; n += 1
            o = (y * nw + x) * 4
            if a > 0:
                out[o] = r // a; out[o + 1] = g // a; out[o + 2] = b // a
            out[o + 3] = a // n
    return out

def round_corners(n, rgba, radius_ratio=0.22):
    r = n * radius_ratio
    out = bytearray(rgba)
    for y in range(n):
        for x in range(n):
            cx = min(max(x + 0.5, r), n - r); cy = min(max(y + 0.5, r), n - r)
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d > r:
                out[(y * n + x) * 4 + 3] = 0
            elif d > r - 1.2:
                o = (y * n + x) * 4 + 3
                out[o] = int(rgba[o] * (r - d) / 1.2)
    return out

def write_png(path, n, rgba):
    raw = b''.join(b'\x00' + bytes(rgba[y * n * 4:(y + 1) * n * 4]) for y in range(n))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', n, n, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))
    open(path, 'wb').write(png)

def ascii_preview(n, rgba, cols=32):
    print('--- preview ---')
    for y in range(cols):
        row = ''
        for x in range(cols):
            i = ((y * n // cols) * n + (x * n // cols)) * 4
            a = rgba[i + 3]
            if a < 40: row += '  '; continue
            lum = (0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2])
            row += '##' if lum > 210 else ('++' if lum > 150 else ('..' if lum > 80 else '  '))
        print(row)

if __name__ == '__main__':
    w, h, rgba = read_png(SRC)
    print('src', w, 'x', h)
    # 应用内吉祥物 256
    m = downscale(w, h, rgba, 256, 256)
    m = round_corners(256, m, 0.24)
    write_png(os.path.join(OUTDIR, 'drawable-xxhdpi', 'ic_mascot.png'), 256, m)
    # 启动图标 192（圆角更小一点，交给启动器再裁）
    l = downscale(w, h, rgba, 192, 192)
    l = round_corners(192, l, 0.22)
    write_png(os.path.join(OUTDIR, 'drawable', 'ic_launcher.png'), 192, l)
    ascii_preview(192, l)
    print('written: ic_mascot.png(256), ic_launcher.png(192)')
