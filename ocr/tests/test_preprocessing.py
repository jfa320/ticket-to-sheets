import os
import sys
import unittest

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from preprocess import detect_physical_lines, load_cv2, remove_physical_lines


def mask_has_pixels(mask):
    return int(np.array(mask).sum()) > 0


class PreprocessingTest(unittest.TestCase):
    def setUp(self):
        if load_cv2() is None:
            self.skipTest("OpenCV is not installed")

    def test_removes_long_horizontal_line(self):
        image = Image.new("L", (300, 120), 255)
        draw = ImageDraw.Draw(image)
        draw.line((10, 60, 290, 60), fill=0, width=4)

        cleaned, mask = remove_physical_lines(image)

        self.assertTrue(mask_has_pixels(mask))
        self.assertGreater(cleaned.getpixel((150, 60)), 180)

    def test_removes_long_vertical_line(self):
        image = Image.new("L", (160, 260), 255)
        draw = ImageDraw.Draw(image)
        draw.line((80, 10, 80, 250), fill=0, width=4)

        cleaned, mask = remove_physical_lines(image)

        self.assertTrue(mask_has_pixels(mask))
        self.assertGreater(cleaned.getpixel((80, 130)), 180)

    def test_preserves_short_strokes(self):
        image = Image.new("L", (300, 120), 255)
        draw = ImageDraw.Draw(image)
        draw.line((40, 60, 95, 60), fill=0, width=3)
        draw.line((130, 60, 185, 60), fill=0, width=3)
        draw.line((220, 30, 220, 80), fill=0, width=3)

        mask = detect_physical_lines(np.array(image))

        self.assertEqual(0, int(mask.sum()))

    def test_image_without_lines_keeps_empty_mask(self):
        image = Image.new("L", (220, 100), 255)
        draw = ImageDraw.Draw(image)
        draw.text((20, 35), "PRODUCTO 123,45", fill=0)

        _, mask = remove_physical_lines(image)

        self.assertEqual(0, int(np.array(mask).sum()))


if __name__ == "__main__":
    unittest.main()
