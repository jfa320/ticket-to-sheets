import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from scoring import score_lines


def line(text, confidence=0.9):
    return {"text": text, "confidence": confidence}


class ScoringTest(unittest.TestCase):
    def test_scores_same_layout_without_brand_bias(self):
        first = [
            line("COMERCIO DE PRUEBA"),
            line("PRODUCTO ALFA 1200,00"),
            line("ARTICULO BETA 800,00"),
            line("TOTAL 2000,00"),
        ]
        second = [
            line("COMERCIO DE PRUEBA"),
            line("MARCA FAMOSA 1200,00"),
            line("OTRA MARCA 800,00"),
            line("TOTAL 2000,00"),
        ]

        self.assertEqual(score_lines(first), score_lines(second))

    def test_prefers_confident_structured_receipt(self):
        structured = [
            line("FECHA 01/01/2026", 0.95),
            line("PRODUCTO ALFA 1200,00", 0.95),
            line("ARTICULO BETA 800,00", 0.95),
            line("TOTAL 2000,00", 0.95),
        ]
        noisy = [
            line("FECHA 01/01/2026", 0.25),
            line("PRODUCTO", 0.25),
            line("ALFA", 0.25),
            line("TOTAL", 0.25),
        ]

        self.assertGreater(score_lines(structured), score_lines(noisy))

    def test_empty_result_is_worst_score(self):
        self.assertEqual(-1, score_lines([]))


if __name__ == "__main__":
    unittest.main()
