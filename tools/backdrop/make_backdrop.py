#!/usr/bin/env python3
"""Makes the title screen's backdrop: a snowy Alpine photo, blurred and shrunk.

    python3 tools/backdrop/make_backdrop.py [--blur 1.5] [--darken 25] [--width 960] [--preview DIR]

The photo is "Snowy landscape in Eigergletscher, Lauterbrunnen" by Pascale Amez, CC0 1.0 (see
SOURCE below and title_backdrop.txt beside the texture). The 5193 x 3466 original is downloaded
once into work/backdrop/ (git-ignored, so the repo holds only the small result) and written to the
Incari pack as a PNG, uncropped at 3:2: the client crops it to the screen at run time, keeping the
peaks (FOCUS_Y), so one file serves 3:2 Chromebooks, 16:9 and ultrawide alike.

The blur is done here rather than in a shader, so drawing it costs one textured quad. A blurred
image has no fine detail, so a small one stretched to the screen looks the same as a big one.
It is darkened too, most at the bottom, so the menu's light text holds up over the white cloud.

It also writes the photo, as a small JPEG, and Incari's mark (tools/icon's 128-pixel PNG) into
app-web's index.html as data URLs, so the page's loader shows the same scene before the game starts
and the one-file build carries them too.

With --preview, also writes the crops each screen shape would show, for judging the composition.
Needs ImageMagick (magick) on the path; otherwise the standard library only.
"""

import argparse
import base64
import re
import shutil
import subprocess
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CACHE = ROOT / "work" / "backdrop" / "eigergletscher.jpg"
OUT = ROOT / "client/src/main/resources/incari-pack/assets/incari/textures/gui/title_backdrop.png"
PAGE = ROOT / "app-web/src/main/web/index.html"
MARK = OUT.parent / "icon_128.png"
# The page's copy: small, as it's in the page itself; blurred, so it looks the same stretched.
PAGE_WIDTH = 640
PAGE_QUALITY = 72
SOURCE = "https://commons.wikimedia.org/wiki/File:Snowy_landscape_in_Eigergletscher,_Lauterbrunnen.jpg"
DOWNLOAD = "https://commons.wikimedia.org/wiki/Special:FilePath/Snowy_landscape_in_Eigergletscher,_Lauterbrunnen.jpg"
# Where the crop is centred, as a fraction of the height: the peak line sits 20-45% down, and the
# bright cloud below is what to lose first on wide screens.
FOCUS_Y = 0.35
# Screen shapes the preview crops to.
SHAPES = {"3x2": (3, 2), "16x9": (16, 9), "16x10": (16, 10), "21x9": (21, 9)}


def fetch():
    if CACHE.exists():
        return
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    # Wikimedia asks for a descriptive User-Agent.
    request = urllib.request.Request(DOWNLOAD, headers={"User-Agent": "IncariClient-backdrop/1.0 (tools/backdrop)"})
    with urllib.request.urlopen(request) as response:
        CACHE.write_bytes(response.read())
    print(f"Downloaded {CACHE}")


def make(blur, darken, width):
    OUT.parent.mkdir(parents=True, exist_ok=True)
    height = round(width * 3466 / 5193)
    # Shrink to twice the target first so the blur is cheap, blur by a fraction of the width (so
    # the look doesn't depend on --width), then shrink to the target.
    sigma = 2 * width * blur / 100
    # Multiplied by a grey ramp: the top keeps 60% of the darkening, the bottom has all of it.
    top = round(255 * (1 - 0.6 * darken / 100))
    bottom = round(255 * (1 - darken / 100))
    subprocess.run(["magick", str(CACHE), "-resize", f"{2 * width}x", "-blur", f"0x{sigma:.1f}",
                    "-resize", f"{width}x{height}!",
                    "(", "-size", f"{width}x{height}", f"gradient:rgb({top},{top},{top})-rgb({bottom},{bottom},{bottom})", ")",
                    "-compose", "multiply", "-composite", "-strip", "-define", "png:compression-level=9",
                    str(OUT)], check=True)
    print(f"Wrote {OUT} ({width}x{height}, {OUT.stat().st_size // 1024} KB)")


def page():
    photo = subprocess.run(["magick", str(OUT), "-resize", f"{PAGE_WIDTH}x", "-strip", "-quality", str(PAGE_QUALITY),
                            "jpg:-"], check=True, capture_output=True).stdout
    text = PAGE.read_text()
    for name, kind, data in (("backdrop", "jpeg", photo), ("mark", "png", MARK.read_bytes())):
        url = f'url("data:image/{kind};base64,{base64.b64encode(data).decode()}")'
        text, count = re.subn(rf"--incari-{name}: [^;]*;", lambda _: f"--incari-{name}: {url};", text, count=1)
        if count != 1:
            raise SystemExit(f"{PAGE} has no --incari-{name} to write")
    PAGE.write_text(text)
    print(f"Wrote the photo ({len(photo) // 1024} KB) and the mark into {PAGE}")


def preview(directory, width):
    directory.mkdir(parents=True, exist_ok=True)
    height = round(width * 3466 / 5193)
    for name, (w, h) in SHAPES.items():
        if w / h >= width / height:
            cw, ch = width, round(width * h / w)
        else:
            cw, ch = round(height * w / h), height
        x = (width - cw) // 2
        y = min(max(round(FOCUS_Y * height - ch / 2), 0), height - ch)
        target = directory / f"backdrop_{name}.png"
        subprocess.run(["magick", str(OUT), "-crop", f"{cw}x{ch}+{x}+{y}", "+repage", str(target)], check=True)
        print(f"Wrote {target}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--blur", type=float, default=1.5, help="blur radius, percent of the width")
    parser.add_argument("--darken", type=float, default=25, help="darkening at the bottom, percent")
    parser.add_argument("--width", type=int, default=960, help="width of the texture in pixels")
    parser.add_argument("--preview", type=Path, help="also write each screen shape's crop here")
    args = parser.parse_args()
    if shutil.which("magick") is None:
        raise SystemExit("ImageMagick (magick) isn't on the path")
    fetch()
    make(args.blur, args.darken, args.width)
    page()
    if args.preview:
        preview(args.preview, args.width)


if __name__ == "__main__":
    main()
