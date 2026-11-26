"""Makes the launcher icons (android/app/src/main/res/mipmap-*) from heavenfall/icon/logo.png, the Heavenfall program's logo.

    python heavenfall/tools/make_icons.py

For each density: ic_launcher_foreground.png (the logo inside the adaptive icon's safe zone), ic_launcher_background.png
(a flat dark square) and ic_launcher.png (the same on a rounded square, for the phones without adaptive icons). Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LOGO = HERE.parent / "icon" / "logo.png"
RES = HERE.parent.parent / "android" / "app" / "src" / "main" / "res"
BACKGROUND = (13, 11, 14)  # the program's own dark background
# density: the adaptive layers' side in pixels (108 dp); the legacy icon is 48 dp
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_FIT = 0.62  # the logo's longest side, as a share of the layer: inside the 66 dp safe circle of 108
LEGACY_FIT = 0.90


def fitted(logo: Image.Image, side: int, share: float) -> Image.Image:
    scale = side * share / max(logo.size)
    return logo.resize((max(1, round(logo.width * scale)), max(1, round(logo.height * scale))), Image.LANCZOS)


def centred(layer: Image.Image, side: int, base: Image.Image) -> Image.Image:
    base.alpha_composite(layer, ((side - layer.width) // 2, (side - layer.height) // 2))
    return base


def main() -> None:
    logo = Image.open(LOGO).convert("RGBA")
    for name, factor in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        side = round(108 * factor)
        centred(fitted(logo, side, ADAPTIVE_FIT), side, Image.new("RGBA", (side, side), (0, 0, 0, 0))).save(
            folder / "ic_launcher_foreground.png")
        Image.new("RGB", (side, side), BACKGROUND).save(folder / "ic_launcher_background.png")
        legacy = round(48 * factor)
        plate = Image.new("RGBA", (legacy, legacy), (0, 0, 0, 0))
        ImageDraw.Draw(plate).rounded_rectangle((0, 0, legacy - 1, legacy - 1), radius=legacy // 5, fill=BACKGROUND + (255,))
        centred(fitted(logo, legacy, LEGACY_FIT), legacy, plate).save(folder / "ic_launcher.png")


if __name__ == "__main__":
    main()
