"""Combine corroborated OCR readings without mixing coordinate frames."""

import math
from targeted_retry import preserves_content


def confidence(item):
    value = item.get("confidence")
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return value if math.isfinite(value) and 0 <= value <= 1 else None


def bounds(item):
    values = [item.get(key) for key in ("left", "top", "right", "bottom")]
    if not all(isinstance(value, (int, float)) and math.isfinite(value) for value in values):
        return None
    return values if values[2] > values[0] and values[3] > values[1] else None


def overlap(a, b):
    return max(0, min(a[2], b[2]) - max(a[0], b[0])), max(0, min(a[3], b[3]) - max(a[1], b[1]))


def reading_for_box(original, others, scale, neighbors):
    target = bounds(original)
    pieces = []
    for item in others:
        box, certainty = bounds(item), confidence(item)
        if box is None or certainty is None or certainty < max(0.85, confidence(original) + 0.1 - 1e-9):
            continue
        box = [box[0] * scale[0], box[1] * scale[1], box[2] * scale[0], box[3] * scale[1]]
        width, height = overlap(target, box)
        # Candidates must belong to this row and stay predominantly inside it.
        if height < 0.8 * (target[3] - target[1]) or width * height < 0.8 * (box[2] - box[0]) * (box[3] - box[1]):
            continue
        if any(overlap(box, neighbor)[0] * overlap(box, neighbor)[1] >
               0.1 * (neighbor[2] - neighbor[0]) * (neighbor[3] - neighbor[1]) for neighbor in neighbors):
            continue
        text = " ".join(str(item.get("text", "")).split())
        if text:
            pieces.append((box, text, certainty))
    pieces.sort(key=lambda piece: piece[0][0])
    if not pieces:
        return None
    # Overlapping alternative detections are ambiguous, not two independent votes.
    for index, (box, _, _) in enumerate(pieces):
        if any(overlap(box, previous[0])[0] > 0.1 * min(box[2] - box[0], previous[0][2] - previous[0][0])
               for previous in pieces[:index]):
            return None
    union = [min(p[0][0] for p in pieces), min(p[0][1] for p in pieces),
             max(p[0][2] for p in pieces), max(p[0][3] for p in pieces)]
    width, height = overlap(target, union)
    text = " ".join(piece[1] for piece in pieces)
    if width < 0.8 * (target[2] - target[0]) or height < 0.8 * (target[3] - target[1]):
        return None
    if not preserves_content(str(original["text"]), text):
        return None
    return text, min(piece[2] for piece in pieces)


def fuse_detections(detections, metadata, alternatives):
    """Keep base boxes; replace weak text only with two distinct agreeing images.

    Metadata explicitly identifies a common canvas and its raster size. Crops,
    rotations and perspective transforms never share a frame. Size alone is not
    evidence of alignment. Conflicting readings keep the original, even if a
    majority agrees. No extra boxes or entirely missing rows are introduced.
    """
    updated = list(detections)
    summary = {"acceptedRegions": 0, "conflictingRegions": 0}
    if not metadata or not metadata.get("frame"):
        return updated, summary
    base_width, base_height = metadata["size"]
    base_fingerprint = metadata.get("fingerprint")
    for index, original in enumerate(detections):
        certainty, target = confidence(original), bounds(original)
        if certainty is None or certainty >= 0.8 or target is None:
            continue
        neighbors = [bounds(item) for pos, item in enumerate(detections) if pos != index and bounds(item)]
        votes = {}
        seen = {base_fingerprint}
        for alternative in alternatives:
            other_meta = alternative["metadata"]
            fingerprint = other_meta.get("fingerprint")
            if other_meta.get("frame") != metadata["frame"] or not fingerprint or fingerprint in seen:
                continue
            seen.add(fingerprint)
            width, height = other_meta["size"]
            reading = reading_for_box(original, alternative["detections"],
                                      (base_width / width, base_height / height), neighbors)
            if reading:
                text, quality = reading
                votes.setdefault(text.upper(), []).append((text, quality))
        if len(votes) > 1:
            summary["conflictingRegions"] += 1
            continue
        if len(votes) != 1:
            continue
        support = next(iter(votes.values()))
        if len(support) < 2:
            continue
        text = support[0][0]
        if text.upper() == " ".join(str(original["text"]).split()).upper():
            continue
        certainty = min(item[1] for item in support)
        updated[index] = dict(original, text=text, confidence=certainty, score=certainty)
        summary["acceptedRegions"] += 1
    return updated, summary
