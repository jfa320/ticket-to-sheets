import os
import sys
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from illumination import illumination_variants
from preprocess import load_cv2


def text_mask(size):
    glyphs = Image.new("L", (180, 20), 0)
    ImageDraw.Draw(glyphs).text((2, 2), "PRODUCTO 1234,56", fill=255)
    glyphs = glyphs.point(lambda value: 255 if value > 128 else 0)
    glyphs = glyphs.resize((360, 40), Image.Resampling.NEAREST)
    mask = Image.new("L", size, 0)
    for y in (30, 110, 190):
        mask.paste(glyphs, (30, y))
    return np.asarray(mask) > 0


def receipt_fixture(shadow=True, contrast=14, noise=0):
    width, height = 640, 256
    mask = text_mask((width, height))
    if shadow:
        horizontal = np.linspace(230, 105, width, dtype=np.float32)
        vertical = np.linspace(0, 8, height, dtype=np.float32)
        background = horizontal[None, :] + vertical[:, None]
    else:
        background = np.full((height, width), 225, dtype=np.float32)
    if noise:
        generator = np.random.default_rng(2026)
        background = background + generator.normal(0, noise, background.shape)
    array = np.clip(background - mask * contrast, 0, 255).astype(np.uint8)
    return Image.fromarray(array), mask


def local_contrast(image, mask, cv2):
    array = np.asarray(image).astype(np.float32)
    nearby_paper = cv2.dilate(array, np.ones((9, 9), dtype=np.uint8))
    return float((nearby_paper - array)[mask].mean())


class IlluminationFallbackTest(unittest.TestCase):
    def test_missing_opencv_produces_no_candidates_and_preserves_input(self):
        image, _ = receipt_fixture()
        original = image.tobytes()

        with patch("illumination.load_cv2", return_value=None):
            self.assertEqual([], list(illumination_variants(image)))

        self.assertEqual(original, image.tobytes())

    def test_small_images_skip_opencv_safely(self):
        for size in ((1, 200), (15, 15), (0, 20)):
            with self.subTest(size=size), patch("illumination.load_cv2") as cv2:
                self.assertEqual([], list(illumination_variants(Image.new("L", size))))
                cv2.assert_not_called()


class IlluminationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cv2 = load_cv2()
        if cls.cv2 is None:
            raise unittest.SkipTest("OpenCV is not installed")

    def test_candidates_keep_original_pixels_dimensions_and_gray_values(self):
        gray, _ = receipt_fixture()
        image = gray.convert("RGB")
        original = image.tobytes()

        variants = list(illumination_variants(image))

        self.assertEqual(["local-contrast", "shadow-normalized"], [name for name, _ in variants])
        self.assertEqual(original, image.tobytes())
        for name, candidate in variants:
            with self.subTest(candidate=name):
                self.assertEqual(image.size, candidate.size)
                self.assertEqual("L", candidate.mode)
                self.assertGreater(len(np.unique(np.asarray(candidate))), 2)

    def test_flat_images_do_not_generate_noise_or_artificial_text(self):
        for level in (0, 80, 225, 255):
            with self.subTest(level=level):
                self.assertEqual([], list(illumination_variants(Image.new("L", (80, 80), level))))

    def test_local_contrast_increases_visibility_of_faint_printing(self):
        image, mask = receipt_fixture(shadow=False, contrast=12)
        enhanced = dict(illumination_variants(image))["local-contrast"]

        self.assertGreater(local_contrast(enhanced, mask, self.cv2), local_contrast(image, mask, self.cv2))

    def test_background_compensation_reduces_shadow_without_erasing_glyphs(self):
        image, mask = receipt_fixture(shadow=True, contrast=14)
        normalized = dict(illumination_variants(image))["shadow-normalized"]
        original_array = np.asarray(image).astype(np.float32)
        normalized_array = np.asarray(normalized).astype(np.float32)

        # Use a text-free strip to evaluate illumination, independently of OCR.
        self.assertLess(float(normalized_array[:, 500:620].std()),
                        float(original_array[:, 500:620].std()) * 0.25)
        self.assertGreater(local_contrast(normalized, mask, self.cv2), local_contrast(image, mask, self.cv2))
        nearby_paper = self.cv2.dilate(normalized_array, np.ones((9, 9), dtype=np.uint8))
        visible_strokes = nearby_paper - normalized_array > 7
        self.assertGreater(float(visible_strokes[mask].mean()), 0.98)

    def test_faint_printing_and_small_price_strokes_keep_their_geometry(self):
        image, mask = receipt_fixture(shadow=True, contrast=8)
        for name, candidate in illumination_variants(image):
            with self.subTest(candidate=name):
                array = np.asarray(candidate).astype(np.float32)
                nearby_paper = self.cv2.dilate(array, np.ones((9, 9), dtype=np.uint8))
                recovered = nearby_paper - array > 4
                self.assertGreater(float(recovered[mask].mean()), 0.98)
                # Background variation should not create widespread false strokes.
                self.assertLess(float(recovered[:, 500:620].mean()), 0.02)

    def test_background_compensation_bounds_noise_gain(self):
        image, _ = receipt_fixture(shadow=False, contrast=12, noise=1)
        normalized = dict(illumination_variants(image))["shadow-normalized"]

        original_noise = float(np.asarray(image)[:, 500:620].std())
        normalized_noise = float(np.asarray(normalized)[:, 500:620].std())
        self.assertLess(normalized_noise, original_noise * 2.5)


if __name__ == "__main__":
    unittest.main()
