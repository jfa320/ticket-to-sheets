import math
import re
import unicodedata


PRICE_PATTERN = re.compile(
    r"(?:\$?\s*\d+(?:[.,]\d{3})*[.,]\d{2}|\$?\s*\d+[.,]\d{2})"
)
STRUCTURAL_TOKENS = (
    "fecha",
    "total",
    "subtotal",
    "comprobante",
    "ticket",
    "cuit",
    "consumidor",
    "iva",
)
NON_ITEM_TOKENS = (
    "fecha",
    "hora",
    "total",
    "subtotal",
    "cuit",
    "direccion",
    "recibi",
    "vuelto",
    "iva",
    "telefono",
    "tarjeta",
    "efectivo",
)
STRUCTURAL_PATTERNS = {token: re.compile(r"\b" + re.escape(token) + r"\b") for token in STRUCTURAL_TOKENS}
NON_ITEM_PATTERN = re.compile(r"\b(?:" + "|".join(re.escape(token) for token in NON_ITEM_TOKENS) + r")\b")
MARKET_UI_PATTERN = re.compile(r"\b(?:entregad[oa]|compensacion|te acreditamos|repetir pedido|ir al local)\b")
DELIVERY_DATE_PATTERN = re.compile(
    r"\bentregad[oa]\b.*?(?<!\w)[0-9o]{1,2}\s+(?:de\s+)?"
    r"(?:ene(?:ro)?|feb(?:rero)?|mar(?:zo)?|abr(?:il)?|may(?:o)?|jun(?:io)?|jul(?:io)?|ago(?:sto)?|"
    r"sep(?:t(?:iembre)?)?|set(?:iembre)?|oct(?:ubre)?|nov(?:iembre)?|dic(?:iembre)?)\b"
)
MIN_EVIDENCE_CONFIDENCE = 0.5


def score_lines(lines):
    if not lines:
        return -1

    candidates = [line for line in lines if str(line.get("text", "")).strip()]
    if not candidates:
        return -1

    candidates = unique_geometric_lines(candidates)
    evidence = [
        (str(line["text"]).strip().lower(), evidence_weight(line_confidence(line)))
        for line in candidates
    ]
    average_confidence = sum(line_confidence(line) for line in candidates) / len(candidates)

    score = strongest_evidence((weight for _, weight in evidence), 45) * 3
    score += average_confidence * 24
    score += strongest_evidence((weight for text, weight in evidence if PRICE_PATTERN.search(text)), 35) * 9
    score += strongest_evidence((weight for text, weight in evidence if looks_like_item_line(text)), 30) * 14
    structural_weights = [
        max((weight for text, weight in evidence if pattern.search(text)), default=0)
        for pattern in STRUCTURAL_PATTERNS.values()
    ]
    score += min(sum(structural_weights), 6) * 5
    # A tiny Market header is useful evidence even when a variant reads the
    # same products. Cap this bonus so repeated UI text cannot dominate items.
    score += max((weight for text, weight in evidence
                  if DELIVERY_DATE_PATTERN.search(normalize_text(text))), default=0) * 8
    score += strongest_evidence((weight for text, weight in evidence if any(char.isdigit() for char in text)), 40) * 2
    return score


def strongest_evidence(weights, limit):
    # Limit the number of supporting rows before weighting. Otherwise hundreds
    # of weak detections can accumulate the same evidence as reliable products.
    return sum(sorted(weights, reverse=True)[:limit])


def line_confidence(line):
    value = line.get("confidence", line.get("score", 0.5))
    if value is None:
        return 0.5
    try:
        confidence = float(value)
    except (TypeError, ValueError):
        return 0.0
    return max(0.0, min(1.0, confidence)) if math.isfinite(confidence) else 0.0


def evidence_weight(confidence):
    # Many uncertain fragments should not outweigh fewer confidently read rows.
    normalized = max(0.0, confidence - MIN_EVIDENCE_CONFIDENCE) / (1 - MIN_EVIDENCE_CONFIDENCE)
    return normalized * normalized


def unique_geometric_lines(lines):
    retained = []
    for line in sorted(lines, key=line_confidence, reverse=True):
        text = " ".join(str(line["text"]).lower().split())
        if any(
            text == " ".join(str(other["text"]).lower().split())
            and bounds_overlap(line, other) >= 0.8
            for other in retained
        ):
            continue
        retained.append(line)
    return retained


def bounds_overlap(first, second):
    try:
        first_box = [float(first[key]) for key in ("left", "top", "right", "bottom")]
        second_box = [float(second[key]) for key in ("left", "top", "right", "bottom")]
    except (KeyError, TypeError, ValueError):
        return 0.0
    if not all(math.isfinite(value) for value in first_box + second_box):
        return 0.0
    left, top, right, bottom = first_box
    other_left, other_top, other_right, other_bottom = second_box
    area = max(0, right - left) * max(0, bottom - top)
    other_area = max(0, other_right - other_left) * max(0, other_bottom - other_top)
    intersection = max(0, min(right, other_right) - max(left, other_left)) * max(0, min(bottom, other_bottom) - max(top, other_top))
    union = area + other_area - intersection
    return intersection / union if union > 0 else 0.0


def looks_like_item_line(text):
    compact = str(text).strip().lower()
    if len(compact) < 8:
        return False
    if NON_ITEM_PATTERN.search(compact):
        return False
    if MARKET_UI_PATTERN.search(normalize_text(compact)):
        return False
    has_letters = any(char.isalpha() for char in compact)
    has_price = bool(PRICE_PATTERN.search(compact))
    return has_letters and has_price


def normalize_text(text):
    return "".join(char for char in unicodedata.normalize("NFD", text.lower())
                   if not unicodedata.combining(char))
