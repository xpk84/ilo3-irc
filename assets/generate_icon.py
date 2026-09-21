#!/usr/bin/env python3
"""Original geometric console icon; deterministic, Python standard library only.

Run from any directory: python3 assets/generate_icon.py
Use --output-dir PATH to reproduce the icon in an isolated directory.
No downloaded art, vendor marks, fonts, or external rasterizers are used.
"""
import argparse
from pathlib import Path
import struct
import zlib

# Coordinates in an original 64 x 64 drawing. Shared by SVG and PNG renderers.
SHAPES = [
    ('rect', (4, 4, 56, 56, 12), '#14283d'),
    ('rect', (28, 42, 8, 9, 1), '#6496b5'),
    ('rect', (21, 50, 22, 4, 2), '#a6ccde'),
    ('rect', (11, 14, 42, 31, 4), '#a6ccde'),
    ('rect', (14, 17, 36, 23, 2), '#102237'),
    ('line', (21, 23, 27, 28, 2.5), '#63e6c2'),
    ('line', (27, 28, 21, 33, 2.5), '#63e6c2'),
    ('line', (32, 33, 40, 33, 2.5), '#63e6c2'),
    ('rect', (43, 41, 4, 2, 1), '#267f7f'),
]


def svg():
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 64 64">',
             '<title>Original geometric console</title>',
             '<desc>Original monitor, terminal prompt and stand, constructed from geometric primitives. No vendor artwork. MIT license.</desc>']
    for kind, coordinates, color in SHAPES:
        if kind == 'rect':
            x, y, width, height, radius = coordinates
            lines.append(f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{radius}" fill="{color}"/>')
        else:
            x1, y1, x2, y2, width = coordinates
            lines.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" stroke-width="{width}" stroke-linecap="round"/>')
    return '\n'.join(lines + ['</svg>', ''])


def contains(kind, coordinates, x, y):
    if kind == 'rect':
        left, top, width, height, radius = coordinates
        if not (left <= x <= left + width and top <= y <= top + height):
            return False
        dx = max(left + radius - x, 0, x - (left + width - radius))
        dy = max(top + radius - y, 0, y - (top + height - radius))
        return dx * dx + dy * dy <= radius * radius
    x1, y1, x2, y2, width = coordinates
    dx, dy = x2 - x1, y2 - y1
    projection = max(0, min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return (x - x1 - projection * dx) ** 2 + (y - y1 - projection * dy) ** 2 <= (width / 2) ** 2


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


def png(size):
    # Four subpixel samples keep small icons legible without any imaging library.
    shapes = [(kind, coords, tuple(bytes.fromhex(color[1:]))) for kind, coords, color in reversed(SHAPES)]
    rows = bytearray()
    scale = 64 / size
    offsets = (0.25, 0.75) if size < 1024 else (0.5,)
    samples = len(offsets) ** 2
    for row in range(size):
        rows.append(0)  # PNG filter: None
        for column in range(size):
            red = green = blue = covered = 0
            for oy in offsets:
                for ox in offsets:
                    x, y = (column + ox) * scale, (row + oy) * scale
                    for kind, coordinates, color in shapes:
                        if contains(kind, coordinates, x, y):
                            red += color[0]
                            green += color[1]
                            blue += color[2]
                            covered += 1
                            break
            rows.extend((red // covered, green // covered, blue // covered, 255 * covered // samples)
                        if covered else (0, 0, 0, 0))
    header = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(bytes(rows), 9)) + chunk(b'IEND', b'')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().output_dir
    assets = root / 'assets'
    assets.mkdir(parents=True, exist_ok=True)
    (assets / 'original-console.svg').write_text(svg(), encoding='utf-8')
    entries = bytearray()
    for kind, size in ((b'icp4', 16), (b'icp5', 32), (b'icp6', 64), (b'ic07', 128),
                       (b'ic08', 256), (b'ic09', 512), (b'ic10', 1024)):
        data = png(size)
        entries.extend(kind + struct.pack('>I', len(data) + 8) + data)
        if size == 1024:
            (assets / 'original-console.png').write_bytes(data)
    icon = root / 'app-icon.icns'
    icon.write_bytes(b'icns' + struct.pack('>I', len(entries) + 8) + entries)
    print(f'Generated original SVG, PNG and ICNS: {icon}')


if __name__ == '__main__':
    main()
