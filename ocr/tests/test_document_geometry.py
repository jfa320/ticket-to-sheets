import os
import sys
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from document_geometry import correct_document_geometry, deskew_document, rectify_perspective
from preprocess import load_cv2


def receipt(width=700, height=900, markers=True):
    image = Image.new("RGB", (width, height), (250, 250, 250))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=26)
    for index, top in enumerate(range(140, height - 100, 75)):
        draw.text((65, top), f"PRODUCTO ALFA {index + 1} UN 1200,00", fill=(20, 20, 20), font=font)
    if markers:
        for y in (65, height // 2 + 35, height - 55):
            draw.line((40, y, width - 40, y), fill=(235, 20, 20), width=3)
        for x, y in ((10, 10), (width - 15, 10), (10, height - 15), (width - 15, height - 15)):
            draw.rectangle((x, y, x + 4, y + 4), fill=(20, 220, 20))
    return image


def red_line_slopes(image):
    array = np.asarray(image.convert("RGB"))
    mask = (array[:, :, 0] > 175) & (array[:, :, 1] < 100) & (array[:, :, 2] < 100)
    y, x = np.where(mask)
    occupied_rows = np.unique(y)
    groups = np.split(occupied_rows, np.where(np.diff(occupied_rows) > 10)[0] + 1)
    return [float(np.polyfit(x[np.isin(y, group)], y[np.isin(y, group)], 1)[0])
            for group in groups if len(group) and np.count_nonzero(np.isin(y, group)) > 100]


class DocumentGeometryOptionalTest(unittest.TestCase):
    def test_missing_opencv_returns_no_candidate_and_keeps_original(self):
        image = receipt()
        original = image.tobytes()
        with patch("document_geometry.load_cv2", return_value=None):
            self.assertIsNone(correct_document_geometry(image))
            self.assertIsNone(rectify_perspective(image))
            self.assertIsNone(deskew_document(image))
        self.assertEqual(original, image.tobytes())


class DocumentGeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cv2 = load_cv2()
        if cls.cv2 is None:
            raise unittest.SkipTest("OpenCV is not installed")

    def test_positive_and_negative_small_skew_are_corrected_with_corner_ink_preserved(self):
        for angle in (-6, 6):
            with self.subTest(angle=angle):
                image = receipt().rotate(angle, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white")
                original = image.tobytes()

                corrected = deskew_document(image)

                self.assertIsNotNone(corrected)
                self.assertIsNot(image, corrected)
                self.assertEqual("RGB", corrected.mode)
                slopes = red_line_slopes(corrected)
                self.assertEqual(3, len(slopes))
                self.assertLess(max(abs(value) for value in slopes), 0.015)
                array = np.asarray(corrected)
                green = (array[:, :, 1] > 150) & (array[:, :, 0] < 90) & (array[:, :, 2] < 90)
                count, _, stats, _ = self.cv2.connectedComponentsWithStats(green.astype(np.uint8), 8)
                self.assertEqual(4, sum(stats[index, self.cv2.CC_STAT_AREA] >= 8 for index in range(1, count)))
                self.assertLessEqual(corrected.width * corrected.height, 4_000_000)
                self.assertEqual(original, image.tobytes())

    def test_sideways_text_supports_the_same_small_skew_correction(self):
        image = receipt().rotate(96, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white")

        corrected = deskew_document(image)

        self.assertIsNotNone(corrected)
        # Turn it upright only for the independent red-marker slope measurement.
        slopes = red_line_slopes(corrected.rotate(-90, expand=True))
        self.assertEqual(3, len(slopes))
        self.assertLess(max(abs(value) for value in slopes), 0.015)

    def test_straight_document_returns_no_candidate(self):
        image = receipt()
        self.assertIsNone(deskew_document(image))
        self.assertIsNone(correct_document_geometry(image))

    def test_single_text_row_and_physical_lines_are_insufficient_skew_evidence(self):
        image = Image.new("RGB", (700, 900), "white")
        draw = ImageDraw.Draw(image)
        font = ImageFont.load_default(size=26)
        draw.text((65, 160), "PRODUCTO ALFA 1200,00", fill="black", font=font)
        for y in (100, 400, 700):
            draw.line((40, y, 650, y), fill="black", width=3)
        image = image.rotate(6, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white")

        self.assertIsNone(deskew_document(image))

    def test_blank_image_and_small_bright_quadrilateral_are_not_documents(self):
        for image in (Image.new("RGB", (700, 900), "white"), Image.new("RGB", (700, 900), (30, 30, 30))):
            self.assertIsNone(correct_document_geometry(image))
        image = Image.new("RGB", (700, 900), (30, 30, 30))
        ImageDraw.Draw(image).polygon(((270, 300), (380, 305), (365, 480), (275, 470)), fill="white")

        self.assertIsNone(rectify_perspective(image))

    def test_dark_rectangle_on_white_background_is_not_a_paper_candidate(self):
        image = Image.new("RGB", (700, 900), "white")
        ImageDraw.Draw(image).polygon(((90, 100), (650, 150), (550, 750), (140, 800)), fill=(30, 30, 30))

        self.assertIsNone(rectify_perspective(image))

    def test_trapezoid_paper_is_rectified_and_preserves_border_markers(self):
        paper = receipt()
        source = np.array([[0, 0], [699, 0], [699, 899], [0, 899]], np.float32)
        trapezoid = np.array([[120, 80], [760, 125], [700, 970], [170, 930]], np.float32)
        matrix = self.cv2.getPerspectiveTransform(source, trapezoid)
        photo_array = self.cv2.warpPerspective(np.asarray(paper), matrix, (900, 1050),
                                               borderMode=self.cv2.BORDER_CONSTANT, borderValue=(35, 35, 35))
        photo = Image.fromarray(photo_array)
        original = photo.tobytes()

        corrected = rectify_perspective(photo)

        self.assertIsNotNone(corrected)
        slopes = red_line_slopes(corrected)
        self.assertEqual(3, len(slopes))
        self.assertLess(max(abs(value) for value in slopes), 0.015)
        self.assertGreater(corrected.width, 630)
        self.assertGreater(corrected.height, 830)
        array = np.asarray(corrected)
        green = (array[:, :, 1] > 150) & (array[:, :, 0] < 90) & (array[:, :, 2] < 90)
        count, _, stats, _ = self.cv2.connectedComponentsWithStats(green.astype(np.uint8), 8)
        self.assertEqual(4, sum(stats[index, self.cv2.CC_STAT_AREA] >= 5 for index in range(1, count)))
        self.assertLessEqual(corrected.width * corrected.height, 4_000_000)
        self.assertEqual(original, photo.tobytes())

    def test_straight_paper_rectangle_does_not_trigger_a_perspective_transform(self):
        image = Image.new("RGB", (900, 1100), (30, 30, 30))
        image.paste(receipt(), (100, 100))

        self.assertIsNone(rectify_perspective(image))

    def test_rotated_blank_paper_border_does_not_establish_text_skew(self):
        image = Image.new("RGB", (900, 1100), (30, 30, 30))
        ImageDraw.Draw(image).rectangle((100, 100, 800, 1000), fill="white")
        image = image.rotate(6, resample=Image.Resampling.BICUBIC, expand=True, fillcolor=(30, 30, 30))

        self.assertIsNone(rectify_perspective(image))
        self.assertIsNone(correct_document_geometry(image))

    def test_expanded_rotation_stays_within_pixel_budget(self):
        image = receipt().resize((1750, 2250), Image.Resampling.LANCZOS)
        image = image.rotate(5, resample=Image.Resampling.BICUBIC, expand=True, fillcolor="white")
        # Match the caller's limit: the geometry input already has at most 4 MP.
        scale = (4_000_000 / (image.width * image.height)) ** 0.5
        image = image.resize((int(image.width * scale), int(image.height * scale)), Image.Resampling.LANCZOS)

        corrected = deskew_document(image)

        self.assertIsNotNone(corrected)
        self.assertLessEqual(corrected.width * corrected.height, 4_000_000)
        self.assertGreater(corrected.width * corrected.height, 3_800_000)


if __name__ == "__main__":
    unittest.main()
