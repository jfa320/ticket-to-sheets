"""Genera tickets ficticios reproducibles; referencias escritas antes del OCR.

Las dos particiones tienen productos, fechas e importes distintos. No reemplazan
un conjunto independiente de fotos reales. No se usan marcas de compras privadas.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont


PRODUCTS = {
    "calibration": ("ARROZ", "FIDEOS", "AZUCAR"),
    "holdout": ("LECHE", "PAN", "YERBA"),
}


def load_font(path=None):
    candidates = [path] if path else ["DejaVuSansMono.ttf", "C:/Windows/Fonts/cour.ttf"]
    for candidate in candidates:
        try:
            return ImageFont.truetype(str(candidate), 30)
        except OSError:
            continue
    raise ValueError("No hay fuente monoespaciada. Indicá --font con un archivo TTF local.")


def generate(output, font_path=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    font = load_font(font_path)
    manifests = {}
    for partition, products in PRODUCTS.items():
        cases = []
        for index in range(10):
            date = f"{index + 1:02}/10/2026" if partition == "calibration" else f"{index + 11:02}/09/2026"
            offset = 0 if partition == "calibration" else 75
            prices = [Decimal(500 + offset + index * 25 + position * 200) for position in range(3)]
            items = [{"descripcion": product.title(), "cantidad": "1", "precioUnitario": str(price)}
                     for product, price in zip(products, prices)]
            money = lambda amount: f"{amount:.2f}".replace(".", ",")
            lines = ["SUPERMERCADO", "COMERCIO SINTETICO", f"Fecha {date}", "",
                     *[f"{product:<20}{money(price):>10}" for product, price in zip(products, prices)],
                     "", f"TOTAL {money(sum(prices))}", "GRACIAS POR SU COMPRA"]
            image = Image.new("RGB", (840, 620), "white")
            draw = ImageDraw.Draw(image)
            for position, line in enumerate(lines):
                draw.text((35, 25 + position * 52), line, font=font, fill=(30, 30, 30))
            # Incluye rotación, impresión tenue y desenfoque moderado, sin ruido aleatorio.
            profile = index % 5
            if profile == 1:
                image = image.rotate(90, expand=True, fillcolor="white")
            elif profile == 2:
                image = image.rotate(-2, expand=True, fillcolor="white", resample=Image.Resampling.BICUBIC)
            elif profile == 3:
                image = ImageEnhance.Contrast(image).enhance(.4)
            elif profile == 4:
                image = image.filter(ImageFilter.GaussianBlur(.7))
            name = f"{partition}-{index + 1:02}"
            image.save(output / f"{name}.png")
            reference = {"items": items, "date": date, "total": str(sum(prices))}
            (output / f"{name}.json").write_text(json.dumps(reference, ensure_ascii=False, indent=2), encoding="utf-8")
            cases.append({"id": name, "image": f"{name}.png", "receipt_file": f"{name}.json"})
        target = output / f"manifest-{partition}.json"
        target.write_text(json.dumps({"cases": cases}, indent=2), encoding="utf-8")
        manifests[partition] = target
    return manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("test-data/synthetic-corpus"))
    parser.add_argument("--font", type=Path)
    args = parser.parse_args()
    for partition, path in generate(args.output, args.font).items():
        print(f"{partition}: {path}")


if __name__ == "__main__":
    main()
