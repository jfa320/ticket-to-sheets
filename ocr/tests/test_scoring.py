import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from scoring import looks_like_item_line, score_lines


def line(text, confidence=0.9):
    return {"text": text, "confidence": confidence}


def positioned_line(text, top, confidence=0.9, left=0):
    return dict(line(text, confidence), top=top, bottom=top + 16, left=left, right=left + 200)


class ScoringTest(unittest.TestCase):
    def test_prefers_market_variant_that_preserves_small_delivery_date(self):
        products = [line("PRODUCTO ALFA $ 4.577,50 1x", 0.95)]
        readable = line("Entregado mié 30 de sept · 22:04 hs", 0.95)
        illegible = line("Entregado mié ?? de ???? · 22:04 hs", 0.95)
        self.assertGreater(score_lines(products + [readable]), score_lines(products + [illegible]))
        self.assertGreater(score_lines(products + [readable]), score_lines(products))

    def test_market_ui_amounts_are_not_product_evidence(self):
        for text in ("Compensación $ 1.500,00", "Te acreditamos un cupón $ 1.500,00",
                     "Entregado mié 30 de sept · 22:04 hs"):
            self.assertFalse(looks_like_item_line(text), text)

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

    def test_metadata_tokens_inside_product_words_do_not_hide_items(self):
        for text in ("ACEITE DE OLIVA 1200,00", "OLIVAS VERDES 800,00"):
            with self.subTest(text=text):
                self.assertTrue(looks_like_item_line(text))

        for text in ("IVA 21,00", "TOTAL 1200,00", "TARJETA 1200,00", "FECHA 01/01/2026"):
            with self.subTest(text=text):
                self.assertFalse(looks_like_item_line(text))

    def test_prefers_correct_oliva_over_less_confident_misspelling(self):
        accurate = [line("ACEITE DE OLIVA 1200,00", 0.95)]
        misspelled = [line("ACEITE DE OLNA 1200,00", 0.8)]

        self.assertGreater(score_lines(accurate), score_lines(misspelled))

    def test_many_uncertain_price_fragments_do_not_outscore_confident_receipt(self):
        accurate = [line("PRODUCTO ALFA 1200,00", 0.95), line("TOTAL 1200,00", 0.95)]
        for count in (30, 45, 100, 1000):
            with self.subTest(fragment_count=count):
                noisy = [line(f"FRAGMENTO {index} 1200,00", 0.6) for index in range(count)]

                self.assertGreater(score_lines(accurate), score_lines(noisy))

    def test_confident_additional_products_still_improve_score(self):
        short_receipt = [line("PRODUCTO ALFA 1200,00", 0.95)]
        complete_receipt = short_receipt + [line("PRODUCTO BETA 800,00", 0.95)]

        self.assertGreater(score_lines(complete_receipt), score_lines(short_receipt))

    def test_complete_confident_receipt_outscores_cropped_version(self):
        products = [line(f"PRODUCTO {index} 1200,00", 0.95) for index in range(100)]
        total = line("TOTAL 120000,00", 0.95)

        self.assertGreater(score_lines(products + [total]), score_lines(products[:2] + [total]))

    def test_confident_evidence_is_counted_even_after_many_uncertain_rows(self):
        noisy = [line(f"FRAGMENTO {index} 1200,00", 0.6) for index in range(100)]
        accurate = [line("PRODUCTO ALFA 1200,00", 0.95), line("TOTAL 1200,00", 0.95)]

        self.assertGreater(score_lines(noisy + accurate), score_lines(noisy))
        self.assertAlmostEqual(score_lines(noisy + accurate), score_lines(accurate + noisy))

    def test_overlapping_duplicate_text_does_not_inflate_score(self):
        original = positioned_line("PRODUCTO ALFA 1200,00", 20, 0.95)
        duplicate = positioned_line("  producto   alfa 1200,00 ", 21, 0.8, left=1)

        self.assertEqual(score_lines([original]), score_lines([duplicate, original]))

    def test_repeated_products_in_distinct_positions_keep_their_evidence(self):
        first = positioned_line("PRODUCTO ALFA 1200,00", 20)
        repeated = positioned_line("PRODUCTO ALFA 1200,00", 50)

        self.assertGreater(score_lines([first, repeated]), score_lines([first]))

    def test_repeated_text_without_geometry_is_not_assumed_duplicate(self):
        product = line("PRODUCTO ALFA 1200,00")

        self.assertGreater(score_lines([product, product]), score_lines([product]))

    def test_more_confident_variant_wins_when_text_and_geometry_match(self):
        uncertain = [positioned_line("PRODUCTO ALFA 1200,00", 20, 0.55)]
        confident = [positioned_line("PRODUCTO ALFA 1200,00", 20, 0.95)]

        self.assertGreater(score_lines(confident), score_lines(uncertain))


if __name__ == "__main__":
    unittest.main()
