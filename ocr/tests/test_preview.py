import base64
from io import BytesIO
import os
import sys
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from preview import MAX_BYTES, MAX_PIXELS, MAX_SIDE, build_preview


class PreviewTest(unittest.TestCase):
    def test_preserves_ocr_coordinates_when_preview_pixels_are_smaller(self):
        original = Image.new("RGB", (800, 5000), (110, 150, 190))
        preview = build_preview(original)
        self.assertEqual((800, 5000), (preview["width"], preview["height"]))
        encoded = base64.b64decode(preview["imageDataUrl"].split(",", 1)[1])
        self.assertLessEqual(len(encoded), MAX_BYTES)
        with Image.open(BytesIO(encoded)) as image:
            self.assertEqual("JPEG", image.format)
            self.assertLessEqual(image.width * image.height, MAX_PIXELS)
            self.assertLessEqual(max(image.size), MAX_SIDE)
            self.assertAlmostEqual(800 / 5000, image.width / image.height, delta=0.002)
        self.assertEqual((800, 5000), original.size)
        self.assertEqual((110, 150, 190), original.getpixel((0, 0)))

    def test_grayscale_preview_is_a_self_contained_jpeg(self):
        preview = build_preview(Image.new("L", (100, 200), 225))
        self.assertTrue(preview["imageDataUrl"].startswith("data:image/jpeg;base64,"))
        with Image.open(BytesIO(base64.b64decode(preview["imageDataUrl"].split(",", 1)[1]))) as image:
            self.assertEqual("RGB", image.mode)
            self.assertEqual((100, 200), image.size)

    def test_noisy_images_obey_byte_budget_without_changing_frame_dimensions(self):
        image = Image.fromarray(np.random.default_rng(4).integers(0, 256, (400, 400, 3), dtype=np.uint8))
        with patch("preview.MAX_BYTES", 5000):
            preview = build_preview(image)
        encoded = base64.b64decode(preview["imageDataUrl"].split(",", 1)[1])
        self.assertLessEqual(len(encoded), 5000)
        self.assertEqual((400, 400), (preview["width"], preview["height"]))


if __name__ == "__main__":
    unittest.main()
