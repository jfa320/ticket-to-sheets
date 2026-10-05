"""Optional document geometry candidates, independent of the OCR model."""

import math

import numpy as np
from PIL import Image, ImageOps

from preprocess import load_cv2


MAX_PIXELS = 4_000_000
ANALYSIS_PIXELS = 1_000_000


def _analysis_gray(image):
    gray = ImageOps.grayscale(image)
    scale = min(1.0, math.sqrt(ANALYSIS_PIXELS / (image.width * image.height)))
    if scale < 1:
        gray = gray.resize((max(1, int(image.width * scale)), max(1, int(image.height * scale))),
                           Image.Resampling.LANCZOS)
    return np.asarray(gray), (image.width / gray.width, image.height / gray.height)


def _bounded_size(width, height):
    scale = min(1.0, math.sqrt(MAX_PIXELS / (width * height)))
    return max(1, int(width * scale)), max(1, int(height * scale)), scale


def _image_array(image):
    return np.asarray(image if image.mode in ("L", "RGB") else image.convert("RGB"))


def _ordered_quad(points):
    points = np.asarray(points, dtype=np.float32).reshape(4, 2)
    # Sum/difference ordering is ambiguous for strongly rotated diamonds. The
    # angle sort keeps all four corners distinct, then starts at the upper left.
    center = points.mean(axis=0)
    order = np.argsort(np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0]))
    points = points[order]
    return np.roll(points, -int(np.argmin(points.sum(axis=1))), axis=0)


def _paper_quad(gray, cv2):
    height, width = gray.shape
    if min(height, width) < 60 or int(gray.max()) - int(gray.min()) < 30:
        return None
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    _, bright = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    edges = cv2.Canny(blurred, 40, 120)
    candidates = []
    for mask in (bright, edges):
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            area = cv2.contourArea(contour)
            if not 0.20 * width * height <= area <= 0.97 * width * height:
                continue
            perimeter = cv2.arcLength(contour, True)
            approx = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
            if len(approx) != 4 or not cv2.isContourConvex(approx):
                continue
            quad = _ordered_quad(approx)
            lengths = np.linalg.norm(np.roll(quad, -1, axis=0) - quad, axis=1)
            if min(lengths) < max(35, min(width, height) * 0.15):
                continue
            # Reject extremely acute corners, often a merged text contour.
            valid_angles = True
            for index in range(4):
                a = quad[(index - 1) % 4] - quad[index]
                b = quad[(index + 1) % 4] - quad[index]
                cosine = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))
                if abs(cosine) > 0.80:
                    valid_angles = False
                    break
            if not valid_angles:
                continue
            paper = np.zeros_like(gray)
            cv2.fillConvexPoly(paper, np.rint(quad).astype(np.int32), 255)
            interior = cv2.erode(paper, np.ones((9, 9), np.uint8)) > 0
            expanded = cv2.dilate(paper, np.ones((17, 17), np.uint8)) > 0
            exterior = expanded & (paper == 0)
            if not np.any(interior) or np.count_nonzero(exterior) < 40:
                continue
            inside = gray[interior]
            outside = gray[exterior]
            # A white paper boundary must differ from its immediate surroundings.
            # This also excludes white-background text blocks and dark logos.
            if np.median(inside) < 160 or np.median(inside) - float(np.median(outside)) < 18:
                continue
            if np.mean(inside > 145) < 0.70:
                continue
            candidates.append((area, quad))
    return max(candidates, key=lambda item: item[0])[1] if candidates else None


def rectify_perspective(image, cv2=None):
    """Return a rectified large, bright paper quadrilateral, or ``None``.

    No transformation is produced for an already rectangular paper boundary.
    Unclear, tiny, dark or incomplete boundaries are intentionally left alone.
    """
    cv2 = load_cv2() if cv2 is None else cv2
    if cv2 is None:
        return None
    gray, (scale_x, scale_y) = _analysis_gray(image)
    quad = _paper_quad(gray, cv2)
    if quad is None:
        return None
    quad = quad * np.array([scale_x, scale_y], dtype=np.float32)
    edges = np.roll(quad, -1, axis=0) - quad
    lengths = np.linalg.norm(edges, axis=1)
    # A rotated rectangle alone cannot establish text skew. Perspective needs
    # unequal opposite sides or corners which deviate from right angles.
    orthogonality = max(abs(float(np.dot(edges[index], edges[(index + 1) % 4])
                                      / (lengths[index] * lengths[(index + 1) % 4]))) for index in range(4))
    perspective = max(abs(float(lengths[0] - lengths[2])) / max(lengths[0], lengths[2]),
                      abs(float(lengths[1] - lengths[3])) / max(lengths[1], lengths[3]))
    if max(orthogonality, perspective) < 0.025:
        return None
    # A small outward margin protects ink and the paper edge from approximation.
    center = quad.mean(axis=0)
    vectors = quad - center
    quad = quad + vectors / np.linalg.norm(vectors, axis=1)[:, None] * 3
    quad[:, 0] = np.clip(quad[:, 0], 0, image.width - 1)
    quad[:, 1] = np.clip(quad[:, 1], 0, image.height - 1)
    lengths = np.linalg.norm(np.roll(quad, -1, axis=0) - quad, axis=1)
    width, height, _ = _bounded_size(math.ceil(max(lengths[0], lengths[2])) + 1,
                                     math.ceil(max(lengths[1], lengths[3])) + 1)
    destination = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], np.float32)
    matrix = cv2.getPerspectiveTransform(quad.astype(np.float32), destination)
    corrected = cv2.warpPerspective(_image_array(image), matrix, (width, height),
                                    flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_CONSTANT,
                                    borderValue=(255, 255, 255))
    return Image.fromarray(corrected)


def _text_skew(gray, cv2):
    height, width = gray.shape
    if min(height, width) < 40:
        return None
    binary = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY_INV, 31, 12)
    count, labels, stats, centroids = cv2.connectedComponentsWithStats(binary, 8)
    characters = []
    accepted_labels = np.zeros(count, dtype=np.uint8)
    for index in range(1, count):
        _, _, component_width, component_height, area = stats[index]
        if (3 <= component_height <= max(35, height * 0.06)
                and 1 <= component_width <= component_height * 3
                and area >= 4 and area / (component_width * component_height) < 0.98):
            characters.append(centroids[index])
            accepted_labels[index] = 255
    if len(characters) < 18:
        return None
    characters = np.asarray(characters)
    clean = accepted_labels[labels]
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(9, int(width * 0.025)), 3))
    rows = cv2.morphologyEx(clean, cv2.MORPH_CLOSE, kernel)
    contours, _ = cv2.findContours(rows, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    records = []
    for contour in contours:
        (cx, cy), (a, b), angle = cv2.minAreaRect(contour)
        length, thickness = max(a, b), min(a, b)
        if length < max(40, width * 0.12) or thickness < 3 or length / thickness < 4:
            continue
        angle = angle if a >= b else angle + 90
        angle = (angle + 90) % 180 - 90
        if abs(angle) > 12:
            continue
        x, y, w, h = cv2.boundingRect(contour)
        inside = characters[(characters[:, 0] >= x) & (characters[:, 0] <= x + w)
                            & (characters[:, 1] >= y) & (characters[:, 1] <= y + h)]
        evidence = sum(cv2.pointPolygonTest(contour, tuple(point), False) >= 0 for point in inside)
        if evidence >= 5:
            records.append((angle, cy - math.tan(math.radians(angle)) * cx, thickness, evidence))
    if len(records) < 3:
        return None
    median = float(np.median([record[0] for record in records]))
    consistent = [record for record in records if abs(record[0] - median) <= 1.25]
    if len(consistent) < 3:
        return None
    spacing = max(5, float(np.median([record[2] for record in consistent])) * 0.75)
    levels = []
    for record in sorted(consistent, key=lambda item: item[1]):
        if not levels or record[1] - levels[-1] > spacing:
            levels.append(record[1])
    if len(levels) < 3 or levels[-1] - levels[0] < spacing * 2:
        return None
    return float(np.median([record[0] for record in consistent])), len(levels), sum(record[3] for record in consistent)


def deskew_document(image, cv2=None):
    """Correct a 0.35–12 degree skew supported by at least three text rows.

    Both horizontal and vertical text axes are considered. Physical borders or
    a single text row do not establish sufficient evidence. Rotation expands
    the canvas to retain corners; a final size bound limits the result to 4 MP.
    """
    cv2 = load_cv2() if cv2 is None else cv2
    if cv2 is None:
        return None
    gray, _ = _analysis_gray(image)
    estimates = [_text_skew(gray, cv2), _text_skew(np.rot90(gray).copy(), cv2)]
    estimates = [estimate for estimate in estimates if estimate is not None]
    if not estimates:
        return None
    angle, _, _ = max(estimates, key=lambda estimate: (estimate[1], estimate[2]))
    if not 0.35 <= abs(angle) <= 12:
        return None
    radians = math.radians(angle)
    full_width = math.ceil(image.width * abs(math.cos(radians)) + image.height * abs(math.sin(radians)))
    full_height = math.ceil(image.height * abs(math.cos(radians)) + image.width * abs(math.sin(radians)))
    width, height, scale = _bounded_size(full_width, full_height)
    center = ((image.width - 1) / 2, (image.height - 1) / 2)
    matrix = cv2.getRotationMatrix2D(center, angle, scale)
    matrix[0, 2] += (width - 1) / 2 - center[0]
    matrix[1, 2] += (height - 1) / 2 - center[1]
    corrected = cv2.warpAffine(_image_array(image), matrix, (width, height), flags=cv2.INTER_CUBIC,
                               borderMode=cv2.BORDER_CONSTANT, borderValue=(255, 255, 255))
    return Image.fromarray(corrected)


def correct_document_geometry(image):
    """Return an independent perspective/skew candidate, or ``None``.

    OpenCV is optional. The original image is never modified. A reliable paper
    quadrilateral can establish perspective; skew additionally needs multiple
    text rows. Geometrically straight or uncertain images produce no candidate.
    """
    cv2 = load_cv2()
    if cv2 is None:
        return None
    perspective = rectify_perspective(image, cv2)
    base = perspective if perspective is not None else image
    skew = deskew_document(base, cv2)
    return skew if skew is not None else perspective
