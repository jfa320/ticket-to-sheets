import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receipt_metrics import evaluate_receipt, latency_summary, validate_receipt
from evaluate import run_evaluation, load_manifest, compare_reports


def product(name, price, quantity="1"):
    return {"descripcion": name, "cantidad": quantity, "precioUnitario": price}


class ReceiptMetricsTest(unittest.TestCase):
    def test_reviewed_association_does_not_count_a_wrong_name_as_correct(self):
        target = product("Leche 1L", "1200")
        target["association_descriptions"] = ["Lech3 1L"]
        result = evaluate_receipt({"items": [target]}, {"items": [product("Lech3 1L", "1200")]})
        self.assertEqual(0, result["items"]["matched"])
        self.assertEqual(0, result["fields"]["items.descripcion"]["matched"])
        self.assertEqual(1, result["fields"]["items.precioUnitario"]["matched"])
        self.assertEqual(1, result["fields"]["items.cantidad"]["matched"])
        with self.assertRaises(ValueError):
            validate_receipt({"items": [target, product("Lech3 1L", "700")]})

    def test_swapped_prices_fail_despite_identical_price_sets(self):
        expected = {"items": [product("Leche", "1200,00"), product("Pan", "500,00")]}
        result = evaluate_receipt(expected, {"items": [product("Pan", "1200.00"), product("Leche", "500.00")]})
        self.assertEqual(0, result["items"]["matched"])
        self.assertEqual(1, result["fields"]["items.descripcion"]["accuracy"])
        self.assertEqual(0, result["fields"]["items.precioUnitario"]["accuracy"])

    def test_reordering_duplicate_names_keeps_quantities_and_prices_associated(self):
        expected = {"items": [product("Leche", "1.200,00", "0,875"), product("Leche", "500", "2")]}
        result = evaluate_receipt(expected, {"items": [product("LECHE", "500.00", "2.0"), product("leche", "1200", "0.875")]})
        self.assertEqual(2, result["items"]["matched"])
        self.assertEqual(1, result["items"]["recall"])

    def test_numeric_comparison_does_not_round_away_small_differences(self):
        expected = {"items": [product("Leche", "1.000000000000000000000000000001")]}
        result = evaluate_receipt(expected, {"items": [product("Leche", "1.000000000000000000000000000002")]})
        self.assertEqual(0, result["items"]["matched"])

    def test_missing_products_count_as_field_errors_and_extra_products_reduce_precision(self):
        expected = {"items": [product("Leche", "1200"), product("Pan", "500")]}
        result = evaluate_receipt(expected, {"items": [product("Leche", "1200"), product("Extra", "800")]})
        self.assertEqual(1, result["items"]["missing"])
        self.assertEqual(1, result["items"]["additional"])
        self.assertEqual(.5, result["fields"]["items.cantidad"]["accuracy"])

    def test_metadata_dates_and_optional_fields_are_compared_exactly(self):
        expected = {"storeName": "Mercado", "date": "4/10/2026", "total": "1.200,00",
                    "items": [dict(product("Leche", "1200"), marca="Marca", fecha="4/10/2026")]}
        response = copy.deepcopy(expected)
        response["date"] = "04/10/2025"
        response["items"][0]["marca"] = "Otra"
        result = evaluate_receipt(expected, response)
        self.assertEqual(0, result["fields"]["date"]["accuracy"])
        self.assertEqual(0, result["fields"]["items.marca"]["accuracy"])
        self.assertNotIn("Leche", json.dumps(result))

    def test_invalid_targets_and_malformed_products_are_rejected(self):
        for value in ({}, {"items": [{"descripcion": "Leche"}]}, {"items": [product("Leche", "oops")]},
                      {"items": [dict(product("Leche", "1"), fecha="31/2/2026")]}):
            with self.assertRaises(ValueError):
                validate_receipt(value)
        with self.assertRaises(ValueError):
            evaluate_receipt({"items": []}, {"items": [None]})

    def test_latency_percentiles_use_linear_interpolation_and_handle_empty_runs(self):
        self.assertEqual({"count": 0, "p50": None, "p95": None, "max": None}, latency_summary([]))
        self.assertEqual(2.5, latency_summary([1, 2, 3, 4])["p50"])
        self.assertEqual(3.85, latency_summary([1, 2, 3, 4])["p95"])
        self.assertEqual(3, latency_summary([3])["p95"])

    def test_annotations_join_the_case_hash_and_reports_keep_only_metrics(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "image.png").write_bytes(b"synthetic")
            target = {"items": [product("Private product", "1200")]}
            (root / "expected.json").write_text(json.dumps(target), encoding="utf-8")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"cases": [{"id": "case-1", "image": "image.png", "expected_text": "OCR",
                                                       "receipt_file": "expected.json"}]}), encoding="utf-8")
            cases = load_manifest(manifest)
            response = dict(target, rawText="OCR")
            before = run_evaluation(cases, extractor=lambda *args: response)
            self.assertEqual(1, before["summary"]["receipts"]["annotated_cases"])
            self.assertEqual(1, before["summary"]["receipts"]["items"]["matched"])
            self.assertNotIn("Private product", json.dumps(before))
            self.assertNotIn(str(root), json.dumps(before))
            same = run_evaluation(cases, extractor=lambda *args: response)
            self.assertEqual(0, compare_reports(same, before)["deltas"]["items_recall"])
            cases[0]["expected_receipt"]["items"][0]["precioUnitario"] = "1300"
            changed = run_evaluation(cases, extractor=lambda *args: response)
            self.assertEqual([], compare_reports(changed, before)["compared_ids"])

    def test_backend_without_items_fails_only_the_annotated_case(self):
        with tempfile.TemporaryDirectory() as folder:
            image = Path(folder) / "image.png"
            image.write_bytes(b"synthetic")
            cases = [{"id": "one", "image": image, "expected_text": "OCR", "expected_receipt": {"items": []}},
                     {"id": "two", "image": image, "expected_text": "OCR"}]
            report = run_evaluation(cases, extractor=lambda *args: {"rawText": "OCR"})
            self.assertEqual(1, report["summary"]["failed_cases"])
            self.assertEqual(0, report["summary"]["receipts"]["annotated_cases"])

    def test_receipt_only_manifest_does_not_invent_a_text_transcription(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "image.png").write_bytes(b"synthetic")
            expected = {"items": [product("Leche", "1200")]}
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"cases": [{"id": "one", "image": "image.png",
                                                        "expected_receipt": expected}]}), encoding="utf-8")
            report = run_evaluation(load_manifest(manifest), extractor=lambda *args: dict(expected, rawText="OCR"))
            self.assertEqual(1, report["summary"]["receipts"]["items"]["matched"])
            self.assertEqual(0, report["summary"]["text_annotated_cases"])
            self.assertIsNone(report["summary"]["character_error_rate"])
            self.assertEqual(0, compare_reports(report, report)["deltas"]["items_recall"])
