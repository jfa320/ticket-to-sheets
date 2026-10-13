import importlib.util
import json
import os
import sys
import types
import unittest
import threading
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
from unittest.mock import Mock, patch

from PIL import Image, ImageDraw

OCR_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(OCR_ROOT))


def detection(text, top, bottom, confidence):
    return {
        "text": text, "confidence": confidence, "score": confidence,
        "box": [[10, top], [180, top], [180, bottom], [10, bottom]],
        "left": 10, "right": 180, "top": top, "bottom": bottom,
        "width": 170, "height": bottom - top,
    }


@unittest.skipUnless(importlib.util.find_spec("flask"), "Flask is not installed")
class OcrServiceTest(unittest.TestCase):
    def setUp(self):
        # Exercise Flask and image orchestration without downloading OCR models.
        # Keep compiled image dependencies outside the temporary module patch.
        importlib.import_module("image_variants")
        paddle = types.ModuleType("paddle")
        paddle.set_flags = Mock()
        paddleocr = types.ModuleType("paddleocr")
        paddleocr.PaddleOCR = Mock()
        spec = importlib.util.spec_from_file_location("receipt_ocr_service_under_test", OCR_ROOT / "service.py")
        self.service = importlib.util.module_from_spec(spec)
        with patch.dict(sys.modules, {"paddle": paddle, "paddleocr": paddleocr}):
            spec.loader.exec_module(self.service)

    def test_detector_limit_matches_unscaled_region_size(self):
        with patch.object(self.service, "model_path_options", return_value={}):
            self.service.get_ocr("es")
        self.assertEqual(1400, self.service.PaddleOCR.call_args.kwargs["det_limit_side_len"])
        self.assertEqual(self.service.OCR_MAX_REGION_SIDE,
                         self.service.PaddleOCR.call_args.kwargs["det_limit_side_len"])

    def test_budget_returns_best_completed_reading_without_starting_another_pass(self):
        image = Image.new("RGB", (200, 100), "white")
        weak = [detection("LECH3 1200,00", 10, 30, 0.65)]
        correct = [detection("LECHE 1200,00", 10, 30, 0.96)]
        elapsed = [0]

        def recognize(*args):
            elapsed[0] += 30
            items = weak if elapsed[0] == 30 else correct
            return items, items

        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([
                    ("first", image), ("best", image), ("unnecessary", image)])), \
                patch.object(self.service, "ocr_image", side_effect=recognize) as read, \
                patch.object(self.service.time, "perf_counter", side_effect=lambda: elapsed[0]), \
                patch.object(self.service, "save_debug_image"), \
                patch.object(self.service, "write_debug_json") as debug, \
                patch.dict(os.environ, {"OCR_ADAPTIVE_SEARCH": "true", "OCR_SEARCH_BUDGET_SECONDS": "50"}):
            name, lines, _, _ = self.service.run_best_ocr(image, "es", "synthetic-debug")
        self.assertEqual(2, read.call_count)
        self.assertEqual("best", name)
        self.assertEqual(correct, lines)
        summary = next(call.args[2] for call in debug.call_args_list if call.args[1] == "search-summary.json")
        self.assertEqual("time_budget", summary["stopReason"])
        # The selected output is available even though the cooperative deadline expired.
        self.assertEqual(60, elapsed[0])

    def test_three_distinct_consistent_full_readings_stop_before_remaining_variants(self):
        images = [Image.new("RGB", (200, 200), color) for color in ("white", "gray", "black", "red")]
        items = [detection(text, 10 + index * 25, 30 + index * 25, 0.98) for index, text in enumerate((
            "COMERCIO DE PRUEBA", "CUIT 00-00000000-0", "Fecha 12/10/2026",
            "LECHE ENTERA 1200,00", "PAN INTEGRAL 800,00", "TOTAL 2000,00"))]
        entries = [(f"candidate-{index}", candidate, {"frame": "full:0", "size": candidate.size})
                   for index, candidate in enumerate(images)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter(entries)), \
                patch.object(self.service, "ocr_image", return_value=(items, items)) as read, \
                patch.dict(os.environ, {"OCR_ADAPTIVE_SEARCH": "true", "OCR_TARGETED_RETRY": "false"}):
            _, lines, _, _ = self.service.run_best_ocr(images[0], "es")
        self.assertEqual(3, read.call_count)
        self.assertEqual(items, lines)

    def test_difficult_image_reserves_time_for_final_rereads(self):
        image = Image.new("RGB", (200, 100), "white")
        items = [detection("LECH3 1200,00", 10, 30, .65)]
        elapsed = [0]
        def recognize(*args):
            elapsed[0] += 10
            return items, items
        def retry(image, detections, recognize, should_continue=None):
            self.assertTrue(should_continue())
            return detections, {"attemptedRegions": 0, "acceptedRegions": 0, "ocrCalls": 0, "failedCalls": 0}
        entries = [(f"candidate-{i}", image) for i in range(12)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter(entries)), \
                patch.object(self.service, "ocr_image", side_effect=recognize) as read, \
                patch.object(self.service.time, "perf_counter", side_effect=lambda: elapsed[0]), \
                patch.object(self.service, "refine_detections", side_effect=retry) as reread, \
                patch.dict(os.environ, {"OCR_ADAPTIVE_SEARCH": "true", "OCR_TARGETED_RETRY": "true", "OCR_SEARCH_BUDGET_SECONDS": "90"}):
            self.service.run_best_ocr(image, "es")
        self.assertEqual(8, read.call_count)
        reread.assert_called_once()

    def test_identical_pixels_reuse_reading_but_keep_selected_variant_coordinates(self):
        image = Image.new("RGB", (200, 100), "gray")
        items = [detection("LECHE 1200,00", 10, 30, 0.96)]
        entries = [("original-original", image, {"frame": "full:0", "size": image.size}),
                   ("cropped-original", image.copy(), {"frame": "crop:0", "size": image.size}),
                   ("gray-original", image.convert("L"), {"frame": "crop:0", "size": image.size})]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter(entries)), \
                patch.object(self.service, "ocr_image", return_value=(items, items)) as read, \
                patch.dict(os.environ, {"OCR_ADAPTIVE_SEARCH": "true", "OCR_TARGETED_RETRY": "false"}):
            name, lines, boxes, _ = self.service.run_best_ocr(image, "es")
        self.assertEqual(1, read.call_count)
        self.assertEqual("original-original", name)
        self.assertEqual(items, lines)
        self.assertEqual(items[0]["box"], boxes[0]["box"])

    def test_exhaustive_setting_evaluates_all_variants_without_deadline(self):
        image = Image.new("RGB", (200, 100), "white")
        items = [detection("LECHE 1200,00", 10, 30, 0.96)]
        entries = [(f"candidate-{index}", image, {"frame": "full:0", "size": image.size}) for index in range(5)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter(entries)), \
                patch.object(self.service, "ocr_image", return_value=(items, items)) as read, \
                patch.object(self.service, "search_budget_seconds", side_effect=AssertionError("No budget")), \
                patch.dict(os.environ, {"OCR_ADAPTIVE_SEARCH": "false", "OCR_TARGETED_RETRY": "false"}):
            self.service.run_best_ocr(image, "es")
        self.assertEqual(5, read.call_count)

    def test_corrections_setting_reaches_variant_generation_for_baseline_comparison(self):
        image = Image.new("RGB", (200, 100), "white")
        correct = [detection("PRODUCTO 1200,00", 10, 30, 0.95)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([("original-original", image)])) as variants, \
                patch.object(self.service, "ocr_image", return_value=(correct, correct)), \
                patch.dict(os.environ, {"OCR_IMAGE_CORRECTIONS": "false"}):
            self.service.run_best_ocr(image, "es")

        self.assertFalse(variants.call_args.kwargs["document_corrections"])

    def test_ocr_image_reads_unscaled_regions_and_merges_seam_duplicates(self):
        image = Image.new("RGB", (400, 1800), (195, 230, 210))
        responses = iter([
            [[[[10, 1300], [180, 1300], [180, 1320], [10, 1320]], ["LECH3 1200,00", 0.7]]],
            [[[[10, 60], [180, 60], [180, 80], [10, 80]], ["LECHE 1200,00", 0.95]]],
        ])
        seen = []

        def recognize(path, cls):
            with Image.open(path) as region:
                seen.append((path, region.size))
                self.assertEqual((195, 230, 210), region.getpixel((0, 0)))
            self.assertFalse(cls)
            return [next(responses)]

        model = Mock()
        model.ocr.side_effect = recognize
        detections, lines = self.service.ocr_image(model, image)

        self.assertEqual([(400, 1400), (400, 560)], [size for _, size in seen])
        self.assertTrue(all(not os.path.exists(path) for path, _ in seen))
        self.assertEqual(["LECHE 1200,00"], [line["text"] for line in lines])
        self.assertEqual(1300, detections[0]["top"])
        self.assertEqual([[10.0, 1300.0], [180.0, 1300.0], [180.0, 1320.0], [10.0, 1320.0]],
                         detections[0]["box"])

    def test_retry_runs_once_on_winning_variant_and_rebuilds_lines_score_and_debug(self):
        source = Image.new("RGB", (400, 300), "white")
        selected = Image.new("RGB", (200, 100), "gray")
        weak = [detection("LECH3 1200,00", 10, 30, 0.65)]
        refined = [dict(weak[0], text="LECHE 1200,00", confidence=0.96, score=0.96)]
        statistics = {"attemptedRegions": 1, "acceptedRegions": 1, "ocrCalls": 2, "failedCalls": 0}
        model = Mock()
        retry_crop = Image.new("RGB", (90, 40), "white")

        def retry(image, items, recognize, should_continue=None):
            self.assertIs(selected, image)
            self.assertIs(weak, items)
            self.assertTrue(should_continue())
            recognize(retry_crop)
            return refined, statistics

        with patch.object(self.service, "get_ocr", return_value=model), \
                patch.object(self.service, "build_variants", return_value=iter([
                    ("original-original", source), ("geometry-original", selected),
                ])), \
                patch.object(self.service, "ocr_image", side_effect=[([], []), (weak, weak)]), \
                patch.object(self.service, "refine_detections", side_effect=retry) as reread, \
                patch.object(self.service, "recognize_block", return_value=[]) as recognize_block, \
                patch.object(self.service, "save_debug_image") as save_image, \
                patch.object(self.service, "write_debug_json") as save_json, \
                patch.dict(os.environ, {"OCR_TARGETED_RETRY": "true"}):
            name, lines, detections, score = self.service.run_best_ocr(source, "es", "synthetic-debug")

        reread.assert_called_once()
        recognize_block.assert_called_once_with(model, retry_crop)
        self.assertEqual("geometry-original+retry", name)
        self.assertEqual(refined, detections)
        self.assertEqual("LECHE 1200,00", lines[0]["text"])
        self.assertEqual([0], lines[0]["detectionIndexes"])
        self.assertEqual(weak[0]["box"], detections[0]["box"])
        self.assertEqual(0.96, lines[0]["confidence"])
        self.assertEqual(self.service.score_lines(lines), score)
        self.assertGreater(score, self.service.score_lines(weak))
        debug = {call.args[1]: call.args[2] for call in save_json.call_args_list}
        self.assertEqual(lines, debug["detections.json"]["lines"])
        self.assertEqual(score, debug["detections.json"]["score"])
        self.assertEqual(1, debug["targeted-retry.json"]["acceptedRegions"])
        save_image.assert_any_call("synthetic-debug", "selected.png", selected)

    def test_retry_can_be_disabled_for_an_unchanged_baseline(self):
        image = Image.new("RGB", (200, 100), "white")
        weak = [detection("LECH3 1200,00", 10, 30, 0.65)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([("original-original", image)])), \
                patch.object(self.service, "ocr_image", return_value=(weak, weak)), \
                patch.object(self.service, "refine_detections") as reread, \
                patch.dict(os.environ, {"OCR_TARGETED_RETRY": "false"}):
            name, lines, detections, score = self.service.run_best_ocr(image, "es")

        reread.assert_not_called()
        self.assertEqual("original-original", name)
        self.assertEqual(weak, lines)
        self.assertEqual(weak, detections)
        self.assertEqual(self.service.score_lines(weak), score)

    def test_fusion_uses_other_readings_and_keeps_the_winning_preview_frame(self):
        images = [Image.new('RGB', (200, 100), color) for color in ('white', 'gray', 'black')]
        base = [detection('LECH3 1200,00', 10, 30, .6), detection('PAN 500,00', 50, 65, .99),
                detection('YERBA 3000,00', 80, 95, .99)]
        corrected = [detection('LECHE 1200,00', 10, 30, .95)]
        entries = [(f'candidate-{index}', image, {'frame': 'crop:0', 'size': image.size})
                   for index, image in enumerate(images)]
        for enabled in ('true', 'false'):
            with self.subTest(enabled=enabled), patch.object(self.service, 'get_ocr'), \
                    patch.object(self.service, 'build_variants', return_value=iter(entries)), \
                    patch.object(self.service, 'ocr_image', side_effect=[(base, base), (corrected, corrected), (corrected, corrected)]), \
                    patch.dict(os.environ, {'OCR_TARGETED_RETRY': 'false', 'OCR_VARIANT_FUSION': enabled}):
                preview = {}
                name, lines, items, score = self.service.run_best_ocr(images[0], 'es', preview_sink=preview)
            self.assertEqual(3, len(items))
            self.assertEqual(base[0]['box'], items[0]['box'])
            self.assertEqual('LECHE 1200,00' if enabled == 'true' else 'LECH3 1200,00', items[0]['text'])
            self.assertEqual('candidate-0+fusion' if enabled == 'true' else 'candidate-0', name)
            self.assertEqual(200, preview['width'])
            self.assertEqual(self.service.score_lines(lines), score)

    def test_failed_retry_preserves_the_selected_output(self):
        image = Image.new("RGB", (200, 100), "white")
        weak = [detection("LECH3 1200,00", 10, 30, 0.65)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([("original-original", image)])), \
                patch.object(self.service, "ocr_image", return_value=(weak, weak)), \
                patch.object(self.service, "refine_detections", side_effect=RuntimeError("synthetic failure")), \
                patch.dict(os.environ, {"OCR_TARGETED_RETRY": "true"}), \
                self.assertLogs(self.service.app.logger, level="WARNING"):
            name, lines, detections, score = self.service.run_best_ocr(image, "es")

        self.assertEqual("original-original", name)
        self.assertEqual(weak, lines)
        self.assertEqual(weak, detections)
        self.assertEqual(self.service.score_lines(weak), score)

    def test_selection_prefers_confident_product_over_noisy_alternative(self):
        image = Image.new("RGB", (200, 100), "white")
        correct = [detection("ACEITE DE OLIVA 1200,00", 10, 30, 0.95)]
        noisy = [detection("ACEITE DE OLNA 1200,00", 10, 30, 0.6)] * 5
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([
                    ("original-original", image), ("threshold-original", image),
                ])), \
                patch.object(self.service, "ocr_image", side_effect=[(correct, correct), (noisy, noisy)]), \
                patch.dict(os.environ, {"APP_OCR_DEBUG": "false"}):
            name, lines, detections, score = self.service.run_best_ocr(image, "es")

        self.assertEqual("original-original", name)
        self.assertEqual(correct, lines)
        self.assertEqual(correct, detections)
        self.assertGreater(score, 0)

    def test_selection_prefers_aligned_geometry_over_confident_detached_prices(self):
        image = Image.new("RGB", (300, 300), "white")
        complete = [detection(text, index * 30, index * 30 + 20, 0.94) for index, text in enumerate((
            "COMERCIO PRUEBA", "Fecha 12/10/2026", "PRODUCTO ALFA 1200,00",
            "PRODUCTO BETA 800,00", "TOTAL 2000,00"))]
        detached = [detection(text, index * 30, index * 30 + 20, 0.99) for index, text in enumerate((
            "COMERCIO PRUEBA", "Fecha 12/10/2026", "PRODUCTO ALFA", "1200,00",
            "PRODUCTO BETA", "800,00", "TOTAL", "2000,00"))]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([
                    ("geometry-original", image), ("denoised-original", image)])), \
                patch.object(self.service, "ocr_image", side_effect=[(complete, complete), (detached, detached)]), \
                patch.dict(os.environ, {"OCR_TARGETED_RETRY": "false"}):
            name, lines, _, _ = self.service.run_best_ocr(image, "es")
        self.assertEqual("geometry-original", name)
        self.assertEqual(complete, lines)

    def test_failed_candidate_does_not_discard_a_successful_original(self):
        image = Image.new("RGB", (200, 100), "white")
        correct = [detection("PRODUCTO 1200,00", 10, 30, 0.95)]
        with patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([
                    ("threshold-original", image), ("original-original", image),
                ])), \
                patch.object(self.service, "ocr_image", side_effect=[RuntimeError("synthetic failure"), (correct, correct)]):
            name, lines, _, _ = self.service.run_best_ocr(image, "es")

        self.assertEqual("original-original", name)
        self.assertEqual(correct, lines)

    def test_endpoint_preserves_input_and_existing_response_contract(self):
        image = Image.new("RGB", (20, 40), (230, 230, 230))
        image.putpixel((5, 5), (195, 195, 195))
        output = BytesIO()
        image.save(output, "PNG")
        items = [detection("PRODUCTO 1200,00", 10, 30, 0.95)]

        def recognize(actual, language, debug_dir, preview_sink=None):
            self.assertEqual("es", language)
            self.assertIsNone(debug_dir)
            self.assertEqual(image.tobytes(), actual.tobytes())
            return "original-original", items, items, 42.0

        with patch.dict(self.service.OCR_CACHE, {self.service.DEFAULT_LANGUAGE: Mock()}), \
                patch.object(self.service, "run_best_ocr", side_effect=recognize), \
                patch.dict(os.environ, {"APP_OCR_DEBUG": "false"}):
            response = self.service.app.test_client().post("/ocr", data=output.getvalue(), content_type="image/png")

        self.assertEqual(200, response.status_code)
        payload = json.loads(response.data)
        self.assertEqual({"text", "lines", "detections", "variant", "score", "preview"}, set(payload))
        self.assertEqual("PRODUCTO 1200,00", payload["text"])
        self.assertEqual(items, payload["lines"])
        self.assertEqual(items, payload["detections"])

    def test_endpoint_returns_retry_text_with_consistent_boxes_lines_and_score(self):
        image = Image.new("RGB", (200, 100), "white")
        ImageDraw.Draw(image).rectangle((10, 10, 179, 29), fill="black")
        output = BytesIO()
        image.save(output, "PNG")
        weak = [detection("LECH3 1200,00", 10, 30, 0.65)]

        def recognize_crop(model, crop):
            # Simulate only the OCR model; cropping, comparison and remapping
            # are the production refinement implementation.
            mask = crop.convert("L").point(lambda pixel: 255 if pixel < 128 else 0)
            left, top, right, bottom = mask.getbbox()
            return [{
                "text": "LECHE 1200,00", "confidence": 0.96, "score": 0.96,
                "left": left, "top": top, "right": right, "bottom": bottom,
                "width": right - left, "height": bottom - top,
                "box": [[left, top], [right, top], [right, bottom], [left, bottom]],
            }]

        with patch.dict(self.service.OCR_CACHE, {self.service.DEFAULT_LANGUAGE: Mock()}), \
                patch.object(self.service, "get_ocr"), \
                patch.object(self.service, "build_variants", return_value=iter([("original-original", image)])), \
                patch.object(self.service, "ocr_image", return_value=(weak, weak)), \
                patch.object(self.service, "recognize_block", side_effect=recognize_crop) as recognize, \
                patch.dict(os.environ, {"OCR_TARGETED_RETRY": "true", "APP_OCR_DEBUG": "false"}):
            response = self.service.app.test_client().post("/ocr", data=output.getvalue(), content_type="image/png")

        self.assertEqual(200, response.status_code)
        payload = json.loads(response.data)
        self.assertEqual(2, recognize.call_count)
        self.assertEqual({"text", "lines", "detections", "variant", "score", "preview"}, set(payload))
        self.assertEqual(200, payload["preview"]["width"])
        self.assertEqual(100, payload["preview"]["height"])
        self.assertTrue(payload["preview"]["imageDataUrl"].startswith("data:image/jpeg;base64,"))
        self.assertEqual("original-original+retry", payload["variant"])
        self.assertEqual("LECHE 1200,00", payload["text"])
        self.assertEqual(payload["text"], payload["lines"][0]["text"])
        self.assertEqual(payload["text"], payload["detections"][0]["text"])
        self.assertEqual(1, len(payload["detections"]))
        self.assertEqual(weak[0]["box"], payload["detections"][0]["box"])
        self.assertEqual(0.96, payload["lines"][0]["confidence"])
        self.assertEqual(self.service.score_lines(payload["lines"]), payload["score"])

    def test_pending_models_keep_health_available_but_reject_processing(self):
        client = self.service.app.test_client()
        health = client.get("/health")
        self.assertEqual(200, health.status_code)
        self.assertFalse(health.json["ocrReady"])
        self.assertFalse(health.json["ocrBusy"])
        with patch.object(self.service, "run_best_ocr") as recognize:
            response = client.post("/ocr", data=b"not-decoded-during-startup", content_type="image/png")
        self.assertEqual(503, response.status_code)
        self.assertIn("preparando los modelos", response.json["error"])
        recognize.assert_not_called()

    def test_overlapping_requests_are_rejected_while_health_stays_responsive(self):
        output = BytesIO()
        Image.new("RGB", (20, 40), "white").save(output, "PNG")
        image_bytes = output.getvalue()
        started = threading.Event()
        finish = threading.Event()
        items = [detection("PRODUCTO 1200,00", 10, 30, 0.95)]

        def recognize(*args, **kwargs):
            started.set()
            if not finish.wait(5):
                raise AssertionError("No se liberó la extracción de prueba")
            return "original-original", items, items, 42.0

        def post_image():
            with self.service.app.test_client() as client:
                return client.post("/ocr", data=image_bytes, content_type="image/png")

        with patch.dict(self.service.OCR_CACHE, {self.service.DEFAULT_LANGUAGE: Mock()}), \
                patch.dict(os.environ, {"APP_OCR_DEBUG": "false"}), \
                patch.object(self.service, "run_best_ocr", side_effect=recognize) as engine, \
                ThreadPoolExecutor(max_workers=1) as executor:
            first = executor.submit(post_image)
            try:
                self.assertTrue(started.wait(5))
                health = self.service.app.test_client().get("/health")
                self.assertEqual(200, health.status_code)
                self.assertTrue(health.json["ocrReady"])
                self.assertTrue(health.json["ocrBusy"])
                second = post_image()
                self.assertEqual(429, second.status_code)
                self.assertIn("Esperá a que termine", second.json["error"])
                self.assertEqual(1, engine.call_count)
            finally:
                finish.set()
            self.assertEqual(200, first.result(timeout=5).status_code)
            self.assertFalse(self.service.app.test_client().get("/health").json["ocrBusy"])
            self.assertEqual(200, post_image().status_code)
            self.assertEqual(2, engine.call_count)

    def test_processing_failure_releases_engine_for_retry(self):
        output = BytesIO()
        Image.new("RGB", (20, 40), "white").save(output, "PNG")
        items = [detection("PRODUCTO 1200,00", 10, 30, 0.95)]
        with patch.dict(self.service.OCR_CACHE, {self.service.DEFAULT_LANGUAGE: Mock()}), \
                patch.dict(os.environ, {"APP_OCR_DEBUG": "false"}), \
                patch.object(self.service, "run_best_ocr", side_effect=[
                    RuntimeError("synthetic failure"), ("original-original", items, items, 42.0),
                ]):
            client = self.service.app.test_client()
            with self.assertLogs(self.service.app.logger, level="ERROR"):
                self.assertEqual(500, client.post("/ocr", data=output.getvalue()).status_code)
            self.assertFalse(client.get("/health").json["ocrBusy"])
            self.assertEqual(200, client.post("/ocr", data=output.getvalue()).status_code)


if __name__ == "__main__":
    unittest.main()
