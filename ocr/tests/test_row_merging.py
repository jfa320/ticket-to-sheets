import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from service import merge_boxes_into_rows


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


if __name__ == "__main__":
    unittest.main()
