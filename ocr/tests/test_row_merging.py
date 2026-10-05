import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from receipt_layout import merge_boxes_into_rows


def detection(text, top, left, right, bottom, confidence=0.9):
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


class RowMergingTest(unittest.TestCase):
    def test_keeps_close_ticket_rows_separate(self):
        detections = [
            detection("LA UNICA BOLEA*1OML", 555, 351, 628, 591),
            detection("2000,00", 561, 803, 904, 592),
            detection("ELEGANTE PANLE*100UN", 581, 349, 630, 621),
            detection("1100,00", 589, 805, 904, 621),
        ]

        lines = merge_boxes_into_rows(detections)

        self.assertEqual([
            "LA UNICA BOLEA*1OML 2000,00",
            "ELEGANTE PANLE*100UN 1100,00",
        ], [line["text"] for line in lines])

    def test_dense_rows_stay_separate_at_different_image_scales(self):
        for scale in (0.25, 0.5, 1, 2, 4):
            with self.subTest(scale=scale):
                boxes = [
                    detection("PRODUCTO ALFA", 0, 0, 100, 8),
                    detection("100,00", 1, 150, 190, 8),
                    detection("PRODUCTO BETA", 12, 0, 100, 20),
                    detection("200,00", 13, 150, 190, 20),
                ]
                scaled = [
                    detection(box["text"], box["top"] * scale, box["left"] * scale,
                              box["right"] * scale, box["bottom"] * scale)
                    for box in boxes
                ]

                lines = merge_boxes_into_rows(scaled)

                self.assertEqual(["PRODUCTO ALFA 100,00", "PRODUCTO BETA 200,00"],
                                 [line["text"] for line in lines])
                self.assertEqual([[0, 1], [2, 3]], [line["detectionIndexes"] for line in lines])

    def test_aligns_description_and_smaller_price_on_same_baseline(self):
        for scale in (0.25, 1, 3):
            with self.subTest(scale=scale):
                boxes = [
                    detection("PRODUCTO ALFA", 100 * scale, 0, 100 * scale, 140 * scale),
                    detection("1200,00", 124 * scale, 150 * scale, 200 * scale, 140 * scale),
                ]

                self.assertEqual(["PRODUCTO ALFA 1200,00"],
                                 [line["text"] for line in merge_boxes_into_rows(boxes)])

    def test_assigns_price_to_closest_matching_row(self):
        boxes = [
            detection("PRODUCTO ALFA", 0, 0, 100, 20),
            detection("PRODUCTO BETA", 12, 0, 100, 32),
            detection("200,00", 13, 150, 190, 21),
        ]

        self.assertEqual(["PRODUCTO ALFA", "PRODUCTO BETA 200,00"],
                         [line["text"] for line in merge_boxes_into_rows(boxes)])

    def test_empty_detections_produce_no_rows(self):
        self.assertEqual([], merge_boxes_into_rows([]))


if __name__ == "__main__":
    unittest.main()
