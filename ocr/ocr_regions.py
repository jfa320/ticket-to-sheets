"""Recognize large images without shrinking small receipt text."""

from dataclasses import dataclass
from difflib import SequenceMatcher
import unicodedata


@dataclass
class _Candidate:
    detection: dict
    region: tuple
    clipped_edges: int

    def quality(self):
        item = self.detection
        area = max(0, item["right"] - item["left"]) * max(0, item["bottom"] - item["top"])
        # A complete detection is more useful than a confident fragment at a seam.
        return (-self.clipped_edges, item.get("confidence", item.get("score", 0)), area)


def _origins(length, max_side, overlap):
    origin = 0
    while True:
        yield origin
        if origin + max_side >= length:
            return
        origin += max_side - overlap


def _translate(detection, x, y):
    translated = dict(detection)
    for name in ("left", "right"):
        translated[name] = detection[name] + x
    for name in ("top", "bottom"):
        translated[name] = detection[name] + y
    translated["box"] = [[point[0] + x, point[1] + y] for point in detection["box"]]
    return translated


def _clipped_edges(detection, region, image_size):
    left, top, right, bottom = region
    width, height = image_size
    margin = max(2.0, min(detection["height"] * 0.2, 6.0))
    return sum((
        left > 0 and detection["left"] <= left + margin,
        top > 0 and detection["top"] <= top + margin,
        right < width and detection["right"] >= right - margin,
        bottom < height and detection["bottom"] >= bottom - margin,
    ))


def _intersection(first, second):
    return (
        max(0, min(first[2], second[2]) - max(first[0], second[0])),
        max(0, min(first[3], second[3]) - max(first[1], second[1])),
    )


def _fragment_text_matches(first, second):
    normalized = ["".join(char for char in unicodedata.normalize("NFKD", text).upper()
                          if char.isalnum()) for text in (first, second)]
    shorter, longer = sorted(normalized, key=len)
    if len(shorter) < 3:
        return False
    # Text only supports a geometric containment match at a crop seam. It never
    # makes boxes elsewhere duplicates. Accept a small OCR typo in a fragment.
    return any(SequenceMatcher(None, shorter, fragment, autojunk=False).ratio() >= 0.8
               for fragment in (longer[:len(shorter)], longer[-len(shorter):]))


def _duplicates(first, second):
    # Preserve separate OCR boxes from one region and repeated products elsewhere.
    if first.region == second.region:
        return False
    region_width, region_height = _intersection(first.region, second.region)
    if region_width <= 0 or region_height <= 0:
        return False

    a, b = first.detection, second.detection
    a_bounds = (a["left"], a["top"], a["right"], a["bottom"])
    b_bounds = (b["left"], b["top"], b["right"], b["bottom"])
    overlap_width, overlap_height = _intersection(a_bounds, b_bounds)
    smaller_width = min(a["right"] - a["left"], b["right"] - b["left"])
    smaller_height = min(a["bottom"] - a["top"], b["bottom"] - b["top"])
    if smaller_width <= 0 or smaller_height <= 0:
        return False
    if overlap_width < smaller_width * 0.75 or overlap_height < smaller_height * 0.75:
        return False
    area_a = (a["right"] - a["left"]) * (a["bottom"] - a["top"])
    area_b = (b["right"] - b["left"]) * (b["bottom"] - b["top"])
    intersection = overlap_width * overlap_height
    if intersection / (area_a + area_b - intersection) >= 0.5:
        return True
    # Unequal boxes at a seam can be a fragment of the same text, but a price
    # contained in a broad description box is a separate detection.
    return bool(first.clipped_edges or second.clipped_edges) and _fragment_text_matches(a["text"], b["text"])


def recognize_regions(image, recognize_fn, max_side=1400, overlap=160):
    """Run ``recognize_fn(PIL_image)`` on overlapping, unscaled image regions.

    The callback returns detection dictionaries with ``box``, ``left``, ``top``,
    ``right``, ``bottom``, ``width`` and ``height`` in region coordinates, plus
    text/confidence and any other fields. Returned coordinates refer to ``image``.
    Duplicates from different regions are merged by position, preferring boxes
    away from internal crop edges and then confidence. Very different box sizes
    additionally require compatible fragment text at a seam; text alone never
    makes detections duplicates.

    Regions are at most ``max_side`` on each axis; overlap is capped at 160 pixels
    and ``max_side - 1``. Final regions may be smaller. A one-region image is
    passed directly to the callback and its result is returned unchanged.
    """
    if not isinstance(max_side, int) or max_side <= 0:
        raise ValueError("max_side must be a positive integer")
    if not isinstance(overlap, int) or overlap < 0:
        raise ValueError("overlap must be a nonnegative integer")
    if max(image.size) <= max_side:
        return recognize_fn(image)

    overlap = min(overlap, 160, max_side - 1)
    candidates = []
    for y in _origins(image.height, max_side, overlap):
        for x in _origins(image.width, max_side, overlap):
            region = (x, y, min(x + max_side, image.width), min(y + max_side, image.height))
            for detection in recognize_fn(image.crop(region)):
                translated = _translate(detection, x, y)
                incoming = _Candidate(translated, region, _clipped_edges(translated, region, image.size))
                duplicates = [item for item in candidates if _duplicates(item, incoming)]
                if not duplicates:
                    candidates.append(incoming)
                    continue
                best = max([incoming] + duplicates, key=lambda item: item.quality())
                if best is incoming:
                    candidates = [item for item in candidates if item not in duplicates]
                    candidates.append(incoming)

    return [item.detection for item in sorted(candidates, key=lambda item: (
        item.detection["top"], item.detection["left"],
    ))]
