#!/usr/bin/env python3
"""Deterministic, zero-dependency PNG icon generator for OBD-II & E-GMP Telemetry (obd-gen5w).

Generates:
  - app/res/drawable-hdpi/ic_app.png (512x512)
  - mod/icons/tile.png               (128x128)
  - mod/icons/tile_disabled.png      (128x128)
"""
import math
import os
import struct
import zlib

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def write_png(path: str, width: int, height: int, rgba_bytes: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)

    def chunk(tag: bytes, payload: bytes) -> bytes:
        crc = zlib.crc32(tag + payload) & 0xFFFFFFFF
        return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", crc)

    raw_rows = bytearray()
    stride = width * 4
    for y in range(height):
        raw_rows.append(0)
        raw_rows.extend(rgba_bytes[y * stride : (y + 1) * stride])

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw_rows), 9)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def point_in_poly(x: float, y: float, poly: list) -> bool:
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-9) + xi):
            inside = not inside
        j = i
    return inside


def dist_to_segment(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    dx, dy = bx - ax, by - ay
    denom = dx * dx + dy * dy
    if denom <= 1e-9:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / denom))
    cx, cy = ax + t * dx, ay + t * dy
    return math.hypot(px - cx, py - cy)


def rounded_rect_sdf(x: float, y: float, x0: float, y0: float, x1: float, y1: float, r: float) -> float:
    cx = (x0 + x1) * 0.5
    cy = (y0 + y1) * 0.5
    hx = (x1 - x0) * 0.5 - r
    hy = (y1 - y0) * 0.5 - r
    dx = max(abs(x - cx) - hx, 0.0)
    dy = max(abs(y - cy) - hy, 0.0)
    return math.hypot(dx, dy) + min(max(abs(x - cx) - hx, abs(y - cy) - hy), 0.0) - r


def blend(dst: tuple, src: tuple, cov: float) -> tuple:
    a_s = (src[3] / 255.0) * max(0.0, min(1.0, cov))
    if a_s <= 0.0:
        return dst
    a_d = dst[3] / 255.0
    out_a = a_s + a_d * (1.0 - a_s)
    if out_a <= 1e-6:
        return (0, 0, 0, 0)
    r = int(round((src[0] * a_s + dst[0] * a_d * (1.0 - a_s)) / out_a))
    g = int(round((src[1] * a_s + dst[1] * a_d * (1.0 - a_s)) / out_a))
    b = int(round((src[2] * a_s + dst[2] * a_d * (1.0 - a_s)) / out_a))
    return (max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b)), int(round(out_a * 255.0)))


def render_icon(size: int, disabled: bool = False) -> bytes:
    buf = bytearray(size * size * 4)

    # E-GMP HV bolt in lower center
    bolt_poly = [
        (0.50, 0.50),
        (0.42, 0.66),
        (0.49, 0.66),
        (0.45, 0.80),
        (0.58, 0.63),
        (0.51, 0.63),
    ]

    # Trapezoidal OBD-II J1962 connector silhouette at bottom
    obd_poly = [
        (0.28, 0.78),
        (0.72, 0.78),
        (0.67, 0.88),
        (0.33, 0.88),
    ]

    # Needle pointing toward high-efficiency power zone
    needle_a = (0.50, 0.52)
    needle_b = (0.66, 0.33)

    px_scale = 1.0 / size
    aa = 1.25 * px_scale

    for py in range(size):
        ny = (py + 0.5) / size
        for px in range(size):
            nx = (px + 0.5) / size
            col = (0, 0, 0, 0)

            # 1. Outer rounded square background tile
            d_tile = rounded_rect_sdf(nx, ny, 0.06, 0.06, 0.94, 0.94, 0.19)
            if d_tile < aa:
                cov = max(0.0, min(1.0, 0.5 - d_tile / (2.0 * aa)))
                if disabled:
                    bg = (34, 38, 44, 255)
                else:
                    t = max(0.0, min(1.0, ny))
                    bg = (
                        int(18 + 12 * (1.0 - t)),
                        int(26 + 16 * (1.0 - t)),
                        int(38 + 24 * (1.0 - t)),
                        255,
                    )
                col = blend(col, bg, cov)

                border_dist = abs(d_tile + 0.012) - 0.008
                if border_dist < aa:
                    bcov = max(0.0, min(1.0, 0.5 - border_dist / (2.0 * aa))) * cov
                    bcol = (90, 96, 106, 160) if disabled else (0, 162, 255, 185)
                    col = blend(col, bcol, bcov)

            # 2. Telemetry gauge arc (225 deg sweep around center 0.50, 0.50)
            dx = nx - 0.50
            dy = ny - 0.50
            rad = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(dy, dx))  # -180..180, 0=right, -90=top, 90=bottom
            # Sweep excluding bottom gap (55 deg .. 125 deg)
            if not (52.0 < ang < 128.0):
                arc_d = abs(rad - 0.29) - 0.028
                if arc_d < aa:
                    acov = max(0.0, min(1.0, 0.5 - arc_d / (2.0 * aa)))
                    if disabled:
                        acol = (126, 134, 144, 255)
                    else:
                        # Cyan-blue -> emerald gradient across angle
                        acol = (46, 214, 126, 255) if (-45.0 <= ang <= 52.0) else (0, 162, 255, 255)
                    col = blend(col, acol, acov)

            # 3. Gauge needle + hub
            nd = dist_to_segment(nx, ny, needle_a[0], needle_a[1], needle_b[0], needle_b[1]) - 0.017
            if nd < aa:
                ncov = max(0.0, min(1.0, 0.5 - nd / (2.0 * aa)))
                ncol = (170, 176, 184, 255) if disabled else (255, 186, 48, 255)
                col = blend(col, ncol, ncov)

            hub_d = rad - 0.038
            if hub_d < aa:
                hcov = max(0.0, min(1.0, 0.5 - hub_d / (2.0 * aa)))
                hcol = (180, 186, 194, 255) if disabled else (255, 255, 255, 255)
                col = blend(col, hcol, hcov)

            # 4. E-GMP lightning bolt
            if 0.40 <= nx <= 0.60 and 0.48 <= ny <= 0.82:
                hits = 0
                for sy in (-0.25, 0.25):
                    for sx in (-0.25, 0.25):
                        if point_in_poly(nx + sx * px_scale, ny + sy * px_scale, bolt_poly):
                            hits += 1
                if hits > 0:
                    bcol = (165, 172, 180, 255) if disabled else (46, 224, 128, 255)
                    col = blend(col, bcol, hits / 4.0)

            # 5. Subtle OBD-II DLC port bar at base
            if 0.26 <= nx <= 0.74 and 0.76 <= ny <= 0.90:
                hits = 0
                for sy in (-0.25, 0.25):
                    for sx in (-0.25, 0.25):
                        if point_in_poly(nx + sx * px_scale, ny + sy * px_scale, obd_poly):
                            hits += 1
                if hits > 0:
                    ocol = (105, 112, 122, 230) if disabled else (56, 189, 248, 220)
                    col = blend(col, ocol, hits / 4.0)

            idx = (py * size + px) * 4
            buf[idx] = col[0]
            buf[idx + 1] = col[1]
            buf[idx + 2] = col[2]
            buf[idx + 3] = col[3]

    return bytes(buf)


def main() -> None:
    ic_app = os.path.join(BASE_DIR, "app", "res", "drawable-hdpi", "ic_app.png")
    tile = os.path.join(BASE_DIR, "mod", "icons", "tile.png")
    tile_dis = os.path.join(BASE_DIR, "mod", "icons", "tile_disabled.png")

    print("Generating OBD-II & E-GMP Telemetry icons...")
    write_png(ic_app, 512, 512, render_icon(512, disabled=False))
    write_png(tile, 128, 128, render_icon(128, disabled=False))
    write_png(tile_dis, 128, 128, render_icon(128, disabled=True))
    print("  Generated:", ic_app)
    print("  Generated:", tile)
    print("  Generated:", tile_dis)


if __name__ == "__main__":
    main()
