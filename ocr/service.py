import json
from io import BytesIO
import glob
import logging
import os
import shutil
import tarfile
import tempfile
import time
import traceback
import threading
from datetime import datetime

os.environ.setdefault("FLAGS_use_mkldnn", "0")
os.environ.setdefault("FLAGS_use_onednn", "0")
os.environ.setdefault("OMP_NUM_THREADS", "1")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "1")
os.environ.setdefault("MKL_NUM_THREADS", "1")
os.environ.setdefault("NUMEXPR_NUM_THREADS", "1")

from flask import Flask, jsonify, request
import numpy as np
import paddle
from paddleocr import PaddleOCR
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

from preprocess import remove_physical_lines

try:
    paddle.set_flags({"FLAGS_use_mkldnn": False})
except Exception:
    pass

try:
    paddle.set_flags({"FLAGS_use_onednn": False})
except Exception:
    pass


app = Flask(__name__)
OCR_CACHE = {}
OCR_LOCK = threading.Lock()
DEFAULT_LANGUAGE = os.environ.get("OCR_DEFAULT_LANGUAGE", "es")
logging.basicConfig(level=logging.INFO)


def debug_enabled():
    value = os.environ.get("APP_OCR_DEBUG", os.environ.get("OCR_DEBUG", "false"))
    return value.strip().lower() in {"1", "true", "yes", "on"}


def create_debug_dir():
    if not debug_enabled():
        return None

    root = os.environ.get("OCR_DEBUG_DIR", "debug")
    run_name = "run-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    path = os.path.join(root, run_name)
    os.makedirs(os.path.join(path, "variants"), exist_ok=True)
    return path


def write_debug_json(debug_dir, name, payload):
    if not debug_dir:
        return
    with open(os.path.join(debug_dir, name), "w", encoding="utf-8") as file:
        json.dump(payload, file, ensure_ascii=False, indent=2)


def save_debug_image(debug_dir, relative_path, image):
    if not debug_dir:
        return
    path = os.path.join(debug_dir, relative_path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, format="PNG")


def get_ocr(language):
    with OCR_LOCK:
        if language not in OCR_CACHE:
            app.logger.info("Loading PaddleOCR model for language '%s'", language)
            try:
                OCR_CACHE[language] = PaddleOCR(
                    use_angle_cls=False,
                    lang=language,
                    show_log=False,
                    enable_mkldnn=False,
                    use_mkldnn=False,
                    ir_optim=False,
                    cpu_threads=1,
                )
            except Exception:
                purge_corrupted_paddle_cache()
                OCR_CACHE[language] = PaddleOCR(
                    use_angle_cls=False,
                    lang=language,
                    show_log=False,
                    enable_mkldnn=False,
                    use_mkldnn=False,
                    ir_optim=False,
                    cpu_threads=1,
                )
            app.logger.info("PaddleOCR model for language '%s' loaded", language)
    return OCR_CACHE[language]


def purge_corrupted_paddle_cache():
    app.logger.warning("PaddleOCR cache is corrupted. Purging downloaded model archives and retrying.")
    for path in glob.glob("/root/.paddleocr/whl/**/*.tar", recursive=True):
        try:
            os.remove(path)
        except OSError as exc:
            app.logger.warning("Could not remove corrupted archive %s: %s", path, exc)

    for path in glob.glob("/root/.paddleocr/whl/**/__MACOSX", recursive=True):
        shutil.rmtree(path, ignore_errors=True)


def warmup_default_ocr():
    while DEFAULT_LANGUAGE not in OCR_CACHE:
        try:
            get_ocr(DEFAULT_LANGUAGE)
        except Exception as exc:
            app.logger.error("OCR warmup failed, retrying in 10 seconds: %s\n%s", exc, traceback.format_exc())
            purge_corrupted_paddle_cache()
            time.sleep(10)


def extract_detections(result):
    detections = []
    if not result:
        return []

    for page in result:
        if not page:
            continue
        for entry in page:
            if not entry or len(entry) < 2:
                continue

            box = entry[0]
            data = entry[1]
            text = ""
            score = 0.0

            if isinstance(data, (list, tuple)) and len(data) >= 2:
                text = str(data[0]).strip()
                try:
                    score = float(data[1])
                except Exception:
                    score = 0.0
            else:
                text = str(data).strip()

            if not text:
                continue

            points = [[float(point[0]), float(point[1])] for point in box]
            top = min(point[1] for point in box)
            bottom = max(point[1] for point in box)
            left = min(point[0] for point in box)
            right = max(point[0] for point in box)
            height = max(bottom - top, 1)
            width = max(right - left, 1)
            detections.append({
                "text": text,
                "confidence": score,
                "score": score,
                "box": points,
                "top": float(top),
                "left": float(left),
                "right": float(right),
                "bottom": float(bottom),
                "width": float(width),
                "height": float(height),
            })

    return detections


def extract_lines(result):
    return merge_boxes_into_rows(extract_detections(result))


def merge_boxes_into_rows(boxes):
    rows = []
    indexed_boxes = [dict(item, detectionIndex=index) for index, item in enumerate(boxes)]
    indexed_boxes.sort(key=lambda item: (item["top"], item["left"]))

    for box in indexed_boxes:
        center = box["top"] + box["height"] / 2
        matching_row = None

        for row in rows:
            threshold = max(18, min(row["height"], box["height"]) * 0.45)
            if abs(center - row["center"]) <= threshold:
                matching_row = row
                break

        if matching_row is None:
            rows.append({
                "center": center,
                "height": box["height"],
                "boxes": [box],
            })
        else:
            matching_row["boxes"].append(box)
            matching_row["center"] = sum(item["top"] + item["height"] / 2 for item in matching_row["boxes"]) / len(matching_row["boxes"])
            matching_row["height"] = max(matching_row["height"], box["height"])

    lines = []
    for row in rows:
        row_boxes = sorted(row["boxes"], key=lambda item: item["left"])
        text = " ".join(item["text"] for item in row_boxes).strip()
        lines.append({
            "text": text,
            "score": min(item["confidence"] for item in row_boxes),
            "confidence": min(item["confidence"] for item in row_boxes),
            "top": min(item["top"] for item in row_boxes),
            "left": min(item["left"] for item in row_boxes),
            "right": max(item["right"] for item in row_boxes),
            "bottom": max(item["bottom"] for item in row_boxes),
            "width": max(item["right"] for item in row_boxes) - min(item["left"] for item in row_boxes),
            "height": max(item["bottom"] for item in row_boxes) - min(item["top"] for item in row_boxes),
            "detectionIndexes": [item["detectionIndex"] for item in row_boxes],
        })

    lines.sort(key=lambda item: (item["top"], item["left"]))
    return lines


def draw_overlay(image, detections):
    overlay = image.convert("RGB").copy()
    draw = ImageDraw.Draw(overlay)

    for index, detection in enumerate(detections):
        points = [(point[0], point[1]) for point in detection["box"]]
        if len(points) >= 4:
            draw.line(points + [points[0]], fill=(255, 0, 0), width=3)

        label_text = detection["text"][:32]
        label = f"{index} {detection['confidence']:.2f} {label_text}"
        x = detection["left"]
        y = max(0, detection["top"] - 18)
        text_bbox = draw.textbbox((x, y), label)
        draw.rectangle(text_bbox, fill=(255, 255, 255))
        draw.text((x, y), label, fill=(255, 0, 0))

    return overlay


def crop_receipt_region(image):
    gray = ImageOps.grayscale(image)
    gray = ImageOps.autocontrast(gray)
    array = np.array(gray)

    mask = array > 165
    row_hits = np.where(mask.mean(axis=1) > 0.45)[0]
    col_hits = np.where(mask.mean(axis=0) > 0.45)[0]

    if len(row_hits) < 40 or len(col_hits) < 40:
        return image

    top = max(int(row_hits[0]) - 25, 0)
    bottom = min(int(row_hits[-1]) + 25, image.height)
    left = max(int(col_hits[0]) - 25, 0)
    right = min(int(col_hits[-1]) + 25, image.width)

    if right - left < image.width * 0.22 or bottom - top < image.height * 0.35:
        return image

    return image.crop((left, top, right, bottom))


def build_variants(image, debug_dir=None):
    variants = []
    source = image.convert("RGB")

    for rotation in (0, 90, 270, 180):
        oriented = source if rotation == 0 else source.rotate(rotation, expand=True)
        base = crop_receipt_region(oriented)
        base = limit_image_size(base, 1400)
        suffix = "original" if rotation == 0 else f"rot{rotation}"

        variants.append((f"cropped-{suffix}", base))

        gray = ImageOps.grayscale(base)
        variants.append((f"gray-{suffix}", gray))

        without_lines = remove_physical_lines(gray)
        if without_lines is not None:
            cleaned, line_mask = without_lines
            variants.append((f"without-lines-{suffix}", cleaned))
            save_debug_image(debug_dir, f"variants/without-lines-{suffix}-mask.png", line_mask)

        contrast = ImageEnhance.Contrast(gray).enhance(2.3)
        sharp = ImageEnhance.Sharpness(contrast).enhance(2.0)
        enlarged = limit_image_size(sharp.resize((sharp.width * 2, sharp.height * 2), Image.Resampling.LANCZOS), 1800)
        variants.append((f"enhanced-{suffix}", enlarged))

        threshold = enlarged.point(lambda pixel: 255 if pixel > 172 else 0, mode="1").convert("L")
        variants.append((f"threshold-{suffix}", threshold))

        denoised = enlarged.filter(ImageFilter.MedianFilter(size=3))
        variants.append((f"denoised-{suffix}", denoised))

        light_threshold = denoised.point(lambda pixel: 255 if pixel > 150 else 0, mode="1").convert("L")
        variants.append((f"light-threshold-{suffix}", light_threshold))

        small = limit_image_size(base, 900)
        variants.append((f"small-{suffix}", small))
        variants.append((f"small-gray-{suffix}", ImageOps.grayscale(small)))

    return variants


def limit_image_size(image, max_side=2200):
    largest_side = max(image.width, image.height)
    if largest_side <= max_side:
        return image

    scale = max_side / largest_side
    new_size = (max(1, int(image.width * scale)), max(1, int(image.height * scale)))
    return image.resize(new_size, Image.Resampling.LANCZOS)


def score_lines(lines):
    if not lines:
        return -1

    text = "\n".join(line["text"] for line in lines)
    lower = text.lower()
    score = len(lines) * 4 + sum(len(line["text"]) for line in lines) / 30

    for token in ["fecha", "total", "subtotal", "supermercado", "corazones", "consumidor"]:
        if token in lower:
            score += 8

    for token in ["serenisima", "elegante", "higienol", "frutigran", "union", "vocacion", "papel", "salchich"]:
        if token in lower:
            score += 10
    if any(char.isdigit() for char in text):
        score += 6

    price_lines = sum(1 for line in lines if any(ch.isdigit() for ch in line["text"]) and "," in line["text"])
    score += price_lines * 8

    item_lines = sum(1 for line in lines if looks_like_item_line(line["text"]))
    score += item_lines * 14

    return score


def looks_like_item_line(text):
    compact = text.strip().lower()
    if len(compact) < 8:
        return False
    if any(token in compact for token in ["fecha", "hora", "total", "subtotal", "cuit", "direccion", "recibi", "vuelto"]):
        return False
    has_letters = any(char.isalpha() for char in compact)
    has_price = any(char.isdigit() for char in compact) and ("," in compact or "." in compact)
    return has_letters and has_price


def ocr_image(ocr, image):
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as temp_file:
            image.save(temp_file, format="PNG")
            temp_path = temp_file.name
        result = ocr.ocr(temp_path, cls=False)
        detections = extract_detections(result)
        return detections, merge_boxes_into_rows(detections)
    finally:
        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)


def run_best_ocr(image, language, debug_dir=None):
    ocr = get_ocr(language)
    best_name = "original"
    best_lines = []
    best_detections = []
    best_variant = None
    best_score = -1
    variant_summaries = []

    for name, variant in build_variants(image, debug_dir):
        save_debug_image(debug_dir, f"variants/{name}.png", variant)
        try:
            detections, lines = ocr_image(ocr, variant)
        except Exception as exc:
            app.logger.warning("OCR variant '%s' failed: %s", name, exc)
            variant_summaries.append({"variant": name, "error": str(exc)})
            continue
        score = score_lines(lines)
        variant_summaries.append({
            "variant": name,
            "score": score,
            "lineCount": len(lines),
            "detectionCount": len(detections),
            "preview": " | ".join(line["text"] for line in lines[:8]),
        })
        write_debug_json(debug_dir, f"variants/{name}.json", {
            "variant": name,
            "score": score,
            "detections": detections,
            "lines": lines,
        })
        if score > best_score:
            best_score = score
            best_lines = lines
            best_detections = detections
            best_name = name
            best_variant = variant

    if best_score < 0:
        raise RuntimeError("PaddleOCR fallo en todas las variantes de imagen. Revisa tamaño/formato de la foto.")

    preview = " | ".join(line["text"] for line in best_lines[:12])
    app.logger.info("Selected OCR variant '%s' with score %.2f and %d lines: %s", best_name, best_score, len(best_lines), preview)

    if debug_dir:
        if best_variant is not None:
            save_debug_image(debug_dir, "selected.png", best_variant)
            save_debug_image(debug_dir, "detections-overlay.png", draw_overlay(best_variant, best_detections))
        write_debug_json(debug_dir, "variants-summary.json", variant_summaries)
        write_debug_json(debug_dir, "detections.json", {
            "variant": best_name,
            "score": best_score,
            "detections": best_detections,
            "lines": best_lines,
        })

    return best_name, best_lines, best_detections, best_score


@app.post("/ocr")
def ocr_endpoint():
    if not request.data:
        return jsonify({"error": "No se recibio imagen"}), 400

    language = request.headers.get("X-OCR-Language", "es")

    try:
        image = Image.open(BytesIO(request.data)).convert("RGB")
        debug_dir = create_debug_dir()
        save_debug_image(debug_dir, "original.png", image)
        variant_name, lines, detections, score = run_best_ocr(image, language, debug_dir)
        text = "\n".join(line["text"] for line in lines)
        response = {
            "text": text,
            "lines": lines,
            "detections": detections,
            "variant": variant_name,
            "score": score,
        }
        if debug_dir:
            response["debugDir"] = debug_dir
            write_debug_json(debug_dir, "ocr-response.json", response)
        return app.response_class(
            response=json.dumps(response, ensure_ascii=False),
            status=200,
            mimetype="application/json",
        )
    except Exception as exc:
        app.logger.error("OCR request failed: %s\n%s", exc, traceback.format_exc())
        return jsonify({"error": str(exc)}), 500


@app.get("/health")
def health_endpoint():
    return jsonify({"status": "ok", "ocrReady": DEFAULT_LANGUAGE in OCR_CACHE}), 200


if __name__ == "__main__":
    threading.Thread(target=warmup_default_ocr, daemon=True).start()
    app.run(host="0.0.0.0", port=5000)
