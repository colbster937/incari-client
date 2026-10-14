#!/usr/bin/env python3
"""Draws Incari's icon: a snow-white pine with a comet circling up it, its tail fading below.

    python3 tools/icon/make_icon.py

Writes tools/icon/incari-icon.svg (the source art, 512 units square, transparent), and with
rsvg-convert on the path, the PNGs the client reads: the window icon's sizes and the 256-pixel
image the GUI draws the mark from (incari.client.ui.Logo), under the Incari pack; the 32-pixel
badge glyph the incari:badge font shows before Incari players' name tags; and the page's
favicon, the 48-pixel PNG written into app-web's index.html (so the one-file build has it too).

Standard library only. The trail is a cone-shaped helix around the tree, cut in short quads: SVG
has no gradient along a path, so each quad takes its own colour, and a mask of grey quads (opaque
in the mask, so neighbours overlap without seams) fades the tail out. Quads behind the tree are
drawn before it, quads in front after it, over a faint dark edge so they part from the white.
"""

import base64
import math
import re
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACK = HERE.parents[1] / "client/src/main/resources/incari-pack/assets/incari/textures/gui"
PAGE = HERE.parents[1] / "app-web/src/main/web/index.html"
SIZES = (16, 32, 48, 128, 256)
FAVICON = 48
BADGE = HERE.parents[1] / "client/src/main/resources/incari-pack/assets/incari/textures/font/badge.png"

CX = 256
TIP = 50
# Each tier: its apex, its bottom and its half width.
TIERS = [(50, 160, 66), (100, 236, 102), (160, 316, 136), (226, 392, 168)]

# The comet: its head's height, how far the trail drops, how many turns it makes, the head's angle,
# how flat the orbit looks (the ellipse's minor axis over its major), and the trail's quads.
HEAD_Y = 118
DROP = 290
TURNS = 2.31
HEAD_ANGLE = math.radians(22)
TILT = 0.28
QUADS = 900
EDGE = "#1d3f5c"


def half_width(y):
    """The tree's half width at a height: the cone its tiers' tips lie on."""
    return max(0, (y - TIP) / (392 - TIP) * 168)


def tier_path(apex, bottom, half):
    """A tier: flanks curving in, a bottom of drooping points."""
    d = f"M{CX},{apex} Q{CX - half * 0.28:.1f},{apex + (bottom - apex) * 0.62:.1f} {CX - half:.1f},{bottom:.1f} "
    tips = [-1, -0.5, 0, 0.5, 1]
    for x0, x1 in zip(tips, tips[1:]):
        notch = (x0 + x1) / 2
        drop = 0 if abs(x1) == 1 else 5
        d += f"L{CX + half * notch:.1f},{bottom - 20:.1f} L{CX + half * x1:.1f},{bottom + drop:.1f} "
    d += f"Q{CX + half * 0.28:.1f},{apex + (bottom - apex) * 0.62:.1f} {CX},{apex} Z"
    return d


TRUNK_BOTTOM = 452
TRUNK = f"M{CX - 20},380 L{CX + 20},380 L{CX + 24},446 Q{CX},{TRUNK_BOTTOM} {CX - 24},446 Z"


def position(s):
    """The trail at s, 0 at the tail and 1 at the head: x, y, and how far in front of the tree."""
    centre = HEAD_Y + (1 - s) * DROP
    angle = HEAD_ANGLE - (1 - s) * TURNS * 2 * math.pi
    radius = half_width(centre) * 0.88 + 58
    return CX + radius * math.cos(angle), centre + TILT * radius * math.sin(angle), math.sin(angle)


def width(s):
    return 11 * s**1.5


def opacity(s):
    t = min(1, s / 0.38)
    return t * t * (3 - 2 * t)


def colour(s):
    stops = [(0, (178, 204, 230)), (0.55, (206, 224, 242)), (0.85, (230, 240, 250)), (1, (255, 255, 255))]
    for (s0, c0), (s1, c1) in zip(stops, stops[1:]):
        if s <= s1:
            t = (s - s0) / (s1 - s0)
            return "#%02x%02x%02x" % tuple(round(a + (b - a) * t) for a, b in zip(c0, c1))
    return "#ffffff"


POINTS = [position(i / QUADS) for i in range(QUADS + 1)]


def quads(pad, fill, front):
    """The trail's quads on one side of the tree, widened by pad, each filled by fill(s)."""
    out = []
    for i in range(QUADS):
        x0, y0, f0 = POINTS[i]
        x1, y1, f1 = POINTS[i + 1]
        if (f0 + f1 > 0) != front:
            continue
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy)
        ux, uy = dx / length, dy / length
        # Each quad a little longer than its step, so neighbours overlap.
        x0, y0, x1, y1 = x0 - ux * 0.6, y0 - uy * 0.6, x1 + ux * 0.6, y1 + uy * 0.6
        w0 = width(i / QUADS) / 2 + pad
        w1 = width((i + 1) / QUADS) / 2 + pad
        nx, ny = -uy, ux
        corners = [(x0 + nx * w0, y0 + ny * w0), (x1 + nx * w1, y1 + ny * w1),
                   (x1 - nx * w1, y1 - ny * w1), (x0 - nx * w0, y0 - ny * w0)]
        path = "L".join(f"{a:.2f},{b:.2f}" for a, b in corners)
        out.append(f'<path d="M{path}Z" fill="{fill((i + 0.5) / QUADS)}"/>')
    return "".join(out)


def grey(s):
    return "#%02x%02x%02x" % ((round(255 * opacity(s)),) * 3)


def trail(front):
    """The mask, the trail and (in front only) the dark edge under it."""
    tag = "front" if front else "back"
    mask = f'<mask id="{tag}" maskUnits="userSpaceOnUse" x="0" y="0" width="512" height="512">{quads(4, grey, front)}</mask>'
    body = f'<g mask="url(#{tag})">{quads(0, colour, front)}</g>'
    edge = (f'<g mask="url(#{tag})" clip-path="url(#tree)" opacity="0.55">'
            f'{quads(2.5, lambda s: EDGE, front)}</g>')
    return mask, body, edge


def svg():
    paths = [tier_path(*tier) for tier in TIERS]
    silhouette = "".join(f'<path d="{p}"/>' for p in reversed(paths))
    back_mask, back, _ = trail(False)
    front_mask, front, edge = trail(True)
    head_x, head_y, _ = POINTS[-1]

    # Each tier shaded like a cone (cooler on the left, white right of the ridge), lit at its top,
    # and casting a soft shadow on the tier below it.
    tree = [f'<path d="{TRUNK}" fill="url(#cone)"/>',
            f'<path d="{TRUNK}" fill="#8fa8bf" fill-opacity="0.18" clip-path="url(#left)"/>',
            f'<g clip-path="url(#trunk)"><path d="{paths[-1]}" transform="translate(0 12)" fill="#3e5a73" '
            f'fill-opacity="0.3" filter="url(#soft)"/></g>']
    for i in reversed(range(len(TIERS))):
        tree.append(f'<path d="{paths[i]}" fill="url(#cone)"/>')
        tree.append(f'<path d="{paths[i]}" fill="#8fa8bf" fill-opacity="0.13" clip-path="url(#left)"/>')
        tree.append(f'<path d="{paths[i]}" fill="url(#light{i})"/>')
        if i > 0:
            tree.append(f'<g clip-path="url(#tier{i})"><path d="{paths[i - 1]}" transform="translate(0 10)" '
                        f'fill="#3e5a73" fill-opacity="0.22" filter="url(#soft)"/></g>')
    tier_clips = "".join(f'<clipPath id="tier{i}"><path d="{p}"/></clipPath>' for i, p in enumerate(paths))
    lights = "".join(
        f'<linearGradient id="light{i}" gradientUnits="userSpaceOnUse" x1="0" y1="{apex}" x2="0" y2="{bottom + 5}">'
        f'<stop offset="0" stop-color="#ffffff" stop-opacity="0.35"/>'
        f'<stop offset="0.6" stop-color="#ffffff" stop-opacity="0"/>'
        f'<stop offset="1" stop-color="#6f8ca6" stop-opacity="0.12"/></linearGradient>'
        for i, (apex, bottom, _) in enumerate(TIERS))
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
<defs>
<radialGradient id="halo" cx="0.5" cy="0.5" r="0.5"><stop offset="0" stop-color="#dcecf8" stop-opacity="0.9"/><stop offset="0.35" stop-color="#dcecf8" stop-opacity="0.35"/><stop offset="1" stop-color="#dcecf8" stop-opacity="0"/></radialGradient>
<linearGradient id="cone" gradientUnits="userSpaceOnUse" x1="88" y1="0" x2="424" y2="0"><stop offset="0" stop-color="#d6e3ee"/><stop offset="0.42" stop-color="#f2f7fb"/><stop offset="0.5" stop-color="#ffffff"/><stop offset="0.75" stop-color="#ffffff"/><stop offset="1" stop-color="#edf3f8"/></linearGradient>
{lights}
<filter id="soft" x="-20%" y="-20%" width="140%" height="140%"><feGaussianBlur stdDeviation="6"/></filter>
<clipPath id="tree">{silhouette}<path d="{TRUNK}"/></clipPath>
<clipPath id="trunk"><path d="{TRUNK}"/></clipPath>
{tier_clips}
<clipPath id="left"><rect x="0" y="0" width="{CX}" height="512"/></clipPath>
{back_mask}{front_mask}
</defs>
{back}
{"".join(tree)}
{edge}{front}
<circle cx="{head_x:.1f}" cy="{head_y:.1f}" r="40" fill="url(#halo)"/>
<circle cx="{head_x:.1f}" cy="{head_y:.1f}" r="10" fill="#ffffff"/>
</svg>
"""


def main():
    source = HERE / "incari-icon.svg"
    source.write_text(svg())
    print(f"Wrote {source}")
    if shutil.which("rsvg-convert") is None:
        print("rsvg-convert isn't on the path: the PNGs weren't made")
        return
    PACK.mkdir(parents=True, exist_ok=True)
    for size in SIZES:
        png = PACK / f"icon_{size}.png"
        subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), str(source), "-o", str(png)], check=True)
        print(f"Wrote {png}")
    # The badge, cropped so the tree runs from the glyph's top to its baseline: incari:badge's glyph
    # is 9 units with 8 above the baseline, as tall as the name tag's capitals.
    side = (TRUNK_BOTTOM - TIP) * 9 / 8
    cropped = HERE / "badge.svg"
    cropped.write_text(svg().replace('viewBox="0 0 512 512" width="512" height="512"',
                                     f'viewBox="{CX - side / 2:.2f} {TIP} {side:.2f} {side:.2f}" width="32" height="32"', 1))
    subprocess.run(["rsvg-convert", "-w", "32", "-h", "32", str(cropped), "-o", str(BADGE)], check=True)
    cropped.unlink()
    print(f"Wrote {BADGE}")
    favicon = base64.b64encode((PACK / f"icon_{FAVICON}.png").read_bytes()).decode()
    page = re.sub(r'<link rel="icon" href="[^"]*">', f'<link rel="icon" href="data:image/png;base64,{favicon}">',
                  PAGE.read_text(), count=1)
    PAGE.write_text(page)
    print(f"Wrote the favicon into {PAGE}")


if __name__ == "__main__":
    main()
