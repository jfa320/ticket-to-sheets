import re


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


def score_lines(lines):
    if not lines:
        return -1

    texts = [str(line.get("text", "")).strip() for line in lines if str(line.get("text", "")).strip()]
    if not texts:
        return -1

    confidence_values = [
        float(line.get("confidence", line.get("score", 0.0)) or 0.0)
        for line in lines
        if line.get("confidence", line.get("score", 0.0)) is not None
    ]
    average_confidence = sum(confidence_values) / len(confidence_values) if confidence_values else 0.5
    normalized_text = "\n".join(texts).lower()

    score = min(len(texts), 45) * 3
    score += max(0.0, min(1.0, average_confidence)) * 24
    score += min(sum(1 for text in texts if PRICE_PATTERN.search(text)), 35) * 9
    score += min(sum(1 for text in texts if looks_like_item_line(text)), 30) * 14
    score += min(sum(1 for token in STRUCTURAL_TOKENS if token in normalized_text), 6) * 5
    score += min(sum(1 for text in texts if any(char.isdigit() for char in text)), 40) * 2
    return score


def looks_like_item_line(text):
    compact = str(text).strip().lower()
    if len(compact) < 8:
        return False
    if any(token in compact for token in NON_ITEM_TOKENS):
        return False
    has_letters = any(char.isalpha() for char in compact)
    has_price = bool(PRICE_PATTERN.search(compact))
    return has_letters and has_price
