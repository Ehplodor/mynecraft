"""Sanity checks on a screenshot: not (almost) black, no missing-texture magenta. Pure stdlib PNG reader."""
import struct, sys, zlib

def pixels(path):
    data = open(path, 'rb').read()
    pos, idat, w = 8, b'', 0
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
            bpp = {2: 3, 6: 4}[ctype]
        elif kind == b'IDAT':
            idat += body
        pos += 12 + n
    raw, stride, prev = zlib.decompress(idat), w * bpp, bytearray(w * bpp)
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        for x in range(0, stride, bpp * 4):  # every 4th pixel is enough
            yield line[x], line[x + 1], line[x + 2]

path = sys.argv[1]
total = dark = magenta = 0
for r, g, b in pixels(path):
    total += 1
    dark += r + g + b < 30
    magenta += r > 200 and b > 200 and g < 50
ok = dark < total * 0.9 and magenta < total * 0.001
print(('ok  ' if ok else 'BAD ') + path + f' dark={dark / total:.1%} magenta={magenta / total:.2%}')
sys.exit(0 if ok else 1)
