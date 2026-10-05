"""Optional local illumination candidates without OCR model dependencies."""

import numpy as np
from PIL import Image, ImageOps

from preprocess import load_cv2


def illumination_variants(image):
    """Yield at most two grayscale candidates, leaving ``image`` unchanged.

    The caller bounds image dimensions before calling. Missing OpenCV, very
    small images and flat images produce no candidates. Continuous grayscale
    values preserve faint printing that a hard threshold could discard.
    """
    if min(image.size) < 16:
        return
    cv2 = load_cv2()
    if cv2 is None:
        return

    array = np.asarray(ImageOps.grayscale(image))
    if int(array.max()) - int(array.min()) <= 2:
        return

    # Keep tiles large enough to avoid strengthening individual noise pixels.
    grid = (min(8, max(2, image.width // 96)), min(8, max(2, image.height // 96)))
    clahe = cv2.createCLAHE(clipLimit=1.5, tileGridSize=grid)
    yield "local-contrast", Image.fromarray(clahe.apply(array))

    # Closing fills dark strokes in the estimated paper without smoothing the
    # source glyphs. Smooth only this background estimate, then normalize it.
    kernel_size = min(71, max(15, int(min(image.size) * 0.08) | 1))
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    background = cv2.morphologyEx(array, cv2.MORPH_CLOSE, kernel)
    background = cv2.GaussianBlur(background, (0, 0), sigmaX=max(3, kernel_size / 6))
    background = np.maximum(background, array).astype(np.float32)
    gain = np.minimum(245.0 / np.maximum(background, 1), 2.5)
    normalized = np.minimum(array.astype(np.float32) * gain, 245).astype(np.uint8)
    yield "shadow-normalized", Image.fromarray(normalized)
