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

CHALLENGE_PRODUCTS = {
    "calibration": ("PAPEL COCINA DOBLE HOJA 2 UN", "JABON DE TOCADOR NEUTRO 90 G", "AZUCAR RUBIA 1 KG"),
    "holdout": ("ARROZ PARBOIL LARGO 1 KG", "FIDEOS SECOS CODITOS 500 G", "YERBA COMPUESTA 500 G"),
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


def generate_challenge(output, font_path=None):
    """Banco más exigente, separado del original y con referencias previas al OCR.

    Los casos reservados no deben usarse para ajustar reglas. Después de inspeccionar
    sus errores pasan a ser desarrollo y se necesita una nueva versión reservada.
    """
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    font = load_font(font_path)
    manifests = {}
    for partition, products in CHALLENGE_PRODUCTS.items():
        cases = []
        for index in range(8):
            date = f"{index + 1}/08/2026" if partition == "calibration" else f"{index + 15}/07/2026"
            descriptions = [*products, products[index % len(products)]]
            quantities = [Decimal("0.375"), Decimal("2"), Decimal("1"), Decimal("1")]
            prices = [Decimal(800 + index * 80 + position * 400) for position in range(4)]
            money = lambda value: f"{value:.2f}".replace(".", ",")
            items = [{"descripcion": text.title(), "cantidad": str(quantity).replace(".", ","),
                      "precioUnitario": money(price)} for text, quantity, price in zip(descriptions, quantities, prices)]
            total = sum(quantity * price for quantity, price in zip(quantities, prices))
            name = f"challenge-{partition}-{index + 1:02}"
            reference = {"date": date, "total": money(total), "items": items}
            # La referencia se fija antes de renderizar o reconocer la imagen.
            (output / f"{name}.json").write_text(json.dumps(reference, ensure_ascii=False, indent=2), encoding="utf-8")
            lines = ["SUPERMERCADO", "COMERCIO SINTETICO", f"Fecha {date}", ""]
            for text, quantity, price in zip(descriptions, quantities, prices):
                if quantity != 1: lines.append(f"{str(quantity).replace('.', ',')} x {money(price)}")
                lines.append(f"{text:<43}{money(quantity * price):>12}")
            lines.extend(["", f"TOTAL {money(total)}", "GRACIAS POR SU COMPRA"])
            (output / f"{name}.txt").write_text("\n".join(lines), encoding="utf-8")
            image = Image.new("RGB", (1260, 1000), "white")
            draw = ImageDraw.Draw(image)
            for position, line in enumerate(lines): draw.text((35, 25 + position * 62), line, font=font, fill=(25, 25, 25))
            profile = index % 4
            if profile == 1: image = image.rotate(-1.5, expand=True, fillcolor="white", resample=Image.Resampling.BICUBIC)
            elif profile == 2: image = ImageEnhance.Contrast(image).enhance(.45)
            elif profile == 3: image = image.rotate(90, expand=True, fillcolor="white").filter(ImageFilter.GaussianBlur(.5))
            image.save(output / f"{name}.png")
            cases.append({"id": name, "image": f"{name}.png", "receipt_file": f"{name}.json",
                          "text_file": f"{name}.txt", "profile": profile})
        target = output / f"manifest-challenge-{partition}.json"
        target.write_text(json.dumps({"partition": partition, "reference_origin": "synthetic-before-ocr",
                                      "cases": cases}, indent=2), encoding="utf-8")
        manifests[partition] = target
    return manifests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("test-data/synthetic-corpus"))
    parser.add_argument("--font", type=Path)
    parser.add_argument("--challenge", action="store_true", help="Genera 16 casos con variantes, repeticiones y pesos")
    args = parser.parse_args()
    generator = generate_challenge if args.challenge else generate
    for partition, path in generator(args.output, args.font).items():
        print(f"{partition}: {path}")


if __name__ == "__main__":
    main()
