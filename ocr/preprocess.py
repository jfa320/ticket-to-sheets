import logging

import numpy as np
from PIL import Image, ImageOps


LOGGER = logging.getLogger(__name__)


def load_cv2():
    try:
        import cv2
    except Exception as exc:
        LOGGER.warning("OpenCV is not available; skipping line-removal variants: %s", exc)
        return None
    return cv2


def remove_physical_lines(image):
    cv2 = load_cv2()
    if cv2 is None:
        return None

    gray = ImageOps.grayscale(image)
    array = np.array(gray)
    mask = detect_physical_lines(array, cv2)
    if not np.any(mask):
        return gray, Image.fromarray(mask)

    cleaned = cv2.inpaint(array, mask, 3, cv2.INPAINT_TELEA)
    cleaned = np.where(mask > 0, cleaned, array).astype(np.uint8)
    return Image.fromarray(cleaned), Image.fromarray(mask)


def detect_physical_lines(gray_array, cv2=None):
    if cv2 is None:
        cv2 = load_cv2()
    if cv2 is None:
        return np.zeros_like(gray_array, dtype=np.uint8)

    array = np.asarray(gray_array, dtype=np.uint8)
    height, width = array.shape[:2]
    if height < 20 or width < 20:
        return np.zeros((height, width), dtype=np.uint8)

    inverted = cv2.bitwise_not(array)
    binary = cv2.adaptiveThreshold(
        inverted,
        255,
        cv2.ADAPTIVE_THRESH_MEAN_C,
        cv2.THRESH_BINARY,
        31,
        -6,
    )

    horizontal = extract_line_mask(binary, cv2, horizontal=True)
    vertical = extract_line_mask(binary, cv2, horizontal=False)
    mask = cv2.bitwise_or(horizontal, vertical)
    return filter_long_components(mask, cv2)


def extract_line_mask(binary, cv2, horizontal):
    height, width = binary.shape[:2]
    if horizontal:
        kernel_size = (max(30, width // 20), 1)
    else:
        kernel_size = (1, max(30, height // 20))

    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, kernel_size)
    opened = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)
    return cv2.dilate(opened, np.ones((3, 3), dtype=np.uint8), iterations=1)


def filter_long_components(mask, cv2):
    height, width = mask.shape[:2]
    min_horizontal_width = width * 0.50
    min_vertical_height = height * 0.50
    filtered = np.zeros_like(mask, dtype=np.uint8)
    component_count, labels, stats, _ = cv2.connectedComponentsWithStats(mask, 8)

    for component in range(1, component_count):
        x, y, component_width, component_height, area = stats[component]
        if area <= 0:
            continue

        is_horizontal = component_width >= min_horizontal_width and component_height <= max(16, height * 0.04)
        is_vertical = component_height >= min_vertical_height and component_width <= max(16, width * 0.04)
        if is_horizontal or is_vertical:
            filtered[labels == component] = 255

    return filtered
