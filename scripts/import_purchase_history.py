"""Importa nombres de todas las pestañas de un XLSX al catálogo local (sin precios)."""
import argparse
import json
import os
import posixpath
import re
import shutil
import tempfile
import unicodedata
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
HEADERS = ("descripcion", "marca", "lugar de compra", "categoria")
ROOT = Path(__file__).resolve().parents[1]


def normalize(value):
    text = unicodedata.normalize("NFD", value.lower())
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def cell_text(cell, strings):
    if cell.get("t") == "inlineStr":
        return "".join(t.text or "" for t in cell.iter(MAIN + "t"))
    value = cell.find(MAIN + "v")
    if value is None or value.text is None:
        return ""
    return strings[int(value.text)] if cell.get("t") == "s" else ""


def read_history(source, mappings=None):
    products, included, skipped = {}, [], []
    row_count = 0
    aliases = {normalize(k): v for k, v in (mappings or {}).items()}
    with zipfile.ZipFile(source) as archive:
        if sum(item.file_size for item in archive.infolist()) > 64_000_000:
            raise ValueError("El archivo descomprimido supera 64 MB.")
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            strings = ["".join(t.text or "" for t in s.iter(MAIN + "t"))
                       for s in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        relationships = {r.get("Id"): r.get("Target") for r in
                         ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
                         if r.get("TargetMode") != "External"}
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        for sheet in workbook.find(MAIN + "sheets"):
            target = relationships.get(sheet.get(REL + "id"), "")
            name = sheet.get("name", "")
            path = posixpath.normpath(target.lstrip("/") if target.startswith("/") else "xl/" + target)
            if not path.startswith("xl/") or path not in archive.namelist():
                skipped.append(name)
                continue
            header = None
            for row in ET.fromstring(archive.read(path)).iter(MAIN + "row"):
                cells = {re.sub(r"\d", "", c.get("r", "")): cell_text(c, strings).strip()
                         for c in row if c.tag == MAIN + "c"}
                if header is None:
                    indexed = {normalize(value): column for column, value in cells.items()}
                    if all(field in indexed for field in HEADERS):
                        header = [indexed[field] for field in HEADERS]
                    continue
                fields = [re.sub(r"\s+", " ", cells.get(column, "")).strip() for column in header]
                if not fields[0] or not fields[2] or normalize(fields[0]) in {"descripcion", "descuento", "total", "subtotal"}:
                    continue
                if any(len(v) > 1000 or re.search(r"[\x00-\x1f]", v) for v in fields):
                    raise ValueError("Hay campos de producto inválidos; se conserva el catálogo anterior.")
                store_key = normalize(fields[2])
                fields[2] = next((value for alias, value in aliases.items() if alias and alias in store_key), fields[2])
                if normalize(fields[1]) in {"", "generico", "sin marca"}:
                    fields[1] = "Genérico"
                product = dict(zip(("descripcion", "marca", "comercio", "categoria"), fields))
                products.setdefault(tuple(normalize(v) for v in fields), product)
                row_count += 1
                if row_count > 12500:
                    raise ValueError("El historial supera 12.500 filas de productos.")
            (included if header else skipped).append(name)
    if not products:
        raise ValueError("No hay productos en pestañas con descripción, marca, comercio y categoría.")
    return dict(enabled=True, spreadsheetId="", headerRow=2, updatedAt=datetime.now(timezone.utc).isoformat(),
                rowCount=row_count, sheets=included, skippedSheets=skipped, products=list(products.values()))


def save_history(snapshot, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", prefix=".local-history.", suffix=".tmp",
                                     dir=destination.parent, delete=False) as output:
        staged = Path(output.name)
        json.dump(snapshot, output, ensure_ascii=False, indent=2)
        output.flush()
        os.fsync(output.fileno())
    try:
        if destination.exists():
            shutil.copy2(destination, destination.with_name(destination.name + ".bak"))
        os.replace(staged, destination)
    finally:
        staged.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "data/local-history.json")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    mappings = json.loads((ROOT / "src/main/resources/store-mappings.json").read_text(encoding="utf-8"))
    snapshot = read_history(args.source, mappings)
    if not args.dry_run:
        save_history(snapshot, args.output)
    print(json.dumps({"rows": snapshot["rowCount"], "products": len(snapshot["products"]),
                      "sheets": snapshot["sheets"], "skippedSheets": snapshot["skippedSheets"],
                      "saved": not args.dry_run}, ensure_ascii=True))


if __name__ == "__main__":
    main()
