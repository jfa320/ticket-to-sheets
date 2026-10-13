import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from search_policy import AdaptiveSearch, reliable_signature


def receipt(confidence=0.98):
    return [{"text": text, "confidence": confidence} for text in (
        "COMERCIO DE PRUEBA", "CUIT 00-00000000-0", "Fecha 12/10/2026",
        "LECHE ENTERA 1200,00", "PAN INTEGRAL 800,00", "TOTAL 2000,00")]


class SearchPolicyTest(unittest.TestCase):
    def test_confident_incomplete_or_noisy_readings_do_not_stop_search(self):
        self.assertIsNotNone(reliable_signature(receipt()))
        for rows in (receipt(0.7), receipt()[:5], receipt()[3:],
                     receipt() + [{"text": "800,00", "confidence": 0.99}],
                     [row for row in receipt() if "Fecha" not in row["text"]]):
            self.assertIsNone(reliable_signature(rows))

    def test_consensus_requires_three_distinct_full_images_and_keeps_repeated_products(self):
        search = AdaptiveSearch()
        rows = receipt()
        for _ in range(4):
            search.observe(rows, 100, {"frame": "full:0", "fingerprint": "same-image"})
        self.assertFalse(search.complete)
        search.observe(rows, 100, {"frame": "crop:0", "fingerprint": "crop"})
        self.assertFalse(search.complete)
        search.observe(rows, 100, {"frame": "geometry:0", "fingerprint": "second"})
        self.assertFalse(search.complete)
        search.observe(rows + [rows[3]], 100, {"frame": "full:0", "fingerprint": "extra-product"})
        self.assertFalse(search.complete)
        search.observe(rows, 100, {"frame": "full:0", "fingerprint": "third"})
        self.assertTrue(search.complete)

    def test_conflicting_prices_or_dates_do_not_count_as_agreement(self):
        search = AdaptiveSearch()
        for index, field in enumerate(("unchanged", "price", "date")):
            rows = receipt()
            if field == "price":
                rows[3] = dict(rows[3], text="LECHE ENTERA 1700,00")
            if field == "date":
                rows[2] = dict(rows[2], text="Fecha 11/10/2026")
            search.observe(rows, 100, {"frame": "full:0", "fingerprint": field})
        self.assertFalse(search.complete)

    def test_orientation_is_probed_before_any_threshold_and_good_rotation_is_prioritized(self):
        closed = []

        def factory(rotation):
            try:
                yield (rotation, "original")
                yield (rotation, "correction")
                yield (rotation, "threshold")
            finally:
                closed.append(rotation)

        search = AdaptiveSearch()
        candidates = search.variants(factory)
        self.assertEqual((0, "original"), next(candidates))
        search.observe([], -1, None)
        self.assertEqual((90, "original"), next(candidates))
        search.observe([], -1, None)
        self.assertEqual((270, "original"), next(candidates))
        search.observe(receipt(), 100, None)
        self.assertEqual((270, "correction"), next(candidates))
        candidates.close()
        self.assertCountEqual([0, 90, 270], closed)

    def test_weak_orientation_probes_continue_with_best_score_then_all_fallbacks(self):
        search = AdaptiveSearch()
        candidates = search.variants(lambda rotation: iter([(rotation, "original"), (rotation, "fallback")]))
        for rotation, score in ((0, 1), (90, 2), (270, 10), (180, 3)):
            self.assertEqual((rotation, "original"), next(candidates))
            search.observe([], score, None)
        self.assertEqual([(270, "fallback"), (180, "fallback"), (90, "fallback"), (0, "fallback")],
                         list(candidates))


if __name__ == "__main__":
    unittest.main()
