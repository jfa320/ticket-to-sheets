"""Spend OCR passes on readable orientations and stop on consistent full readings."""

import re
from collections import Counter

from scoring import PRICE_PATTERN, line_confidence, looks_like_item_line, normalize_text, unique_geometric_lines


DATE_PATTERN = re.compile(r"(?<!\d)\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}(?!\d)")
TOTAL_PATTERN = re.compile(r"\btotal\b")


def reliable_signature(lines):
    """Require products, a date and a footer; confidence alone is insufficient.

    Preserve repeated product rows. This only controls search effort, without
    deriving monetary values or changing the parser's calculated total.
    """
    rows = unique_geometric_lines([line for line in lines if str(line.get("text", "")).strip()])
    products = [line for line in rows if looks_like_item_line(normalize_text(line["text"]))]
    if len(rows) < 6 or len(products) < 2:
        return None
    if any(PRICE_PATTERN.fullmatch(str(line["text"]).strip()) for line in rows):
        # A detached price may belong to an omitted or misaligned product.
        return None
    if min(line_confidence(line) for line in products) < 0.85:
        return None
    if sum(line_confidence(line) for line in products) / len(products) < 0.93:
        return None
    dates = []
    totals = []
    for line in rows:
        if line_confidence(line) < 0.85:
            continue
        text = normalize_text(line["text"])
        dates.extend(DATE_PATTERN.findall(text))
        if TOTAL_PATTERN.search(text) and PRICE_PATTERN.search(text):
            totals.append(" ".join(text.split()))
    if not dates or not totals:
        return None
    return (tuple(sorted(" ".join(normalize_text(line["text"]).split()) for line in products)),
            tuple(sorted(dates)), tuple(sorted(totals)))


class AdaptiveSearch:
    def __init__(self):
        self.rotation = 0
        self.scores = {}
        self.signatures = Counter()
        self.fingerprints = set()
        self.readable_orientation = False
        self.complete = False

    def observe(self, lines, score, metadata):
        self.scores[self.rotation] = max(score, self.scores.get(self.rotation, -1))
        signature = reliable_signature(lines)
        self.readable_orientation = signature is not None
        if not signature or not metadata or not str(metadata.get("frame", "")).startswith(("full:", "geometry:")):
            return
        fingerprint = metadata.get("fingerprint")
        if fingerprint is None or fingerprint in self.fingerprints:
            return
        self.fingerprints.add(fingerprint)
        self.signatures[signature] += 1
        self.complete = self.signatures[signature] >= 3

    def variants(self, factory):
        pending = {}
        try:
            # Probe orientation before spending passes on contrast and thresholds.
            for rotation in (0, 90, 270, 180):
                self.rotation = rotation
                candidates = iter(factory(rotation))
                pending[rotation] = candidates
                first = next(candidates, None)
                if first is not None:
                    yield first
                    if self.readable_orientation:
                        break
            ranked = sorted(pending, key=lambda rotation: self.scores.get(rotation, -1), reverse=True)
            for rotation in ranked + [r for r in (0, 90, 270, 180) if r not in pending]:
                self.rotation = rotation
                if rotation not in pending:
                    pending[rotation] = iter(factory(rotation))
                yield from pending[rotation]
        finally:
            for candidates in pending.values():
                close = getattr(candidates, "close", None)
                if close:
                    close()
