import os
import sys
import unittest

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from ocr_regions import recognize_regions


def detection(text, left, top, right, bottom, confidence=0.9):
    return {
        "text": text,
        "confidence": confidence,
        "score": confidence,
        "box": [[left, top], [right, top], [right, bottom], [left, bottom]],
        "top": float(top),
        "left": float(left),
        "right": float(right),
        "bottom": float(bottom),
        "width": float(right - left),
        "height": float(bottom - top),
    }


class OcrRegionsTest(unittest.TestCase):
    def test_single_region_preserves_image_and_callback_result(self):
        image = Image.new("RGB", (300, 1400), "white")
        expected = [detection("LECHE", 10, 20, 90, 40)]

        def recognize(region):
            self.assertIs(image, region)
            return expected

        self.assertIs(expected, recognize_regions(image, recognize))

    def test_long_image_preserves_original_pixels_and_covers_every_row(self):
        image = Image.new("L", (8, 3000))
        for y in range(image.height):
            for x in range(image.width):
                image.putpixel((x, y), (y * 13 + x * 43) % 256)
        calls = []
        starts = [0, 1240, 2480]

        def recognize(region):
            start = starts[len(calls)]
            calls.append(region.size)
            self.assertEqual(image.crop((0, start, 8, min(start + 1400, 3000))).tobytes(), region.tobytes())
            return []

        self.assertEqual([], recognize_regions(image, recognize))
        self.assertEqual([(8, 1400), (8, 1400), (8, 520)], calls)
        covered_rows = set()
        for start, (_, height) in zip(starts, calls):
            covered_rows.update(range(start, start + height))
        self.assertEqual(set(range(3000)), covered_rows)

    def test_grid_translates_boxes_and_keeps_callback_detections_unchanged(self):
        image = Image.new("RGB", (2500, 2500), "white")
        local = detection("PRODUCTO", 10, 15, 30, 25)
        calls = []

        def recognize(region):
            calls.append(region.size)
            return [local]

        result = recognize_regions(image, recognize)

        self.assertEqual([(1400, 1400), (1260, 1400), (1400, 1260), (1260, 1260)], calls)
        self.assertEqual([(10, 15), (1250, 15), (10, 1255), (1250, 1255)], [
            (item["left"], item["top"]) for item in result
        ])
        self.assertEqual([[1250, 1255], [1270, 1255], [1270, 1265], [1250, 1265]], result[-1]["box"])
        self.assertEqual(1270, result[-1]["right"])
        self.assertEqual(1265, result[-1]["bottom"])
        self.assertEqual(20, result[-1]["width"])
        self.assertEqual(10, result[-1]["height"])
        self.assertEqual(detection("PRODUCTO", 10, 15, 30, 25), local)

    def test_duplicate_with_divergent_text_uses_more_confident_complete_box(self):
        responses = iter([
            [detection("LECH3", 10, 1300, 100, 1320, 0.7)],
            [detection("LECHE", 11, 61, 102, 81, 0.96)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(1, len(result))
        self.assertEqual("LECHE", result[0]["text"])
        self.assertEqual(1301, result[0]["top"])

    def test_complete_box_wins_over_confident_fragment_at_vertical_seam(self):
        responses = iter([
            [detection("LEC", 10, 1388, 70, 1400, 0.99)],
            [detection("LECHE 1000,00", 10, 148, 140, 173, 0.8)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(1, len(result))
        self.assertEqual("LECHE 1000,00", result[0]["text"])
        self.assertEqual(1413, result[0]["bottom"])

    def test_complete_box_wins_when_fragment_arrives_from_next_region(self):
        responses = iter([
            [detection("LECHE 1000,00", 10, 1230, 140, 1260, 0.8)],
            [detection("LECH", 10, 0, 90, 20, 0.99)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(["LECHE 1000,00"], [item["text"] for item in result])

    def test_horizontal_seam_keeps_complete_box(self):
        responses = iter([
            [detection("PROD", 1380, 10, 1400, 30, 0.99)],
            [detection("PRODUCTO", 140, 10, 190, 30, 0.8)],
        ])

        result = recognize_regions(Image.new("L", (1800, 200)), lambda _: next(responses))

        self.assertEqual(["PRODUCTO"], [item["text"] for item in result])
        self.assertEqual(1430, result[0]["right"])

    def test_repeated_products_at_distinct_locations_are_preserved(self):
        responses = iter([
            [detection("LECHE", 10, 1200, 100, 1220), detection("LECHE", 10, 1300, 100, 1320)],
            [detection("LECHE", 10, 60, 100, 80), detection("LECHE", 10, 100, 100, 120)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual([1200, 1300, 1340], [item["top"] for item in result])
        self.assertEqual(["LECHE"] * 3, [item["text"] for item in result])

    def test_separate_nearby_rows_and_boxes_from_same_region_are_preserved(self):
        responses = iter([
            [detection("A", 10, 1300, 100, 1320), detection("B", 15, 1300, 105, 1320)],
            [detection("C", 10, 78, 100, 98)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(["A", "B", "C"], [item["text"] for item in result])

    def test_empty_regions_do_not_discard_detections_in_other_regions(self):
        responses = iter([[], [detection("PAN", 10, 400, 50, 420)]])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(1640, result[0]["top"])

    def test_small_complete_box_inside_broad_box_is_not_discarded(self):
        responses = iter([
            [detection("PRODUCTO", 10, 1300, 190, 1320)],
            [detection("1000,00", 150, 60, 190, 80)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(["PRODUCTO", "1000,00"], [item["text"] for item in result])

    def test_description_at_crop_seam_is_not_erased_by_contained_price(self):
        responses = iter([
            [detection("PRODUCTO ALFA", 10, 1388, 190, 1400)],
            [detection("1200,00", 150, 150, 190, 170)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(["PRODUCTO ALFA", "1200,00"], [item["text"] for item in result])
        self.assertEqual([1388, 1390], [item["top"] for item in result])

    def test_contained_fragment_with_ocr_typo_still_uses_complete_text(self):
        responses = iter([
            [detection("LECH3", 10, 1388, 70, 1400, 0.99)],
            [detection("LECHE 1000,00", 10, 148, 140, 173, 0.8)],
        ])

        result = recognize_regions(Image.new("L", (200, 1800)), lambda _: next(responses))

        self.assertEqual(["LECHE 1000,00"], [item["text"] for item in result])

    def test_contained_suffix_fragment_still_uses_complete_text(self):
        responses = iter([
            [detection("ACEITE DE OLIVA", 1220, 10, 1340, 30, 0.8)],
            [detection("DE OLIVA", 0, 10, 50, 30, 0.99)],
        ])

        result = recognize_regions(Image.new("L", (1800, 200)), lambda _: next(responses))

        self.assertEqual(["ACEITE DE OLIVA"], [item["text"] for item in result])

    def test_grid_corner_duplicates_are_merged_once(self):
        responses = iter([
            [detection("PAN", 1300, 1300, 1350, 1320)],
            [detection("PAN", 60, 1300, 110, 1320)],
            [detection("PAN", 1300, 60, 1350, 80)],
            [detection("PAN", 60, 60, 110, 80)],
        ])

        result = recognize_regions(Image.new("L", (1800, 1800)), lambda _: next(responses))

        self.assertEqual(1, len(result))
        self.assertEqual(detection("PAN", 1300, 1300, 1350, 1320), result[0])

    def test_overlap_is_capped_and_zero_overlap_covers_last_pixel(self):
        calls = []
        image = Image.new("L", (200, 2801))
        recognize_regions(image, lambda region: calls.append(region.size) or [], overlap=999)
        self.assertEqual([(200, 1400), (200, 1400), (200, 321)], calls)
        calls.clear()
        recognize_regions(image, lambda region: calls.append(region.size) or [], overlap=0)
        self.assertEqual([(200, 1400), (200, 1400), (200, 1)], calls)

    def test_invalid_region_settings_fail_before_recognition(self):
        image = Image.new("L", (200, 200))
        for settings in ({"max_side": 0}, {"max_side": 1.5}, {"overlap": -1}, {"overlap": 1.5}):
            with self.subTest(settings=settings):
                with self.assertRaises(ValueError):
                    recognize_regions(image, lambda _: self.fail("recognizer should not be called"), **settings)


if __name__ == "__main__":
    unittest.main()
