#!/usr/bin/env python3
r"""
make-icon.py - generate iohelper.ico (pure Python, no PIL).

Draws a pair of smart glasses (teal frame, translucent lenses, a spark above
the right lens) at 16/32/48 px and packs them into a multi-size Windows .ico
(32-bit BGRA with alpha). Used by the "iohelper" desktop shortcut.

    python make-icon.py                # writes iohelper.ico next to this file
"""
import math
import os
import struct

TEAL = (124, 92, 255)         # frame (violet)
LENS = (124, 92, 255)         # lens fill (low alpha)
SPARK = (62, 207, 142)        # spark accent (green)


def draw(size):
    """Return a size*size list of (b, g, r, a) pixels, top-down."""
    s = size / 48.0                       # design coordinates are 48x48
    px = [[(0, 0, 0, 0)] * size for _ in range(size)]

    def blend(x, y, rgb, a):
        if 0 <= x < size and 0 <= y < size:
            b0, g0, r0, a0 = px[y][x]
            na = a + a0 * (255 - a) // 255
            if na == 0:
                return
            # clamp: na floors down, so numerator//na can round past 255
            r = min(255, (rgb[0] * a + r0 * a0 * (255 - a) // 255) // na)
            g = min(255, (rgb[1] * a + g0 * a0 * (255 - a) // 255) // na)
            b = min(255, (rgb[2] * a + b0 * a0 * (255 - a) // 255) // na)
            px[y][x] = (b, g, r, na)

    def ring(cx, cy, radius, thick, rgb):
        cx, cy, radius, thick = cx * s, cy * s, radius * s, max(thick * s, 1.0)
        for y in range(size):
            for x in range(size):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                # anti-aliased ring band
                edge = abs(d - radius) - thick / 2
                if edge < 0.7:
                    a = int(255 * min(1.0, 0.7 - edge))
                    blend(x, y, rgb, max(0, min(255, a)))
                # translucent lens fill inside
                inner = d - (radius - thick / 2)
                if inner < 0.5:
                    blend(x, y, LENS, 46)

    def line(x0, y0, x1, y1, thick, rgb):
        x0, y0, x1, y1, thick = x0 * s, y0 * s, x1 * s, y1 * s, max(thick * s, 1.0)
        length = math.hypot(x1 - x0, y1 - y0) or 1.0
        for y in range(size):
            for x in range(size):
                # distance from pixel center to segment
                t = max(0.0, min(1.0, ((x + 0.5 - x0) * (x1 - x0)
                                       + (y + 0.5 - y0) * (y1 - y0)) / (length * length)))
                d = math.hypot(x + 0.5 - (x0 + t * (x1 - x0)),
                               y + 0.5 - (y0 + t * (y1 - y0)))
                edge = d - thick / 2
                if edge < 0.7:
                    a = int(255 * min(1.0, 0.7 - edge))
                    blend(x, y, rgb, max(0, min(255, a)))

    ring(14, 27, 9, 2.6, TEAL)            # left lens
    ring(34, 27, 9, 2.6, TEAL)            # right lens
    line(21.5, 25, 26.5, 25, 2.4, TEAL)   # bridge
    line(5.5, 25, 2, 21, 2.2, TEAL)       # left temple stub
    line(42.5, 25, 46, 21, 2.2, TEAL)     # right temple stub
    # spark above the right lens (the "assistant" accent)
    line(40, 8, 40, 14, 2.0, SPARK)
    line(37, 11, 43, 11, 2.0, SPARK)
    return px


def ico(images):
    """Pack [(size, pixels)] into a .ico blob (32-bit BMP entries)."""
    blobs = []
    for size, px in images:
        # BITMAPINFOHEADER: height doubled (XOR + AND masks), bottom-up rows
        hdr = struct.pack("<IiiHHIIiiII", 40, size, size * 2, 1, 32, 0,
                          size * size * 4, 0, 0, 0, 0)
        xor = b"".join(bytes(c for p in px[y] for c in p)
                       for y in range(size - 1, -1, -1))
        and_row = b"\x00" * (((size + 31) // 32) * 4)
        blobs.append(hdr + xor + and_row * size)
    out = struct.pack("<HHH", 0, 1, len(images))
    offset = 6 + 16 * len(images)
    for (size, _), blob in zip(images, blobs):
        out += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32,
                           len(blob), offset)
        offset += len(blob)
    return out + b"".join(blobs)


def main():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "iohelper.ico")
    with open(path, "wb") as fh:
        fh.write(ico([(sz, draw(sz)) for sz in (16, 32, 48)]))
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
