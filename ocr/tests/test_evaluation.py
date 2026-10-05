from contextlib import redirect_stdout
from email.message import Message
from io import BytesIO, StringIO
import json
import os
from pathlib import Path
import random
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from evaluate import (
    compare_reports, edit_distance, evaluate_text, extract_ocr_response, extract_prices,
    extract_raw_text, load_manifest, main, normalize_text, run_evaluation, validate_endpoint,
)


class FakeResponse:
    def __init__(self, payload, content_type="application/json; charset=utf-8", status=200):
        self.payload = payload
        self.status = status
        self.headers = Message()
        self.headers["Content-Type"] = content_type

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self, limit):
        return self.payload[:limit]


class FakeOpener:
    def __init__(self, response=None, error=None):
        self.response, self.error = response, error
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        if self.error:
            raise self.error
        return self.response


class EvaluationMetricsTest(unittest.TestCase):
    def test_normalizes_case_and_spaces_without_dropping_duplicate_lines(self):
        self.assertEqual("leche 1200,00\nleche 1200,00", normalize_text(" LECHE  1200,00\n\nleche\t1200,00 "))
        metrics = evaluate_text("LECHE 1200,00\nLECHE 1200,00", " leche  1200,00 ")
        self.assertEqual(1, metrics["lines"]["matched"])
        self.assertEqual(1, metrics["lines"]["missing"])
        self.assertEqual(0.5, metrics["lines"]["recall"])
        self.assertEqual(1, metrics["prices"]["missing"])

    def test_exact_price_matches_keep_duplicates_and_report_extra_occurrences(self):
        metrics = evaluate_text("LECHE 1200,00\nLECHE 1200,00\nPAN 500,00", "LECHE 1200,00\nJABON 800,00")
        for field in ("lines", "prices"):
            self.assertEqual(3, metrics[field]["expected"])
            self.assertEqual(1, metrics[field]["matched"])
            self.assertEqual(2, metrics[field]["missing"])
            self.assertEqual(1, metrics[field]["additional"])

    def test_prices_are_exact_numeric_matches_without_a_tolerance(self):
        self.assertEqual(["1200.00", "1200.00", "2500.00"], extract_prices("$ 1.200,00 1,200.00 $2.500"))
        self.assertEqual(["-500.00"], extract_prices("DESCUENTO -500,00"))
        metrics = evaluate_text("PRECIO 1200,00", "PRECIO 1200,01")
        self.assertEqual(0, metrics["prices"]["matched"])
        self.assertEqual(1, metrics["prices"]["missing"])
        self.assertEqual(1, metrics["prices"]["additional"])

    def test_explicit_price_target_overrides_extraction_from_expected_text(self):
        metrics = evaluate_text("PRODUCTO SIN PRECIO", "PRODUCTO $1200", ["1.200,00"])
        self.assertEqual(1, metrics["prices"]["matched"])
        self.assertEqual([], extract_prices("05/10/2026 CUIT 20-12345678-3 CANT 0,2950"))

    def test_empty_text_and_missing_text_have_defined_character_errors(self):
        self.assertEqual(0, evaluate_text("", "")["character_error_rate"])
        self.assertEqual(1, evaluate_text("LECHE", "")["character_error_rate"])
        self.assertEqual(3, evaluate_text("", "ABC")["character_errors"])
        self.assertEqual(3, edit_distance("kitten", "sitting"))

    def test_bit_vector_edit_distance_matches_reference_for_varied_strings(self):
        def reference(expected, actual):
            previous = list(range(len(actual) + 1))
            for row_index, expected_char in enumerate(expected, start=1):
                current = [row_index]
                for column_index, actual_char in enumerate(actual, start=1):
                    current.append(min(current[-1] + 1, previous[column_index] + 1,
                                       previous[column_index - 1] + (expected_char != actual_char)))
                previous = current
            return previous[-1]

        randomizer = random.Random(54)
        for _ in range(200):
            expected = "".join(randomizer.choices("abc123é\n", k=randomizer.randrange(30)))
            actual = "".join(randomizer.choices("abc123é\n", k=randomizer.randrange(30)))
            self.assertEqual(reference(expected, actual), edit_distance(expected, actual))


class EvaluationHttpTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.image = Path(self.directory.name) / "synthetic.png"
        self.image.write_bytes(b"synthetic-image-bytes")

    def test_uploads_the_backend_file_field_and_reads_real_raw_text(self):
        opener = FakeOpener(FakeResponse(json.dumps({"rawText": "OCR REAL 1200,00", "items": []}).encode()))

        text = extract_raw_text(self.image, timeout=321, opener=opener)

        self.assertEqual("OCR REAL 1200,00", text)
        request, timeout = opener.requests[0]
        self.assertEqual("POST", request.method)
        self.assertEqual(321, timeout)
        self.assertIn(b'name="file"; filename="synthetic.png"', request.data)
        self.assertIn(b"Content-Type: image/png\r\n", request.data)
        self.assertIn(self.image.read_bytes(), request.data)
        boundary = request.get_header("Content-type").split("boundary=", 1)[1]
        self.assertTrue(request.data.endswith(f"--{boundary}--\r\n".encode()))

    def test_reports_http_errors_without_copying_the_response_body(self):
        error = urllib.error.HTTPError("http://127.0.0.1:8080", 502, "Failed", Message(), BytesIO(b"PRIVATE TRANSCRIPT"))
        opener = FakeOpener(error=error)
        with self.assertRaisesRegex(ValueError, "HTTP 502 del backend"):
            extract_raw_text(self.image, opener=opener)

    def test_reads_text_from_a_twenty_page_response_with_bounded_previews(self):
        pages = [{"imageDataUrl": "data:image/jpeg;base64," + "A" * 1_200_000} for _ in range(20)]
        response = FakeResponse(json.dumps({"rawText": "LECHE 1200,00", "ocrReview": pages}).encode())
        self.assertEqual("LECHE 1200,00", extract_raw_text(self.image, opener=FakeOpener(response)))

    def test_rejects_non_json_invalid_json_and_missing_raw_text(self):
        responses = (
            FakeResponse(b"<html>error</html>", "text/html"),
            FakeResponse(b"{bad-json"),
            FakeResponse(b'{"items": []}'),
            FakeResponse(b'{"rawText": 123}'),
            FakeResponse(b"[]"),
        )
        for response in responses:
            with self.subTest(payload=response.payload), self.assertRaises(ValueError):
                extract_raw_text(self.image, opener=FakeOpener(response))

    def test_endpoint_is_loopback_and_redirects_are_not_followed(self):
        for endpoint in ("http://localhost:8080/api/receipts/extract", "http://127.0.0.1:8080", "http://[::1]:8080"):
            self.assertEqual(endpoint, validate_endpoint(endpoint))
        for endpoint in ("https://example.com/ocr", "http://192.168.1.2:8080", "file:///tmp/image", "http://user:secret@localhost:8080"):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                validate_endpoint(endpoint)
        with patch("evaluate.urllib.request.build_opener") as build:
            build.return_value = FakeOpener(FakeResponse(b'{"rawText": ""}'))
            extract_ocr_response(self.image)
            proxy_handler, redirect_handler = build.call_args.args
            self.assertEqual({}, proxy_handler.proxies)
            self.assertIsNone(redirect_handler.redirect_request(None, None, 302, "Found", Message(), "http://example.com"))


class EvaluationReportTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "images").mkdir()
        self.image = self.root / "images" / "synthetic.png"
        self.image.write_bytes(b"synthetic-image")
        self.manifest = self.root / "manifest.json"
        self.write_manifest([{"id": "synthetic", "image": "images/synthetic.png", "expected_text": "PRODUCTO 1200,00"}])

    def write_manifest(self, cases):
        self.manifest.write_text(json.dumps({"cases": cases}), encoding="utf-8")

    def test_manifest_resolves_image_and_text_paths_relative_to_it(self):
        (self.root / "target.txt").write_text("PRODUCTO 1200,00", encoding="utf-8")
        self.write_manifest([{"id": "synthetic", "image": "images/synthetic.png", "text_file": "target.txt"}])
        cases = load_manifest(self.manifest)
        self.assertEqual(self.image.resolve(), cases[0]["image"].resolve())
        self.assertEqual((self.root / "target.txt").resolve(), cases[0]["text_file"].resolve())
        report = run_evaluation(cases, extractor=lambda *args: {"rawText": "PRODUCTO 1200,00", "variant": "original-original", "score": 98.0})
        self.assertEqual(0, report["summary"]["character_error_rate"])
        self.assertEqual("original-original", report["cases"][0]["variant"])
        self.assertEqual(98.0, report["cases"][0]["score"])
        serialized = json.dumps(report)
        self.assertNotIn("PRODUCTO", serialized)
        self.assertNotIn(str(self.root), serialized)

    def test_failures_remain_explicit_and_do_not_count_as_successful_metrics(self):
        self.write_manifest([
            {"id": "valid", "image": "images/synthetic.png", "expected_text": "PRODUCTO 1200,00"},
            {"id": "missing", "image": "images/missing.png", "expected_text": "PRODUCTO 1200,00"},
        ])
        report = run_evaluation(load_manifest(self.manifest), extractor=lambda *args: "PRODUCTO 1200,00")
        self.assertEqual(1, report["summary"]["successful_cases"])
        self.assertEqual(1, report["summary"]["failed_cases"])
        self.assertEqual(1, report["summary"]["prices"]["expected"])
        self.assertEqual("error", report["cases"][1]["status"])
        self.assertNotIn("metrics", report["cases"][1])

    def test_network_errors_are_recorded_per_case(self):
        def failing(*args):
            raise urllib.error.URLError("connection refused")

        report = run_evaluation(load_manifest(self.manifest), extractor=failing)
        self.assertEqual(1, report["summary"]["failed_cases"])
        self.assertIsNone(report["summary"]["character_error_rate"])
        self.assertEqual("URLError", report["cases"][0]["error"]["type"])

    def test_comparison_uses_only_unchanged_case_contents(self):
        cases = load_manifest(self.manifest)
        previous = run_evaluation(cases, extractor=lambda *args: "PRODUCTO 1200,01")
        current = run_evaluation(cases, extractor=lambda *args: "PRODUCTO 1200,00")
        comparison = compare_reports(current, previous)
        self.assertEqual(["synthetic"], comparison["compared_ids"])
        self.assertLess(comparison["deltas"]["character_error_rate"], 0)
        self.assertEqual(1, comparison["deltas"]["prices_recall"])

        self.image.write_bytes(b"different-synthetic-image")
        changed_image = run_evaluation(cases, extractor=lambda *args: "PRODUCTO 1200,00")
        self.assertEqual(["synthetic"], compare_reports(changed_image, previous)["not_compared_ids"])
        self.assertIsNone(compare_reports(changed_image, previous)["deltas"])

    def test_comparison_does_not_invent_missing_or_failed_cases(self):
        previous = run_evaluation(load_manifest(self.manifest), extractor=lambda *args: "PRODUCTO 1200,00")
        failed = run_evaluation(load_manifest(self.manifest), extractor=lambda *args: None)
        self.assertEqual([], compare_reports(failed, previous)["compared_ids"])
        current = {"cases": []}
        self.assertEqual(["synthetic"], compare_reports(current, previous)["only_previous_ids"])

    def test_changed_expected_text_is_incompatible_with_the_previous_report(self):
        previous = run_evaluation(load_manifest(self.manifest), extractor=lambda *args: "PRODUCTO 1200,00")
        self.write_manifest([{"id": "synthetic", "image": "images/synthetic.png", "expected_text": "PRODUCTO 1300,00"}])
        current = run_evaluation(load_manifest(self.manifest), extractor=lambda *args: "PRODUCTO 1300,00")
        self.assertEqual(["synthetic"], compare_reports(current, previous)["not_compared_ids"])

    def test_cli_writes_the_requested_report_without_transcripts(self):
        output = self.root / "reports" / "report.json"
        with patch("evaluate.extract_ocr_response", return_value={"rawText": "PRODUCTO 1200,00"}), redirect_stdout(StringIO()):
            exit_code = main([str(self.manifest), "--output", str(output)])
        self.assertEqual(0, exit_code)
        report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(1, report["summary"]["successful_cases"])
        self.assertNotIn("rawText", output.read_text(encoding="utf-8").replace("todos los importes reconocidos en rawText", ""))

    def test_invalid_manifest_is_rejected_before_any_upload(self):
        case = {"id": "duplicate", "image": "images/synthetic.png", "expected_text": "PRODUCTO"}
        self.write_manifest([case, case])
        with self.assertRaisesRegex(ValueError, "únicos"):
            load_manifest(self.manifest)


if __name__ == "__main__":
    unittest.main()
