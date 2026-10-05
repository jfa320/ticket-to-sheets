import gc
import os
import sys
import unittest
import weakref
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from image_variants import MAX_PIXELS, MAX_SHORT_SIDE, build_variants, crop_receipt_region, limit_image_size


class ImageVariantsTest(unittest.TestCase):
    def test_geometry_is_reused_across_orientations_and_originals_are_preserved(self):
        image = Image.new("RGB", (80, 120), (230, 240, 220))
        corrected = Image.new("RGB", (60, 100), "white")
        corrected.putpixel((10, 20), (50, 70, 90))

        with patch("image_variants.correct_document_geometry", return_value=corrected) as geometry, \
                patch("image_variants.illumination_variants", return_value=iter(())), \
                patch("image_variants.remove_physical_lines", return_value=None):
            candidates = dict(build_variants(image))

        geometry.assert_called_once()
        self.assertEqual(image.tobytes(), candidates["original-original"].tobytes())
        for rotation in (0, 90, 270, 180):
            suffix = "original" if rotation == 0 else f"rot{rotation}"
            expected = corrected if rotation == 0 else corrected.rotate(rotation, expand=True)
            self.assertEqual(expected.size, candidates[f"geometry-{suffix}"].size)
            self.assertEqual(expected.tobytes(), candidates[f"geometry-{suffix}"].tobytes())

    def test_illumination_is_compared_on_both_full_original_and_corrected_document(self):
        image = Image.new("RGB", (80, 120), "white")
        corrected = Image.new("RGB", (60, 100), "white")

        def illumination(candidate):
            yield "local-contrast", candidate.convert("L")
            yield "shadow-normalized", candidate.convert("L")

        with patch("image_variants.correct_document_geometry", return_value=corrected), \
                patch("image_variants.illumination_variants", side_effect=illumination), \
                patch("image_variants.remove_physical_lines", return_value=None):
            candidates = dict(build_variants(image))

        self.assertEqual((80, 120), candidates["local-contrast-original"].size)
        self.assertEqual((60, 100), candidates["shadow-normalized-geometry-original"].size)
        self.assertEqual((100, 60), candidates["local-contrast-geometry-rot90"].size)

    def test_illumination_receives_text_in_shadow_even_when_brightness_crop_loses_it(self):
        image = Image.new("RGB", (640, 1000), "white")
        draw = ImageDraw.Draw(image)
        draw.rectangle((0, 500, 639, 999), fill=(90, 90, 90))
        draw.text((20, 900), "PRODUCTO 1200,00", fill=(60, 60, 60))

        def illumination(candidate):
            yield "shadow-normalized", candidate.convert("L")

        with patch("image_variants.correct_document_geometry", return_value=None), \
                patch("image_variants.illumination_variants", side_effect=illumination), \
                patch("image_variants.remove_physical_lines", return_value=None):
            candidates = dict(build_variants(image))

        self.assertLess(candidates["cropped-original"].height, 900)
        self.assertEqual(image.size, candidates["shadow-normalized-original"].size)
        expected_ink = np.asarray(image.convert("L"))[900:920, 20:200]
        self.assertLess(expected_ink.min(), 90)
        np.testing.assert_array_equal(expected_ink,
                                      np.asarray(candidates["shadow-normalized-original"])[900:920, 20:200])

    def test_optional_correction_failure_keeps_original_candidates(self):
        image = Image.new("RGB", (80, 120), "white")
        with patch("image_variants.correct_document_geometry", side_effect=RuntimeError("synthetic geometry failure")), \
                patch("image_variants.illumination_variants", side_effect=RuntimeError("synthetic illumination failure")), \
                patch("image_variants.remove_physical_lines", return_value=None), \
                self.assertLogs("image_variants", level="WARNING"):
            names = [name for name, _ in build_variants(image)]

        self.assertIn("original-original", names)
        self.assertIn("original-rot180", names)
        self.assertFalse(any(name.startswith("geometry-") for name in names))

    def test_corrections_can_be_disabled_for_baseline_evaluation(self):
        image = Image.new("RGB", (80, 120), "white")
        with patch("image_variants.correct_document_geometry") as geometry, \
                patch("image_variants.illumination_variants") as illumination, \
                patch("image_variants.remove_physical_lines", return_value=None):
            names = [name for name, _ in build_variants(image, document_corrections=False)]

        geometry.assert_not_called()
        illumination.assert_not_called()
        self.assertIn("original-original", names)
        self.assertIn("enhanced-rot180", names)

    def test_unprocessed_candidate_preserves_color_and_faint_print_in_every_orientation(self):
        image = Image.new("RGB", (80, 120), (230, 240, 220))
        ImageDraw.Draw(image).text((8, 25), "123,45", fill=(219, 229, 209))

        with patch("image_variants.remove_physical_lines", return_value=None):
            originals = {
                name: candidate for name, candidate in build_variants(image)
                if name.startswith("original-")
            }

        self.assertEqual(4, len(originals))
        for rotation in (0, 90, 270, 180):
            suffix = "original" if rotation == 0 else f"rot{rotation}"
            expected = image if rotation == 0 else image.rotate(rotation, expand=True)
            actual = originals[f"original-{suffix}"]
            self.assertEqual("RGB", actual.mode)
            np.testing.assert_array_equal(np.asarray(expected), np.asarray(actual))

    def test_long_receipt_retains_its_original_detail(self):
        image = Image.new("RGB", (400, 4000), "white")
        draw = ImageDraw.Draw(image)
        draw.text((15, 3800), "PRODUCTO 1200,00", fill="black")

        limited = limit_image_size(image)
        name, first_candidate = next(build_variants(image))

        self.assertIs(image, limited)
        self.assertEqual("original-original", name)
        self.assertEqual((400, 4000), first_candidate.size)
        np.testing.assert_array_equal(np.asarray(image), np.asarray(first_candidate))

    def test_generator_does_not_prepare_crops_or_rotations_before_they_are_requested(self):
        image = Image.new("RGB", (80, 120), "white")
        with patch("image_variants.crop_receipt_region", side_effect=lambda candidate: candidate) as crop, \
                patch("image_variants.remove_physical_lines", return_value=None) as remove_lines, \
                patch.object(image, "rotate", wraps=image.rotate) as rotate:
            variants = build_variants(image)
            self.assertIs(iter(variants), variants)
            crop.assert_not_called()
            rotate.assert_not_called()

            self.assertEqual("original-original", next(variants)[0])
            crop.assert_not_called()
            remove_lines.assert_not_called()
            self.assertEqual("cropped-original", next(variants)[0])
            self.assertEqual(1, crop.call_count)
            self.assertEqual("gray-original", next(variants)[0])
            remove_lines.assert_not_called()

            for name, _ in variants:
                if name == "original-rot90":
                    break
            self.assertEqual(1, crop.call_count)
            self.assertEqual(1, rotate.call_count)
            variants.close()

    def test_generator_does_not_retain_every_produced_variant(self):
        image = Image.new("RGB", (80, 120), "white")
        with patch("image_variants.remove_physical_lines", return_value=None):
            variants = build_variants(image)
            for name, candidate in variants:
                if name == "threshold-original":
                    reference = weakref.ref(candidate)
                    break
            del candidate
            self.assertEqual("denoised-original", next(variants)[0])
            gc.collect()
            self.assertIsNone(reference())
            variants.close()

    def test_large_images_respect_short_side_and_pixel_budget(self):
        for size in ((3000, 5000), (5000, 3000), (2500, 2500)):
            image = Image.new("L", size, 255)
            limited = limit_image_size(image)

            self.assertLessEqual(min(limited.size), MAX_SHORT_SIDE)
            self.assertLessEqual(limited.width * limited.height, MAX_PIXELS)
            self.assertAlmostEqual(image.width / image.height, limited.width / limited.height, delta=0.002)

    def test_all_generated_variants_respect_the_pixel_budget(self):
        image = Image.new("RGB", (1500, 2800), "white")
        with patch("image_variants.remove_physical_lines", return_value=None):
            for name, candidate in build_variants(image):
                with self.subTest(name=name):
                    self.assertLessEqual(min(candidate.size), MAX_SHORT_SIDE)
                    self.assertLessEqual(candidate.width * candidate.height, MAX_PIXELS)

    def test_enhancement_preserves_detail_without_allocating_an_unbounded_resize(self):
        image = Image.new("RGB", (400, 4000), "white")
        with patch("image_variants.remove_physical_lines", return_value=None):
            for name, candidate in build_variants(image):
                if name == "enhanced-original":
                    self.assertGreater(candidate.width, 400)
                    self.assertGreater(candidate.height, 4000)
                    self.assertLessEqual(candidate.width * candidate.height, MAX_PIXELS)
                    break

    def test_preserves_existing_variant_names_and_optional_mask_debug(self):
        image = Image.new("RGB", (80, 120), "white")
        debug_images = []

        def without_lines(gray):
            return gray.copy(), Image.new("L", gray.size, 0)

        with patch("image_variants.remove_physical_lines", side_effect=without_lines):
            names = [name for name, _ in build_variants(
                image, "test-debug", lambda directory, name, mask: debug_images.append((directory, name, mask.size))
            )]

        for suffix in ("original", "rot90", "rot270", "rot180"):
            for prefix in ("original", "cropped", "gray", "without-lines", "enhanced", "threshold",
                           "denoised", "light-threshold", "small", "small-gray"):
                self.assertIn(f"{prefix}-{suffix}", names)
        self.assertEqual(4, len(debug_images))
        self.assertEqual(("test-debug", "variants/without-lines-original-mask.png", (80, 120)), debug_images[0])

    def test_crop_falls_back_when_receipt_region_is_too_small(self):
        image = Image.new("RGB", (200, 200), "black")
        ImageDraw.Draw(image).rectangle((90, 80, 110, 120), fill="white")

        self.assertIs(image, crop_receipt_region(image))

    def test_crop_falls_back_when_no_clear_paper_region_exists(self):
        image = Image.new("RGB", (200, 200), (100, 100, 100))

        self.assertIs(image, crop_receipt_region(image))

    def test_crop_retains_a_receipt_with_a_margin(self):
        image = Image.new("RGB", (400, 600), "black")
        ImageDraw.Draw(image).rectangle((90, 90, 310, 510), fill="white")

        cropped = crop_receipt_region(image)

        self.assertEqual((270, 470), cropped.size)


if __name__ == "__main__":
    unittest.main()
