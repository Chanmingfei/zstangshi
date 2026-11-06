#!/usr/bin/env python3
"""
make-icons.py —— 从 Android 自适应图标的矢量参数生成 iOS 所需的位图资源。

设计来源（与 Android 完全一致，不新造视觉）：
  android/app/src/main/res/drawable/ic_launcher_foreground.xml
    左页 (28,36) (52,33) (52,75) (28,72)
    右页 (56,33) (80,36) (80,72) (56,75)
    书脊 (52,33) (56,33) (56,75) (52,75)  —— 品牌色
  android/app/src/main/res/drawable/ic_launcher_background.xml
    满版品牌深蓝 #1B3A6B

尺寸换算为什么不是直接 108 → 1024：
  Android 自适应图标是 108dp 画布，桌面只显示中间 72dp 的遮罩区域，
  所以书本在「用户看到的图标」里约占 52/72 ≈ 72% 宽。iOS 图标自带圆角遮罩、
  没有 108dp 概念，直接把 108 视口铺满会让书本只有 48% 宽，明显偏小。
  因此这里把 **72dp 视口映射到整张 1024 画布**，两边视觉占比才一致。

渲染方式：4096² 扫描线填充 + 4×4 盒式降采样（等效 16 倍超采样抗锯齿），
纯标准库实现，不依赖 Pillow / cairosvg。
"""

import os
import struct
import zlib

CANVAS = 1024          # 输出尺寸（App Store 要求 1024×1024 无 alpha 也无圆角）
SUPER = 4              # 超采样倍数
VIEWPORT_VISIBLE = 72.0  # Android 遮罩内可见的 72dp
CENTER = 54.0          # 108dp 画布中心（书本图形关于它对称）

BRAND = (0x1B, 0x3A, 0x6B)
WHITE = (0xFF, 0xFF, 0xFF)

LEFT_PAGE = [(28, 36), (52, 33), (52, 75), (28, 72)]
RIGHT_PAGE = [(56, 33), (80, 36), (80, 72), (56, 75)]
SPINE = [(52, 33), (56, 33), (56, 75), (52, 75)]


def project(point, size):
    """把 108dp 视口的坐标映射到 size×size 位图上。"""
    scale = size / VIEWPORT_VISIBLE
    return ((point[0] - CENTER) * scale + size / 2.0,
            (point[1] - CENTER) * scale + size / 2.0)


def fill_polygon(pixels, size, polygon, color):
    """扫描线填充（奇偶规则），带 1px 边界外扩以消除锯齿孔洞。"""
    pts = [project(p, size) for p in polygon]
    ys = [p[1] for p in pts]
    y_start = max(0, int(min(ys)))
    y_end = min(size - 1, int(max(ys)) + 1)

    for y in range(y_start, y_end + 1):
        scan_y = y + 0.5
        crossings = []
        for index in range(len(pts)):
            x1, y1 = pts[index]
            x2, y2 = pts[(index + 1) % len(pts)]
            if (y1 <= scan_y < y2) or (y2 <= scan_y < y1):
                ratio = (scan_y - y1) / (y2 - y1)
                crossings.append(x1 + ratio * (x2 - x1))
        if len(crossings) < 2:
            continue
        crossings.sort()
        for pair in range(0, len(crossings) - 1, 2):
            x_from = max(0, int(round(crossings[pair])))
            x_to = min(size - 1, int(round(crossings[pair + 1])))
            row = y * size * 3
            for x in range(x_from, x_to + 1):
                offset = row + x * 3
                pixels[offset] = color[0]
                pixels[offset + 1] = color[1]
                pixels[offset + 2] = color[2]


def downsample(pixels, size, factor):
    out_size = size // factor
    out = bytearray(out_size * out_size * 3)
    area = factor * factor
    for y in range(out_size):
        for x in range(out_size):
            r = g = b = 0
            base_y = y * factor
            base_x = x * factor
            for dy in range(factor):
                row = (base_y + dy) * size * 3
                for dx in range(factor):
                    offset = row + (base_x + dx) * 3
                    r += pixels[offset]
                    g += pixels[offset + 1]
                    b += pixels[offset + 2]
            target = (y * out_size + x) * 3
            out[target] = r // area
            out[target + 1] = g // area
            out[target + 2] = b // area
    return out


def write_png(path, pixels, size):
    raw = bytearray()
    stride = size * 3
    for y in range(size):
        raw.append(0)  # filter type 0
        raw.extend(pixels[y * stride:(y + 1) * stride])

    def chunk(tag, data):
        body = tag + data
        return (struct.pack(">I", len(data)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)  # 8-bit truecolor
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", header)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(png)
    return len(png)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.dirname(here)
    icon_dir = os.path.join(root, "ZhangShangTangShi", "Resources", "Assets.xcassets",
                            "AppIcon.appiconset")
    os.makedirs(icon_dir, exist_ok=True)

    render_size = CANVAS * SUPER
    pixels = bytearray(render_size * render_size * 3)
    for i in range(0, len(pixels), 3):
        pixels[i] = BRAND[0]
        pixels[i + 1] = BRAND[1]
        pixels[i + 2] = BRAND[2]

    fill_polygon(pixels, render_size, LEFT_PAGE, WHITE)
    fill_polygon(pixels, render_size, RIGHT_PAGE, WHITE)
    fill_polygon(pixels, render_size, SPINE, BRAND)

    final = downsample(pixels, render_size, SUPER)
    path = os.path.join(icon_dir, "AppIcon-1024.png")
    written = write_png(path, final, CANVAS)
    print("generated %s (%d bytes, %dx%d)" % (path, written, CANVAS, CANVAS))


if __name__ == "__main__":
    main()
