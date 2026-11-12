import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from missing_descriptions import recover_missing_descriptions


def detection(text, top, bottom, left=30, right=150):
    return dict(text=text, left=left, top=top, right=right, bottom=bottom)


class MissingDescriptionsTest(unittest.TestCase):
    def setUp(self):
        self.image = Image.new("RGB", (400, 300), "white")
        ImageDraw.Draw(self.image).text((32, 110), "PRODUCTO DE PRUEBA", fill="black")
        self.detections = [detection("Cliente Consumidor Final", 50, 70),
                           detection("1$900,00", 140, 160), detection("$900,00", 155, 175, 300, 370)]

    def test_recovers_only_after_two_agreeing_reads_and_preserves_frame_and_review(self):
        recognize = Mock(return_value=("PRODUCTO DE PRUEBA", .94))
        updated, stats = recover_missing_descriptions(self.image, self.detections, recognize)
        self.assertEqual(2, recognize.call_count)
        self.assertEqual(1, stats["acceptedRegions"])
        self.assertEqual(self.detections, updated[:-1])
        self.assertEqual("PRODUCTO DE PRUEBA", updated[-1]["text"])
        self.assertTrue(updated[-1]["reviewRequired"])
        self.assertEqual(111, updated[-1]["top"])
        self.assertEqual(150, updated[-1]["bottom"])
        self.assertEqual([[13, 111], [387, 111], [387, 150], [13, 150]], updated[-1]["box"])

    def test_never_rereads_an_existing_description(self):
        recognize = Mock()
        items = self.detections + [detection("PRODUCTO DE PRUEBA", 105, 125)]
        updated, stats = recover_missing_descriptions(self.image, items, recognize)
        self.assertEqual(items, updated)
        recognize.assert_not_called()

    def test_small_spelling_disagreements_keep_the_best_actual_reading_for_review(self):
        recognize = Mock(side_effect=[("PRODUCTO DE PRUEBA", .94), ("PRODUCTO DE PRUE8A", .93)])
        updated, stats = recover_missing_descriptions(self.image, self.detections, recognize)
        self.assertEqual(1, stats["acceptedRegions"])
        self.assertEqual("PRODUCTO DE PRUEBA", updated[-1]["text"])
        self.assertTrue(updated[-1]["reviewRequired"])

    def test_rejects_conflicts_low_confidence_metadata_prices_and_invalid_reads(self):
        for reading in [("OTRO PRODUCTO", .94), ("PRODUCTO DE PRUEBA", .7),
                        ("Cliente Consumidor Final", .99), ("PRODUCTO $900,00", .99),
                        ("ART0101", .99), ("PRODUCTO DE PRUEBA", float("nan")), None]:
            with self.subTest(reading=reading):
                recognize = Mock(side_effect=[("PRODUCTO DE PRUEBA", .94), reading])
                updated, stats = recover_missing_descriptions(self.image, self.detections, recognize)
                self.assertEqual(self.detections, updated)
                self.assertEqual(0, stats["acceptedRegions"])

    def test_does_not_read_blank_areas_or_unanchored_totals(self):
        for image, items in [(Image.new("RGB", self.image.size, "white"), self.detections),
                             (self.image, [detection("TOTAL $900,00", 140, 160)]),
                             (self.image, [detection("1$900,00", 140, 160)])]:
            recognize = Mock()
            updated, _ = recover_missing_descriptions(image, items, recognize)
            self.assertEqual(items, updated)
            recognize.assert_not_called()

    def test_respects_budget_between_reads_and_keeps_input_unchanged(self):
        recognize = Mock(return_value=("PRODUCTO DE PRUEBA", .94))
        should_continue = lambda: recognize.call_count < 1
        updated, stats = recover_missing_descriptions(self.image, self.detections, recognize,
                                                      should_continue=should_continue)
        self.assertEqual(self.detections, updated)
        self.assertEqual(1, recognize.call_count)

    def test_uses_original_detail_pixels_without_changing_output_coordinates(self):
        recognize = Mock(return_value=("PRODUCTO DE PRUEBA", .94))
        updated, _ = recover_missing_descriptions(self.image, self.detections, recognize,
                                                  detail_image=self.image.resize((800, 600)))
        self.assertEqual(111, updated[-1]["top"])
        self.assertLessEqual(recognize.call_args.args[0].width, 1432)


if __name__ == "__main__":
    unittest.main()
