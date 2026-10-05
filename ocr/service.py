import json
import hashlib
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
import paddle
from paddleocr import PaddleOCR
from PIL import Image, ImageDraw, ImageOps

from image_variants import build_variants
from ocr_regions import recognize_regions
from receipt_layout import merge_boxes_into_rows
from scoring import score_lines
from targeted_retry import refine_detections
from variant_fusion import fuse_detections
from preview import build_preview
from model_paths import model_path_options

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
OCR_MAX_REGION_SIDE = 1400
logging.basicConfig(level=logging.INFO)


def debug_enabled():
    value = os.environ.get("APP_OCR_DEBUG", os.environ.get("OCR_DEBUG", "false"))
    return value.strip().lower() in {"1", "true", "yes", "on"}


def image_corrections_enabled():
    value = os.environ.get("OCR_IMAGE_CORRECTIONS", "true")
    return value.strip().lower() in {"1", "true", "yes", "on"}


def targeted_retry_enabled():
    value = os.environ.get("OCR_TARGETED_RETRY", "true")
    return value.strip().lower() in {"1", "true", "yes", "on"}


def variant_fusion_enabled():
    return os.environ.get("OCR_VARIANT_FUSION", "true").strip().lower() in {"1", "true", "yes", "on"}


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
            model_options = model_path_options(language)
            try:
                OCR_CACHE[language] = PaddleOCR(
                    use_angle_cls=False,
                    lang=language,
                    show_log=False,
                    enable_mkldnn=False,
                    use_mkldnn=False,
                    ir_optim=False,
                    cpu_threads=1,
                    det_limit_side_len=OCR_MAX_REGION_SIDE,
                    **model_options,
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
                    det_limit_side_len=OCR_MAX_REGION_SIDE,
                    **model_options,
                )
            app.logger.info("PaddleOCR model for language '%s' loaded", language)
    return OCR_CACHE[language]


def purge_corrupted_paddle_cache():
    app.logger.warning("PaddleOCR cache is corrupted. Purging downloaded model archives and retrying.")
    cache_roots = {
        os.path.join(os.path.expanduser("~"), ".paddleocr"),
        os.path.join(os.environ.get("USERPROFILE", ""), ".paddleocr"),
        "/root/.paddleocr",
    }
    for cache_root in cache_roots:
        if not cache_root or not os.path.isdir(cache_root):
            continue
        for path in glob.glob(os.path.join(cache_root, "whl", "**", "*.tar"), recursive=True):
            try:
                os.remove(path)
            except OSError as exc:
                app.logger.warning("Could not remove corrupted archive %s: %s", path, exc)

        for path in glob.glob(os.path.join(cache_root, "whl", "**", "__MACOSX"), recursive=True):
            shutil.rmtree(path, ignore_errors=True)

    # PaddleOCR can leave an incomplete model directory after a failed download.
    # Remove only directories that do not contain a usable inference model.
    for cache_root in cache_roots:
        whl_root = os.path.join(cache_root, "whl")
        if not os.path.isdir(whl_root):
            continue
        for model_dir in glob.glob(os.path.join(whl_root, "**", "*_infer"), recursive=True):
            if not os.path.isfile(os.path.join(model_dir, "inference.pdmodel")):
                shutil.rmtree(model_dir, ignore_errors=True)


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


def recognize_block(ocr, image):
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as temp_file:
            image.save(temp_file, format="PNG")
            temp_path = temp_file.name
        result = ocr.ocr(temp_path, cls=False)
        return extract_detections(result)
    finally:
        if temp_path and os.path.exists(temp_path):
            os.remove(temp_path)


def ocr_image(ocr, image):
    detections = recognize_regions(
        image,
        lambda region: recognize_block(ocr, region),
        max_side=OCR_MAX_REGION_SIDE,
    )
    return detections, merge_boxes_into_rows(detections)


def run_best_ocr(image, language, debug_dir=None, preview_sink=None):
    ocr = get_ocr(language)
    best_name = "original"
    best_lines = []
    best_detections = []
    best_variant = None
    best_score = -1
    variant_summaries = []
    evidence = []
    best_metadata = None
    fusion_enabled = variant_fusion_enabled()

    for entry in build_variants(
        image, debug_dir, save_debug_image, document_corrections=image_corrections_enabled(), with_metadata=True
    ):
        name, variant = entry[:2]
        metadata = dict(entry[2]) if len(entry) > 2 else None
        save_debug_image(debug_dir, f"variants/{name}.png", variant)
        try:
            detections, lines = ocr_image(ocr, variant)
        except Exception as exc:
            app.logger.warning("OCR variant '%s' failed: %s", name, exc)
            variant_summaries.append({"variant": name, "error": str(exc)})
            continue
        score = score_lines(lines)
        if fusion_enabled and metadata:
            digest = hashlib.sha256(variant.convert("L").tobytes()).hexdigest()
            metadata["fingerprint"] = (variant.size, digest)
            evidence.append({"metadata": metadata, "detections": detections})
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
            best_metadata = metadata

    if best_score < 0:
        raise RuntimeError("PaddleOCR fallo en todas las variantes de imagen. Revisa tamaño/formato de la foto.")

    fusion_summary = {"enabled": fusion_enabled}
    if fusion_enabled:
        try:
            fused, statistics = fuse_detections(best_detections, best_metadata, evidence)
            fusion_summary.update(statistics)
            if statistics["acceptedRegions"]:
                fused_lines = merge_boxes_into_rows(fused)
                fused_score = score_lines(fused_lines)
                best_detections, best_lines, best_score = fused, fused_lines, fused_score
                best_name += "+fusion"
        except Exception:
            fusion_summary["error"] = "variant_fusion_failed"
            app.logger.warning("Variant fusion failed; keeping the selected reading")

    retry_summary = {"enabled": targeted_retry_enabled()}
    if retry_summary["enabled"] and best_variant is not None:
        try:
            refined, statistics = refine_detections(
                best_variant, best_detections, lambda region: recognize_block(ocr, region)
            )
            retry_summary.update(statistics)
            if statistics["acceptedRegions"]:
                # Keep boxes in the selected variant's coordinate system.
                # Rebuild every derived field before serializing/debugging.
                refined_lines = merge_boxes_into_rows(refined)
                refined_score = score_lines(refined_lines)
                best_detections = refined
                best_lines = refined_lines
                best_score = refined_score
                best_name += "+retry"
            if statistics["attemptedRegions"]:
                app.logger.info("Targeted OCR reread: %d regions, %d accepted, %d calls, %d failed calls",
                                statistics["attemptedRegions"], statistics["acceptedRegions"],
                                statistics["ocrCalls"], statistics["failedCalls"])
        except Exception:
            retry_summary["error"] = "targeted_retry_failed"
            app.logger.warning("Targeted OCR reread failed; keeping the selected variant result")

    preview = " | ".join(line["text"] for line in best_lines[:12])
    app.logger.info("Selected OCR variant '%s' with score %.2f and %d lines: %s", best_name, best_score, len(best_lines), preview)

    if debug_dir:
        if best_variant is not None:
            save_debug_image(debug_dir, "selected.png", best_variant)
            save_debug_image(debug_dir, "detections-overlay.png", draw_overlay(best_variant, best_detections))
        write_debug_json(debug_dir, "variants-summary.json", variant_summaries)
        write_debug_json(debug_dir, "targeted-retry.json", retry_summary)
        write_debug_json(debug_dir, "variant-fusion.json", fusion_summary)
        write_debug_json(debug_dir, "detections.json", {
            "variant": best_name,
            "score": best_score,
            "detections": best_detections,
            "lines": best_lines,
        })

    if preview_sink is not None and best_variant is not None:
        try:
            preview_sink.update(build_preview(best_variant))
        except Exception:
            app.logger.warning("Could not generate OCR preview; text remains available")
    return best_name, best_lines, best_detections, best_score


@app.post("/ocr")
def ocr_endpoint():
    if not request.data:
        return jsonify({"error": "No se recibio imagen"}), 400

    language = request.headers.get("X-OCR-Language", "es")

    try:
        with Image.open(BytesIO(request.data)) as uploaded:
            image = ImageOps.exif_transpose(uploaded).convert("RGB")
        debug_dir = create_debug_dir()
        save_debug_image(debug_dir, "original.png", image)
        preview = {}
        variant_name, lines, detections, score = run_best_ocr(image, language, debug_dir, preview_sink=preview)
        text = "\n".join(line["text"] for line in lines)
        response = {
            "text": text,
            "lines": lines,
            "detections": detections,
            "variant": variant_name,
            "score": score,
            "preview": preview or None,
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
