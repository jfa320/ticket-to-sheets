"""Image candidates for local OCR, without loading Flask or PaddleOCR."""

import math
import logging

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter, ImageOps

from preprocess import remove_physical_lines
from document_geometry import correct_document_geometry
from illumination import illumination_variants


MAX_SHORT_SIDE = 1400
MAX_PIXELS = 4_000_000
LOGGER = logging.getLogger(__name__)


def crop_receipt_region(image):
    gray = ImageOps.autocontrast(ImageOps.grayscale(image))
    array = np.asarray(gray)
    mask = array > 165
    row_hits = np.where(mask.mean(axis=1) > 0.45)[0]
    col_hits = np.where(mask.mean(axis=0) > 0.45)[0]

    if len(row_hits) < 40 or len(col_hits) < 40:
        return image

    top = max(int(row_hits[0]) - 25, 0)
    bottom = min(int(row_hits[-1]) + 25, image.height)
    left = max(int(col_hits[0]) - 25, 0)
    right = min(int(col_hits[-1]) + 25, image.width)

    if right - left < image.width * 0.22 or bottom - top < image.height * 0.35:
        return image

    return image.crop((left, top, right, bottom))


def _bounded_size(width, height, max_short_side, max_pixels, requested_scale=1.0):
    if max_short_side <= 0 or max_pixels <= 0:
        raise ValueError("Image limits must be positive")

    scale = min(
        requested_scale,
        max_short_side / min(width, height),
        math.sqrt(max_pixels / (width * height)),
    )
    new_width = max(1, int(width * scale))
    new_height = max(1, int(height * scale))
    # A one-pixel side cannot shrink further, even when the scale is below one.
    if new_width * new_height > max_pixels:
        if new_width >= new_height:
            new_width = max(1, int(max_pixels) // new_height)
        else:
            new_height = max(1, int(max_pixels) // new_width)
    return new_width, new_height


def limit_image_size(image, max_short_side=MAX_SHORT_SIDE, max_pixels=MAX_PIXELS):
    """Bound memory while preserving the long axis of narrow receipts."""
    size = _bounded_size(image.width, image.height, max_short_side, max_pixels)
    if size == image.size:
        return image
    return image.resize(size, Image.Resampling.LANCZOS)


def _illumination_candidates(image, suffix):
    try:
        for name, candidate in illumination_variants(image):
            yield f"{name}-{suffix}", limit_image_size(candidate)
    except Exception as exc:
        LOGGER.warning("Skipping illumination candidates: %s", exc)


def build_variants(image, debug_dir=None, save_debug_image=None, document_corrections=True, with_metadata=False):
    """Yield one candidate at a time; debug output is supplied by the caller."""
    source = image if image.mode == "RGB" else image.convert("RGB")
    corrected = None
    geometry_checked = False

    def pack(name, candidate, frame):
        if with_metadata:
            return name, candidate, {"frame": frame, "size": candidate.size}
        return name, candidate

    for rotation in (0, 90, 270, 180):
        oriented = source if rotation == 0 else source.rotate(rotation, expand=True)
        suffix = "original" if rotation == 0 else f"rot{rotation}"

        # Preserve color and faint printing even if cropping/contrast is harmful.
        bounded_original = limit_image_size(oriented)
        full_frame, crop_frame, geometry_frame = f"full:{rotation}", f"crop:{rotation}", f"geometry:{rotation}"
        yield pack(f"original-{suffix}", bounded_original, full_frame)

        base = limit_image_size(crop_receipt_region(oriented))
        yield pack(f"cropped-{suffix}", base, crop_frame)

        gray = ImageOps.grayscale(base)
        yield pack(f"gray-{suffix}", gray, crop_frame)

        without_lines = remove_physical_lines(gray)
        if without_lines is not None:
            cleaned, line_mask = without_lines
            if debug_dir and save_debug_image is not None:
                save_debug_image(debug_dir, f"variants/without-lines-{suffix}-mask.png", line_mask)
            del without_lines, line_mask
            yield pack(f"without-lines-{suffix}", cleaned, crop_frame)
            del cleaned

        contrast = ImageEnhance.Contrast(gray).enhance(2.3)
        sharp = ImageEnhance.Sharpness(contrast).enhance(2.0)
        # Compute the target before resizing, avoiding a temporary 16 MP image.
        enhanced_size = _bounded_size(sharp.width, sharp.height, MAX_SHORT_SIDE, MAX_PIXELS, 2.0)
        enlarged = sharp if enhanced_size == sharp.size else sharp.resize(enhanced_size, Image.Resampling.LANCZOS)
        del contrast, sharp
        yield pack(f"enhanced-{suffix}", enlarged, crop_frame)

        yield pack(f"threshold-{suffix}", enlarged.point(lambda pixel: 255 if pixel > 172 else 0, mode="1").convert("L"), crop_frame)

        denoised = enlarged.filter(ImageFilter.MedianFilter(size=3))
        del enlarged
        yield pack(f"denoised-{suffix}", denoised, crop_frame)

        yield pack(f"light-threshold-{suffix}", denoised.point(lambda pixel: 255 if pixel > 150 else 0, mode="1").convert("L"), crop_frame)
        del denoised, gray

        small = limit_image_size(base, 900)
        yield pack(f"small-{suffix}", small, crop_frame)
        yield pack(f"small-gray-{suffix}", ImageOps.grayscale(small), crop_frame)

        if document_corrections:
            # A brightness-based crop may exclude shadowed paper and its text.
            for name, candidate in _illumination_candidates(bounded_original, suffix):
                yield pack(name, candidate, full_frame)
            if not geometry_checked:
                geometry_checked = True
                try:
                    corrected = correct_document_geometry(bounded_original)
                    if corrected is not None:
                        corrected = limit_image_size(corrected)
                except Exception as exc:
                    corrected = None
                    LOGGER.warning("Skipping document geometry candidate: %s", exc)
            if corrected is not None:
                corrected_oriented = corrected if rotation == 0 else corrected.rotate(rotation, expand=True)
                yield pack(f"geometry-{suffix}", corrected_oriented, geometry_frame)
                for name, candidate in _illumination_candidates(corrected_oriented, f"geometry-{suffix}"):
                    yield pack(name, candidate, geometry_frame)
                del corrected_oriented
        del base, small, oriented, bounded_original
