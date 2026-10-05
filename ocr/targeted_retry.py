"""Conservative, bounded second reads of uncertain OCR detections."""

import math
from numbers import Real
import re

from PIL import Image, ImageEnhance


MAX_REGIONS = 8
MAX_SIDE = 1400
MAX_PIXELS = 1_000_000
LOW_CONFIDENCE = 0.8
MIN_CONFIDENCE = 0.85
MIN_IMPROVEMENT = 0.1


def _number(value):
    if isinstance(value, bool) or not isinstance(value, Real):
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def _confidence(item):
    if not isinstance(item, dict):
        return None
    value = _number(item.get("confidence", item.get("score")))
    return value if value is not None and 0 <= value <= 1 else None


def _bounds(item, image_size):
    if not isinstance(item, dict):
        return None
    values = [_number(item.get(name)) for name in ("left", "top", "right", "bottom")]
    if any(value is None for value in values):
        return None
    left, top, right, bottom = values
    width, height = image_size
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        return None
    return tuple(values)


def _text(item):
    value = item.get("text") if isinstance(item, dict) else None
    return " ".join(value.split()) if isinstance(value, str) else ""


def _intersection(first, second):
    return (max(0.0, min(first[2], second[2]) - max(first[0], second[0])),
            max(0.0, min(first[3], second[3]) - max(first[1], second[1])))


def _safe_crop(bounds, neighbors, image_size):
    left, top, right, bottom = bounds
    margin = min(16, max(4, (bottom - top) * 0.35))
    crop = [max(0, math.floor(left - margin)), max(0, math.floor(top - margin)),
            min(image_size[0], math.ceil(right + margin)), min(image_size[1], math.ceil(bottom + margin))]
    for neighbor in neighbors:
        width, height = _intersection(crop, neighbor)
        if width <= 0 or height <= 0:
            continue
        # Margins may approach another detection, but must not include its ink.
        # Overlapping original boxes cannot be separated reliably by a recrop.
        if neighbor[2] <= left:
            crop[0] = max(crop[0], math.ceil(neighbor[2]))
        elif neighbor[0] >= right:
            crop[2] = min(crop[2], math.floor(neighbor[0]))
        elif neighbor[3] <= top:
            crop[1] = max(crop[1], math.ceil(neighbor[3]))
        elif neighbor[1] >= bottom:
            crop[3] = min(crop[3], math.floor(neighbor[1]))
        else:
            return None
    if crop[0] > left or crop[1] > top or crop[2] < right or crop[3] < bottom:
        return None
    return tuple(crop)


def _prepare_region(image, crop):
    width, height = crop[2] - crop[0], crop[3] - crop[1]
    scale = min(3.0, MAX_SIDE / width, MAX_SIDE / height, math.sqrt(MAX_PIXELS / (width * height)))
    # A very broad/large box offers no useful enlargement within the budget.
    if scale < 1.0:
        return None
    size = (max(1, int(width * scale)), max(1, int(height * scale)))
    region = image.crop(crop)
    if region.mode not in ("RGB", "L"):
        region = region.convert("RGB")
    if region.size != size:
        region = region.resize(size, Image.Resampling.LANCZOS)
    return region, (size[0] / width, size[1] / height)


def _content_profile(text):
    words, numbers = [], []
    for token in text.split():
        value = token.strip("()[]{}:;$€£")
        numeric = re.fullmatch(r"[+\-−]?[0-9OIl|]+(?:[.,][0-9OIl|]+)*", value, flags=re.IGNORECASE)
        if numeric and any(character.isdigit() for character in value):
            sign = "-" if value.startswith(("-", "−")) else "+" if value.startswith("+") else ""
            numbers.append((sign, sum(character.isalnum() or character == "|" for character in value),
                            sum(character in ".," for character in value)))
        elif any(character.isalpha() for character in value):
            words.append(sum(character.isalnum() for character in value))
    return words, numbers


def preserves_content(original, candidate):
    # Permit corrected letters/digits, while refusing a shorter fragment of a
    # description or amount. No catalog, parser or monetary value inference.
    if len(candidate.split()) < len(original.split()):
        return False
    original_words, original_numbers = _content_profile(original)
    candidate_words, candidate_numbers = _content_profile(candidate)
    if len(candidate_words) < len(original_words):
        return False
    if any(new < old * 0.8 for old, new in zip(original_words, candidate_words)):
        return False
    original_characters = sum(character.isalnum() for character in original)
    candidate_characters = sum(character.isalnum() for character in candidate)
    if candidate_characters < original_characters * 0.8:
        return False
    if len(candidate_numbers) != len(original_numbers):
        return False
    return all(new[0] == old[0] and new[1] >= old[1] and new[2] >= old[2]
               for old, new in zip(original_numbers, candidate_numbers))


def _candidate(result, original, target, neighbors, crop, scale, region_size):
    if not isinstance(result, (list, tuple)):
        return None
    acceptable = []
    source_confidence = _confidence(original)
    for item in result:
        confidence = _confidence(item)
        text = _text(item)
        local = _bounds(item, region_size)
        if confidence is None or confidence < MIN_CONFIDENCE or confidence - source_confidence < MIN_IMPROVEMENT - 1e-9:
            continue
        if not text or local is None or not preserves_content(_text(original), text):
            continue
        edge_margin = max(2.0, min(scale))
        if min(local[0], local[1], region_size[0] - local[2], region_size[1] - local[3]) < edge_margin:
            continue
        global_box = (crop[0] + local[0] / scale[0], crop[1] + local[1] / scale[1],
                      crop[0] + local[2] / scale[0], crop[1] + local[3] / scale[1])
        overlap_width, overlap_height = _intersection(target, global_box)
        coverage_x = overlap_width / (target[2] - target[0])
        coverage_y = overlap_height / (target[3] - target[1])
        candidate_area = (global_box[2] - global_box[0]) * (global_box[3] - global_box[1])
        target_area = (target[2] - target[0]) * (target[3] - target[1])
        if coverage_x < 0.8 or coverage_y < 0.8 or candidate_area > target_area * 2:
            continue
        if any(all(value > 0 for value in _intersection(global_box, neighbor)) for neighbor in neighbors):
            continue
        acceptable.append((confidence, coverage_x * coverage_y, text))
    if not acceptable:
        return None
    confidence, _, text = max(acceptable, key=lambda item: (item[0], item[1]))
    return text, confidence


def refine_detections(image, detections, recognize_fn, max_regions=8):
    """Return ``(updated_detections, summary)`` after two reads per doubtful box.

    The callback accepts a PIL crop and returns detections in that crop's actual
    enlarged coordinates. Only finite confidences below 0.8 and valid boxes are
    retried, most uncertain first. Each crop uses raw enlargement plus moderate
    contrast, at most 3x, 1400 pixels per axis and 1 MP. The absolute budget is
    eight regions/sixteen OCR calls, even if ``max_regions`` is larger.

    Accept only matching text (case/whitespace normalized; digits/punctuation
    intact), both confidence >=0.85 and gain >=0.1, compatible geometry and no
    lost words/numbers/amounts. Empty, failed or conflicting reads keep the input.
    Order, count and original geometry are retained; input objects are untouched.
    Summary contains only counts: attemptedRegions, acceptedRegions, ocrCalls,
    failedCalls. The caller should invoke this on the winning OCR variant only.
    """
    if isinstance(max_regions, bool) or not isinstance(max_regions, int) or max_regions < 0:
        raise ValueError("max_regions must be a nonnegative integer")
    summary = {"attemptedRegions": 0, "acceptedRegions": 0, "ocrCalls": 0, "failedCalls": 0}
    updated = list(detections)
    bounds = [_bounds(item, image.size) for item in detections]
    eligible = []
    for index, item in enumerate(detections):
        confidence = _confidence(item)
        if confidence is not None and confidence < LOW_CONFIDENCE and bounds[index] is not None and _text(item):
            eligible.append((confidence, index))
    for _, index in sorted(eligible):
        if summary["attemptedRegions"] >= min(max_regions, MAX_REGIONS):
            break
        original = detections[index]
        neighbors = [box for neighbor_index, box in enumerate(bounds) if box is not None and neighbor_index != index]
        crop = _safe_crop(bounds[index], neighbors, image.size)
        if crop is None:
            continue
        prepared = _prepare_region(image, crop)
        if prepared is None:
            continue
        region, scale = prepared
        summary["attemptedRegions"] += 1
        reads = []
        for candidate_image in (region, ImageEnhance.Contrast(region).enhance(1.35)):
            summary["ocrCalls"] += 1
            try:
                result = recognize_fn(candidate_image)
            except Exception:
                summary["failedCalls"] += 1
                reads.append(None)
                continue
            reads.append(_candidate(result, original, bounds[index], neighbors, crop, scale, region.size))
        if any(read is None for read in reads) or reads[0][0].upper() != reads[1][0].upper():
            continue
        text = reads[0][0]
        confidence = min(read[1] for read in reads)
        updated[index] = dict(original, text=text, confidence=confidence, score=confidence)
        summary["acceptedRegions"] += 1
    return updated, summary
