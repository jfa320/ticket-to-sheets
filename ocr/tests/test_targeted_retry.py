import copy
import os
import sys
import unittest

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from targeted_retry import refine_detections


def detection(text, bounds=(40, 40, 160, 60), confidence=0.6):
    left, top, right, bottom = bounds
    return {"text": text, "confidence": confidence, "score": confidence,
            "left": left, "top": top, "right": right, "bottom": bottom,
            "width": right - left, "height": bottom - top,
            "box": [[left, top], [right, top], [right, bottom], [left, bottom]]}


def image_with_boxes(boxes=((40, 40, 160, 60),), size=(300, 300)):
    image = Image.new("RGB", size, "white")
    draw = ImageDraw.Draw(image)
    for left, top, right, bottom in boxes:
        draw.rectangle((left, top, right - 1, bottom - 1), fill=(60, 60, 60))
    return image


def ink_detection(region, text="1200,00", confidence=0.94):
    y, x = np.where(np.asarray(region.convert("L")) < 110)
    return detection(text, (int(x.min()), int(y.min()), int(x.max()) + 1, int(y.max()) + 1), confidence)


class TargetedRetryTest(unittest.TestCase):
    def test_consensus_corrects_price_with_confidence_gain_and_preserves_original_geometry(self):
        image = image_with_boxes()
        original = detection("1200,08")
        original["custom"] = "preserved"
        before = copy.deepcopy(original)
        calls = []

        def recognize(region):
            calls.append(region.size)
            return [ink_detection(region, "1200,00", 0.94 if len(calls) == 1 else 0.91)]

        result, summary = refine_detections(image, [original], recognize)

        self.assertEqual("1200,00", result[0]["text"])
        self.assertEqual(0.91, result[0]["confidence"])
        self.assertEqual(0.91, result[0]["score"])
        self.assertEqual(before["box"], result[0]["box"])
        for key in ("left", "right", "top", "bottom", "width", "height", "custom"):
            self.assertEqual(before[key], result[0][key])
        self.assertEqual(before, original)
        self.assertEqual(2, len(calls))
        self.assertEqual({"attemptedRegions": 1, "acceptedRegions": 1, "ocrCalls": 2, "failedCalls": 0}, summary)

    def test_consensus_ignores_case_and_repeated_whitespace_without_rewriting_punctuation(self):
        texts = iter(["  LECHE   1200,00 ", "leche\t1200,00"])
        result, summary = refine_detections(image_with_boxes(), [detection("LECH3 1200,00")],
                                            lambda region: [ink_detection(region, next(texts))])
        self.assertEqual("LECHE 1200,00", result[0]["text"])
        self.assertEqual(1, summary["acceptedRegions"])

    def test_embedded_letter_digit_errors_and_price_letter_errors_can_be_corrected(self):
        for original, improved in (("C0C4", "COCA"), ("B0N0B0N", "BONOBON"),
                                   ("LECHE 12O0,00", "LECHE 1200,00")):
            with self.subTest(original=original):
                result, summary = refine_detections(image_with_boxes(), [detection(original)],
                                                    lambda region: [ink_detection(region, improved)])
                self.assertEqual(improved, result[0]["text"])
                self.assertEqual(1, summary["acceptedRegions"])

    def test_disagreeing_digits_or_decimal_punctuation_keep_original(self):
        for pair in (("1200,00", "1700,00"), ("1200,00", "1200.00")):
            with self.subTest(pair=pair):
                texts = iter(pair)
                original = [detection("1200,08")]
                result, summary = refine_detections(image_with_boxes(), original,
                                                    lambda region: [ink_detection(region, next(texts))])
                self.assertEqual(original, result)
                self.assertEqual(0, summary["acceptedRegions"])

    def test_loss_of_words_amounts_numbers_signs_or_separators_keeps_original(self):
        pairs = (("LECHE ENTERA 1200,00", "1200,00"), ("2 LECHE 1200,00", "LECHE 1200,00"),
                 ("1200,00", "120000"), ("1200,00", "120,00"), ("-500,00", "500,00"),
                 ("-500,00", "-500,00 300,00"), ("1.200,00", "1200,00"),
                 ("COCA COLA", "COCA COL"))
        for text, fragment in pairs:
            with self.subTest(text=text, fragment=fragment):
                original = [detection(text)]
                result, summary = refine_detections(image_with_boxes(), original,
                                                    lambda region: [ink_detection(region, fragment)])
                self.assertEqual(original, result)
                self.assertEqual(0, summary["acceptedRegions"])

    def test_partial_box_is_rejected_even_with_full_text_consensus(self):
        def recognize(region):
            item = ink_detection(region, "LECHE 1200,00")
            item["left"] = item["right"] - (item["right"] - item["left"]) * 0.25
            return [item]

        original = [detection("LECH3 1200,00")]
        result, summary = refine_detections(image_with_boxes(), original, recognize)
        self.assertEqual(original, result)
        self.assertEqual(0, summary["acceptedRegions"])

    def test_box_touching_crop_boundary_is_rejected_as_potential_truncation(self):
        def recognize(region):
            item = ink_detection(region, "LECHE 1200,00")
            item["right"] = region.width
            return [item]

        original = [detection("LECH3 1200,00")]
        result, summary = refine_detections(image_with_boxes(), original, recognize)
        self.assertEqual(original, result)
        self.assertEqual(0, summary["acceptedRegions"])

    def test_neighbors_limit_crop_and_cannot_supply_replacement_text(self):
        target = (40, 40, 160, 60)
        neighbor = (162, 40, 220, 60)
        image = image_with_boxes((target,))
        ImageDraw.Draw(image).rectangle((162, 40, 219, 59), fill=(230, 20, 20))
        source = [detection("1200,08", target), detection("VECINO", neighbor, 0.96)]
        calls = []

        def recognize(region):
            calls.append(region)
            array = np.asarray(region)
            self.assertFalse(np.any((array[:, :, 0] > 150) & (array[:, :, 1] < 80)))
            return [ink_detection(region)]

        result, summary = refine_detections(image, source, recognize)
        self.assertEqual("1200,00", result[0]["text"])
        self.assertEqual(source[1], result[1])
        self.assertEqual(1, summary["acceptedRegions"])
        self.assertEqual(2, len(calls))

    def test_overlapping_original_neighbor_boxes_are_not_retried(self):
        original = [detection("LECHE", (40, 40, 160, 60)), detection("PAN", (140, 40, 230, 60), 0.96)]
        result, summary = refine_detections(image_with_boxes(), original, lambda _: self.fail("unsafe crop"))
        self.assertEqual(original, result)
        self.assertEqual(0, summary["ocrCalls"])

    def test_confidence_must_be_valid_low_original_and_high_improved_with_gain(self):
        for value in (None, "0.6", True, float("nan"), float("inf"), -0.1, 1.1, 0.8, 0.99):
            with self.subTest(value=value):
                original = [detection("1200,08", confidence=value)]
                result, summary = refine_detections(image_with_boxes(), original, lambda _: self.fail("ineligible"))
                self.assertIs(original[0], result[0])
                self.assertEqual(0, summary["ocrCalls"])
        for original_confidence, retry_confidence in ((0.6, 0.84), (0.79, 0.88), (0.6, None)):
            with self.subTest(original=original_confidence, retry=retry_confidence):
                original = [detection("1200,08", confidence=original_confidence)]
                result, summary = refine_detections(image_with_boxes(), original,
                                                    lambda region: [ink_detection(region, confidence=retry_confidence)])
                self.assertEqual(original, result)
                self.assertEqual(0, summary["acceptedRegions"])

    def test_invalid_bounds_empty_text_and_unknown_confidence_are_ignored(self):
        source = [detection("", confidence=0.5), detection("1200,00", (10, 10, 10, 30)),
                  detection("1200,00", (-1, 10, 50, 30)), detection("1200,00", (10, 10, 400, 30)),
                  detection("1200,00", (float("nan"), 10, 50, 30))]
        source.append({"text": "1200,00", "left": 10, "top": 10, "right": 50, "bottom": 30})
        result, summary = refine_detections(image_with_boxes(), source, lambda _: self.fail("invalid input"))
        self.assertEqual(source, result)
        self.assertEqual(0, summary["ocrCalls"])

    def test_scaled_coordinate_validation_and_image_size_limits(self):
        bounds = (60, 60, 900, 700)
        image = image_with_boxes((bounds,), size=(1000, 800))
        calls = []

        def recognize(region):
            calls.append(region.size)
            self.assertLessEqual(max(region.size), 1400)
            self.assertLessEqual(region.width * region.height, 1_000_000)
            self.assertGreater(region.width, bounds[2] - bounds[0])
            return [ink_detection(region)]

        result, summary = refine_detections(image, [detection("1200,08", bounds)], recognize)
        self.assertEqual("1200,00", result[0]["text"])
        self.assertEqual(1, summary["acceptedRegions"])
        self.assertEqual(2, len(calls))

    def test_absolute_region_budget_and_custom_smaller_budget_preserve_order(self):
        boxes = [(40, 20 + index * 60, 160, 40 + index * 60) for index in range(12)]
        image = image_with_boxes(boxes, size=(300, 800))
        original = [detection("1200,08", box, 0.5 + index * 0.01) for index, box in enumerate(boxes)]
        for limit, expected in ((99, 8), (2, 2), (0, 0)):
            with self.subTest(limit=limit):
                result, summary = refine_detections(image, original, lambda region: [ink_detection(region)], max_regions=limit)
                self.assertEqual(expected, summary["attemptedRegions"])
                self.assertEqual(expected * 2, summary["ocrCalls"])
                self.assertEqual(expected, summary["acceptedRegions"])
                self.assertEqual(len(original), len(result))
                self.assertEqual([item["box"] for item in original], [item["box"] for item in result])
                self.assertEqual(["1200,00"] * expected + ["1200,08"] * (12 - expected),
                                 [item["text"] for item in result])

    def test_input_pixels_and_nested_detections_are_not_mutated(self):
        image = image_with_boxes()
        original_pixels = image.tobytes()
        source = [detection("1200,08")]
        before = copy.deepcopy(source)

        def recognize(region):
            item = ink_detection(region)
            region.putpixel((0, 0), (0, 0, 0))
            return [item]

        result, _ = refine_detections(image, source, recognize)
        self.assertEqual("1200,00", result[0]["text"])
        self.assertEqual(before, source)
        self.assertEqual(original_pixels, image.tobytes())

    def test_callback_failure_empty_result_and_invalid_result_preserve_original(self):
        for bad in ([], None, [{"text": "1200,00", "confidence": 0.99}], "not detections"):
            with self.subTest(bad=bad):
                source = [detection("1200,08")]
                result, summary = refine_detections(image_with_boxes(), source, lambda _: bad)
                self.assertEqual(source, result)
                self.assertEqual(0, summary["acceptedRegions"])
                self.assertEqual(0, summary["failedCalls"])
        calls = []

        def recognize(region):
            calls.append(region)
            if len(calls) == 1:
                raise RuntimeError("OCR unavailable")
            return [ink_detection(region)]

        source = [detection("1200,08")]
        result, summary = refine_detections(image_with_boxes(), source, recognize)
        self.assertEqual(source, result)
        self.assertEqual(2, summary["ocrCalls"])
        self.assertEqual(1, summary["failedCalls"])
        self.assertEqual(0, summary["acceptedRegions"])
        self.assertEqual({"attemptedRegions", "acceptedRegions", "ocrCalls", "failedCalls"}, set(summary))


if __name__ == "__main__":
    unittest.main()
