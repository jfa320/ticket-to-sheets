"""Recover an omitted description immediately above a standalone quantity/price."""

import math
import re
from difflib import SequenceMatcher

from PIL import Image, ImageDraw, ImageEnhance, ImageOps

from scoring import PRICE_PATTERN, normalize_text


QUANTITY_PRICE = re.compile(r"^\d{1,4}(?:[.,]\d{1,4})?\s*(?:[xX×%]\s*\$?|\$)\s*\d[\d.,]*$")
METADATA = re.compile(r"\b(?:cliente|consumidor|fecha|hora|total|subtotal|cuit|telefono|ticket|iva|caja|orden)\b")


def recover_missing_descriptions(image, detections, recognize_line, should_continue=None, detail_image=None,
                                 max_regions=2):
    """Read only empty bands anchored to a detected quantity and monetary amount.

    Two compatible recognition-only reads are required. Coordinates stay in the
    selected frame, and recovered text always requires review.
    """
    updated = list(detections)
    statistics = {"attemptedRegions": 0, "acceptedRegions": 0, "ocrCalls": 0, "failedCalls": 0}
    valid = []
    for item in detections:
        try:
            bounds = tuple(float(item[key]) for key in ("left", "top", "right", "bottom"))
            left, top, right, bottom = bounds
            if all(math.isfinite(value) for value in bounds) and 0 <= left < right <= image.width \
                    and 0 <= top < bottom <= image.height:
                valid.append((item, bounds))
        except (KeyError, TypeError, ValueError):
            continue
    for item, (left, top, right, bottom) in valid:
        if statistics["attemptedRegions"] >= min(max_regions, 2):
            break
        if should_continue is not None and not should_continue():
            break
        text = str(item.get("text", "")).strip()
        if not QUANTITY_PRICE.fullmatch(text) or not PRICE_PATTERN.search(text):
            continue
        height = bottom - top
        previous = [bounds[3] for other, bounds in valid if bounds[3] <= top
                    and bounds[0] < right and bounds[2] > left]
        if not previous or top - max(previous) < height * 1.3:
            continue
        band = (max(0, math.floor(left - height * .85)),
                max(math.ceil(max(previous) + 2), math.floor(top - height * 1.45)),
                min(image.width, math.ceil(max(bounds[2] for _, bounds in valid) + height * .85)),
                math.floor(top + height * .5))
        if band[3] - band[1] < height * .8 or band[2] - band[0] < height * 5:
            continue
        # Never read an area already covered by a detected description or header.
        if any(other is not item and bounds[0] < band[2] and bounds[2] > band[0]
               and bounds[1] < band[3] and bounds[3] > band[1] for other, bounds in valid):
            continue
        source = detail_image if detail_image is not None else image
        sx, sy = source.width / image.width, source.height / image.height
        region = source.crop((math.floor(band[0] * sx), math.floor(band[1] * sy),
                              math.ceil(band[2] * sx), math.ceil(band[3] * sy))).convert("RGB")
        low, high = ImageOps.grayscale(region).getextrema()
        if high - low < 30:
            continue
        # Curved/slanted descriptions can extend below the multiplier's top at
        # the other end of the paper. Mask the known multiplier, preserving ink
        # elsewhere rather than clipping the description's final characters.
        ImageDraw.Draw(region).rectangle((math.floor((left - band[0]) * sx), math.floor((top - band[1]) * sy),
                                          math.ceil((right - band[0]) * sx), region.height), fill="white")
        scale = min(2, 1400 / region.width, 250 / region.height)
        region = region.resize((max(1, round(region.width * scale)), max(1, round(region.height * scale))),
                               Image.Resampling.LANCZOS)
        region = ImageOps.expand(region, border=16, fill="white")
        statistics["attemptedRegions"] += 1
        readings = []
        for candidate in (region, ImageEnhance.Contrast(region).enhance(1.35)):
            if should_continue is not None and not should_continue():
                break
            statistics["ocrCalls"] += 1
            try:
                reading = recognize_line(candidate)
                value, confidence = reading
                value = " ".join(value.split())
                confidence = float(confidence)
                if not math.isfinite(confidence) or not .85 <= confidence <= 1 or len(value.split()) < 2 \
                        or sum(char.isalpha() for char in value) < 8 or PRICE_PATTERN.search(value) \
                        or METADATA.search(normalize_text(value)):
                    break
                readings.append((value, confidence))
            except Exception:
                statistics["failedCalls"] += 1
                break
        if len(readings) != 2 or SequenceMatcher(None, readings[0][0].upper(), readings[1][0].upper(),
                                               autojunk=False).ratio() < .92:
            continue
        x1, y1, x2, y2 = band
        confidence = min(reading[1] for reading in readings)
        # Small spelling disagreements remain visible: use an actual reading,
        # never synthesize text from the two, and always require user review.
        value = max(readings, key=lambda reading: reading[1])[0]
        updated.append({"text": value, "confidence": confidence, "score": confidence,
                        "left": x1, "top": y1, "right": x2, "bottom": y2,
                        "width": x2 - x1, "height": y2 - y1,
                        "box": [[x1, y1], [x2, y1], [x2, y2], [x1, y2]], "reviewRequired": True})
        statistics["acceptedRegions"] += 1
    return updated, statistics
