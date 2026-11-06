"""Una lectura completa acotada; reintenta orientaciones solo si no hay texto útil."""
import time
from PIL import Image
from image_variants import limit_image_size
from receipt_layout import merge_boxes_into_rows
from scoring import score_lines, PRICE_PATTERN, line_confidence
from targeted_retry import refine_detections


def run_fast_reading(image, recognize, recognize_crop, check, budget=8, retry=True, clock=time.perf_counter):
    started = clock(); deadline = started + min(8, budget)
    bounded = image.copy()
    if max(image.size) / min(image.size) > 3:
        bounded = limit_image_size(image, 700, 1_500_000)
    else:
        bounded.thumbnail((1280, 1280), Image.Resampling.LANCZOS)
    best = None; calls = 0; last_duration = 0
    for rotation in (0, 90, 270, 180):
        check()
        if calls and clock() + last_duration * 1.15 >= deadline: break
        candidate = bounded if rotation == 0 else bounded.rotate(rotation, expand=True)
        before = clock(); detections, lines = recognize(candidate)
        last_duration = clock() - before; calls += 1
        score = score_lines(lines)
        if best is None or score > best[0]: best = (score, rotation, candidate, detections, lines)
        confidence = sum(line_confidence(row) for row in lines) / max(1, len(lines))
        if len(lines) >= 3 and confidence >= .75 and any(PRICE_PATTERN.search(row['text']) for row in lines): break
    check()
    score, rotation, selected, detections, lines = best
    crop_duration = [0.0]
    def continue_retry():
        check(); return clock() + max(2.0, crop_duration[0] * 1.35) < deadline
    def bounded_crop(candidate):
        before = clock()
        try: return recognize_crop(candidate)
        finally: crop_duration[0] = clock() - before
    # Marcar importes no confirmados aun cuando no quede tiempo para releerlos.
    refined, statistics = refine_detections(selected, detections, bounded_crop,
        max_regions=1 if retry and clock() + 4.0 < deadline else 0,
        should_continue=continue_retry,
        detail_image=image if rotation == 0 else image.rotate(rotation, expand=True))
    if refined != detections: detections = refined; lines = merge_boxes_into_rows(refined); score = score_lines(lines)
    check()
    return (f'fast-{rotation}', lines, detections, score), selected, {
        'durationMs':round((clock()-started)*1000), 'fullPasses':calls, 'rereadCalls':statistics['ocrCalls']}
