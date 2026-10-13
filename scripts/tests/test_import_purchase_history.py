import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

SPEC = importlib.util.spec_from_file_location("history_import", Path(__file__).resolve().parents[1] / "import_purchase_history.py")
history = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(history)


class ImportHistoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "synthetic.xlsx"

    def workbook(self, tabs):
        ns = history.MAIN
        with zipfile.ZipFile(self.path, "w") as archive:
            workbook = ET.Element(ns + "workbook")
            sheets = ET.SubElement(workbook, ns + "sheets")
            relations = ET.Element("Relationships")
            for index, (name, rows) in enumerate(tabs, 1):
                ET.SubElement(sheets, ns + "sheet", {"name": name, history.REL + "id": f"rId{index}"})
                ET.SubElement(relations, "Relationship", {"Id": f"rId{index}", "Target": f"worksheets/sheet{index}.xml"})
                sheet = ET.Element(ns + "worksheet")
                data = ET.SubElement(sheet, ns + "sheetData")
                for number, values in enumerate(rows, 1):
                    row = ET.SubElement(data, ns + "row", {"r": str(number)})
                    for column, value in values.items():
                        cell = ET.SubElement(row, ns + "c", {"r": f"{column}{number}", "t": "inlineStr"})
                        text = ET.SubElement(ET.SubElement(cell, ns + "is"), ns + "t")
                        text.text = value
                archive.writestr(f"xl/worksheets/sheet{index}.xml", ET.tostring(sheet))
            archive.writestr("xl/workbook.xml", ET.tostring(workbook))
            archive.writestr("xl/_rels/workbook.xml.rels", ET.tostring(relations))

    def header(self):
        return dict(A="Descripción", B="Marca", C="Lugar de compra", D="Categoría")

    def product(self):
        return dict(A="Jabón neutro 500gr", B="Marca Prueba", C="Comercio prueba", D="Limpieza",
                    F="PRECIO PRIVADO", G="FECHA PRIVADA")

    def test_all_tabs_are_read_and_duplicates_deduplicated_without_prices_or_dates(self):
        self.workbook([("Mes uno", [{"A": "Título"}, self.header(), self.product(), self.product()]),
                       ("Mes dos", [self.header(), self.product()]), ("Resumen", [{"A": "Total"}])])
        result = history.read_history(self.path)
        self.assertEqual(result["rowCount"], 3)
        self.assertEqual(len(result["products"]), 1)
        self.assertEqual(result["sheets"], ["Mes uno", "Mes dos"])
        self.assertEqual(result["skippedSheets"], ["Resumen"])
        self.assertEqual(set(result["products"][0]), {"descripcion", "marca", "comercio", "categoria"})
        self.assertNotIn("PRIVAD", json.dumps(result))

    def test_sparse_reordered_columns_generic_brands_and_store_aliases(self):
        header = dict(B="Marca", F="Lugar de compra", H="Descripcion", J="Categoria")
        self.workbook([("Mes", [header, dict(B="-", F="Alias OCR", H="Producto conocido", J="Otros")])])
        product = history.read_history(self.path, {"alias ocr": "Comercio prueba"})["products"][0]
        self.assertEqual(product["marca"], "Genérico")
        self.assertEqual(product["comercio"], "Comercio prueba")

    def test_incomplete_rows_and_discounts_are_not_products(self):
        self.workbook([("Mes", [self.header(), self.product(), dict(A="Descuento", C="Comercio prueba"),
                                dict(A="Total", C="Comercio prueba"), dict(A="Sin comercio"), dict(C="Sin nombre")])])
        self.assertEqual(history.read_history(self.path)["rowCount"], 1)

    def test_invalid_workbook_does_not_replace_existing_catalog(self):
        destination = Path(self.tmp.name) / "local-history.json"
        destination.write_text('{"previous":true}', encoding="utf-8")
        self.workbook([("Resumen", [dict(A="Total")])])
        with self.assertRaises(ValueError):
            history.read_history(self.path)
        self.assertEqual(destination.read_text(), '{"previous":true}')

    def test_atomic_save_preserves_previous_catalog_as_backup(self):
        destination = Path(self.tmp.name) / "data/local-history.json"
        history.save_history({"products": ["primero"]}, destination)
        history.save_history({"products": ["segundo"]}, destination)
        self.assertEqual(json.loads(destination.read_text())["products"], ["segundo"])
        self.assertEqual(json.loads(destination.with_name(destination.name + ".bak").read_text())["products"], ["primero"])
        self.assertEqual(list(destination.parent.glob("*.tmp")), [])


if __name__ == "__main__":
    unittest.main()
