import json
import hashlib
from io import BytesIO
import glob
import logging
import math
import os
import shutil
import tarfile
import tempfile
import time
import traceback
import threading
import re
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
from PIL import Image, ImageDraw, ImageOps, ImageFont

from image_variants import build_variants
from ocr_regions import recognize_regions
from receipt_layout import merge_boxes_into_rows
from scoring import score_lines
from targeted_retry import refine_detections
from variant_fusion import fuse_detections
from preview import build_preview
from model_paths import model_path_options
from search_policy import AdaptiveSearch
from fast_reading import run_fast_reading

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
OCR_PROCESSING_LOCK = threading.Lock()
OCR_REQUEST_CONDITION = threading.Condition()
OCR_ACTIVE_REQUEST = None
OCR_CANCELLED_REQUESTS = {}
OCR_REQUEST_CONTEXT = threading.local()
DEFAULT_LANGUAGE = os.environ.get("OCR_DEFAULT_LANGUAGE", "es")
OCR_MAX_REGION_SIDE = 1400
logging.basicConfig(level=logging.INFO)
# Paddle's imports may configure the root logger before basicConfig runs.
app.logger.setLevel(logging.INFO)


class OcrCancelled(RuntimeError):
    pass


class OcrDeadlineExceeded(RuntimeError):
    pass


def check_cancelled():
    cancelled = getattr(OCR_REQUEST_CONTEXT, "cancelled", None)
    if cancelled is not None and cancelled.is_set():
        raise OcrCancelled("Se canceló el procesamiento de la factura.")
    deadline = getattr(OCR_REQUEST_CONTEXT, "document_deadline", None)
    if deadline is not None and time.perf_counter() >= deadline:
        raise OcrDeadlineExceeded("El PDF superó el presupuesto total de procesamiento.")


def valid_extraction_id(value):
    return bool(value and re.fullmatch(r"[A-Za-z0-9-]{1,64}", value))


def prune_cancelled_requests():
    oldest = time.monotonic() - 600
    for key, timestamp in list(OCR_CANCELLED_REQUESTS.items()):
        if timestamp < oldest:
            del OCR_CANCELLED_REQUESTS[key]
    while len(OCR_CANCELLED_REQUESTS) >= 256:
        del OCR_CANCELLED_REQUESTS[next(iter(OCR_CANCELLED_REQUESTS))]


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


def adaptive_search_enabled():
    return os.environ.get("OCR_ADAPTIVE_SEARCH", "true").strip().lower() in {"1", "true", "yes", "on"}


def search_budget_seconds():
    override = getattr(OCR_REQUEST_CONTEXT, "budget_seconds", None)
    value = float(override if override is not None else os.environ.get("OCR_SEARCH_BUDGET_SECONDS", "90"))
    if not math.isfinite(value) or value <= 0:
        raise ValueError("OCR_SEARCH_BUDGET_SECONDS debe ser un número positivo y finito")
    deadline = getattr(OCR_REQUEST_CONTEXT, "document_deadline", None)
    if deadline is not None:
        check_cancelled()
        value = min(value, deadline - time.perf_counter())
    return value


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


def warm_ocr(engine):
    """Preparar los operadores con texto ficticio antes de publicar el motor."""
    warm = Image.new('RGB',(960,1280),'white')
    try: font=ImageFont.truetype('arial.ttf',28)
    except OSError: font=ImageFont.load_default()
    draw=ImageDraw.Draw(warm)
    for index,text in enumerate(('COMERCIO FICTICIO','FECHA 29/10/2026','PRODUCTO DE PRUEBA 1200,00','TOTAL 1200,00')):
        draw.text((40,40+index*50),text,font=font,fill='black')
    with tempfile.NamedTemporaryFile(suffix='.png',delete=False) as temporary:
        warm.save(temporary,format='PNG'); warm_path=temporary.name
    try: engine.ocr(warm_path,cls=False)
    finally: os.remove(warm_path); warm.close()


def get_ocr(language):
    with OCR_LOCK:
        if language not in OCR_CACHE:
            app.logger.info("Loading PaddleOCR model for language '%s'", language)
            model_options = model_path_options(language)
            threads = int(os.environ.get('OCR_CPU_THREADS', str(min(4, os.cpu_count() or 1))))
            if not 1 <= threads <= 16: raise ValueError('OCR_CPU_THREADS debe estar entre 1 y 16')
            optimized = os.environ.get('OCR_ENABLE_MKLDNN', 'true').strip().lower() in {'true','1','yes','on'}
            try:
                engine = PaddleOCR(
                    use_angle_cls=False,
                    lang=language,
                    show_log=False,
                    enable_mkldnn=optimized,
                    use_mkldnn=optimized,
                    ir_optim=optimized,
                    cpu_threads=threads,
                    det_limit_side_len=OCR_MAX_REGION_SIDE,
                    **model_options,
                )
                warm_ocr(engine)
            except Exception:
                app.logger.warning('No se pudo cargar el motor optimizado; probando el motor compatible')
                engine = PaddleOCR(
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
                warm_ocr(engine)
            OCR_CACHE[language] = engine
            app.logger.info("PaddleOCR model for language '%s' loaded and warmed", language)
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
    check_cancelled()
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as temp_file:
            image.save(temp_file, format="PNG")
            temp_path = temp_file.name
        result = ocr.ocr(temp_path, cls=False)
        check_cancelled()
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
    check_cancelled()
    ocr = get_ocr(language)
    started = time.perf_counter()
    adaptive = adaptive_search_enabled()
    budget = search_budget_seconds() if adaptive else None
    deadline = started + budget if adaptive else None
    # Leave actual time for the final rereads; previously every difficult photo
    # exhausted the deadline while enumerating variants and skipped this stage.
    retry_reserve = min(12.0, budget * .15) if adaptive and targeted_retry_enabled() else 0.0
    selection_deadline = deadline - retry_reserve if adaptive else None
    search = AdaptiveSearch()
    stop_reason = "exhausted"
    best_name = "original"
    best_lines = []
    best_detections = []
    best_variant = None
    best_score = -1
    variant_summaries = []
    evidence = []
    readings = {}
    ocr_pass_count = 0
    best_metadata = None
    best_full_reading = False
    fusion_enabled = variant_fusion_enabled()

    options = dict(document_corrections=image_corrections_enabled(), with_metadata=True, geometry_cache={})
    if adaptive:
        variants = search.variants(lambda rotation: build_variants(
            image, debug_dir, save_debug_image, rotations=(rotation,), prioritize_corrections=True, probe_orientation=True, **options
        ))
    else:
        variants = build_variants(image, debug_dir, save_debug_image, **options)

    # Close suspended generators even on early exit, releasing intermediate images.
    try:
        for entry in variants:
            check_cancelled()
            if selection_deadline is not None and time.perf_counter() >= selection_deadline:
                stop_reason = "time_budget"
                break
            name, variant = entry[:2]
            metadata = dict(entry[2]) if len(entry) > 2 else None
            save_debug_image(debug_dir, f"variants/{name}.png", variant)
            variant_started = time.perf_counter()
            # Exact pixels imply identical OCR coordinates, even when candidates
            # have different frame labels. Keep their own metadata for fusion.
            cache_key = (variant.size, hashlib.sha256(variant.convert("RGB").tobytes()).hexdigest()) \
                if adaptive and metadata else None
            reused = cache_key is not None and cache_key in readings
            try:
                if reused:
                    detections, lines = readings[cache_key]
                else:
                    ocr_pass_count += 1
                    detections, lines = ocr_image(ocr, variant)
                    if cache_key is not None:
                        readings[cache_key] = (detections, lines)
            except (OcrCancelled, OcrDeadlineExceeded):
                raise
            except Exception as exc:
                app.logger.warning("OCR variant '%s' failed (%s)", name, type(exc).__name__)
                variant_summaries.append({"variant": name, "error": str(exc)})
                continue
            check_cancelled()
            score = score_lines(lines)
            duration_ms = round((time.perf_counter() - variant_started) * 1000)
            app.logger.info("OCR variant '%s': %d ms, %d lines, score %.2f", name, duration_ms, len(lines), score)
            if (fusion_enabled or adaptive) and metadata:
                digest = hashlib.sha256(variant.convert("L").tobytes()).hexdigest()
                metadata["fingerprint"] = (variant.size, digest)
                if fusion_enabled:
                    evidence.append({"metadata": metadata, "detections": detections})
            variant_summaries.append({
                "variant": name,
                "score": score,
                "durationMs": duration_ms,
                "reusedReading": reused,
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
            is_probe = bool(metadata and metadata.get("probe"))
            if score >= 0 and ((not is_probe and not best_full_reading)
                    or ((not is_probe or not best_full_reading) and score > best_score)):
                best_score = score
                best_lines = lines
                best_detections = detections
                best_name = name
                best_variant = variant
                best_metadata = metadata
                best_full_reading = not is_probe
            if adaptive:
                search.observe(lines, score, metadata)
                if search.complete:
                    stop_reason = "consistent_readings"
                    break
                if time.perf_counter() >= selection_deadline:
                    stop_reason = "time_budget"
                    break
    finally:
        close = getattr(variants, "close", None)
        if close:
            close()

    check_cancelled()
    if best_score < 0:
        raise RuntimeError("PaddleOCR fallo en todas las variantes de imagen. Revisa tamaño/formato de la foto.")

    fusion_summary = {"enabled": fusion_enabled}
    if fusion_enabled:
        try:
            fused, statistics = fuse_detections(best_detections, best_metadata, evidence)
            fusion_summary.update(statistics)
            if fused != best_detections:
                best_detections = fused
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
            def should_continue():
                check_cancelled()
                return deadline is None or time.perf_counter() < deadline
            retry_options = {"should_continue": should_continue}
            # Full-frame variants share an exact rotation/scale with the upload.
            # Reread original pixels rather than enlarging an already downscaled
            # image. Cropped/perspective frames retain their own pixels and boxes.
            frame = best_metadata.get("frame", "") if best_metadata else ""
            if frame.startswith("full:"):
                rotation = int(frame.split(":", 1)[1])
                detail = image if rotation == 0 else image.rotate(rotation, expand=True)
                if abs(detail.width / detail.height - best_variant.width / best_variant.height) < .01:
                    retry_options["detail_image"] = detail
            refined, statistics = refine_detections(
                best_variant, best_detections, lambda region: recognize_block(ocr, region), **retry_options
            )
            retry_summary.update(statistics)
            if refined != best_detections:
                best_detections = refined
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
        except (OcrCancelled, OcrDeadlineExceeded):
            raise
        except Exception:
            retry_summary["error"] = "targeted_retry_failed"
            app.logger.warning("Targeted OCR reread failed; keeping the selected variant result")

    check_cancelled()
    app.logger.info("Selected OCR variant '%s' with score %.2f and %d lines", best_name, best_score, len(best_lines))
    if debug_enabled():
        app.logger.debug("OCR preview: %s", " | ".join(line["text"] for line in best_lines[:12]))
    search_summary = {"adaptive": adaptive, "stopReason": stop_reason, "variantCount": len(variant_summaries),
                      "ocrPassCount": ocr_pass_count,
                      "durationMs": round((time.perf_counter() - started) * 1000)}
    app.logger.info("OCR search finished: %s, %d variants, %d ms", stop_reason,
                    search_summary["variantCount"], search_summary["durationMs"])

    if debug_dir:
        if best_variant is not None:
            save_debug_image(debug_dir, "selected.png", best_variant)
            save_debug_image(debug_dir, "detections-overlay.png", draw_overlay(best_variant, best_detections))
        write_debug_json(debug_dir, "variants-summary.json", variant_summaries)
        write_debug_json(debug_dir, "search-summary.json", search_summary)
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


def run_fast_ocr(image, language, debug_dir=None, preview_sink=None):
    check_cancelled(); engine = get_ocr(language)
    result, selected, statistics = run_fast_reading(image,
        lambda candidate: ocr_image(engine, candidate), lambda crop: recognize_block(engine, crop),
        check_cancelled, budget=search_budget_seconds(), retry=targeted_retry_enabled())
    if preview_sink is not None: preview_sink.update(build_preview(selected))
    write_debug_json(debug_dir, 'fast-summary.json', statistics)
    app.logger.info('Lectura rápida: %d ms, %d pasadas completas, %d relecturas',
        statistics['durationMs'], statistics['fullPasses'], statistics['rereadCalls'])
    return result


@app.post("/ocr")
def ocr_endpoint():
    global OCR_ACTIVE_REQUEST
    request_started = time.perf_counter()
    if not request.data:
        return jsonify({"error": "No se recibio imagen"}), 400

    extraction_id = request.headers.get("X-Extraction-Id")
    budget_header = request.headers.get("X-OCR-Search-Budget-Seconds")
    remaining_header = request.headers.get("X-OCR-Document-Remaining-Seconds")
    try:
        budget_override = int(budget_header) if budget_header is not None else None
        if budget_override is not None and not 5 <= budget_override <= 180:
            raise ValueError()
    except ValueError:
        return jsonify({"error": "El presupuesto OCR debe ser un entero entre 5 y 180 segundos."}), 400
    try:
        document_remaining = float(remaining_header) if remaining_header is not None else None
        if document_remaining is not None and (not math.isfinite(document_remaining) or not 0 < document_remaining <= 3600):
            raise ValueError()
    except ValueError:
        return jsonify({"error": "El tiempo restante del documento debe ser positivo, finito y de hasta 3600 segundos."}), 400
    if extraction_id is not None and not valid_extraction_id(extraction_id):
        return jsonify({"error": "El identificador de extracción no es válido."}), 400

    if DEFAULT_LANGUAGE not in OCR_CACHE:
        return jsonify({"error": "El OCR está preparando los modelos. Esperá a que termine y reintentá."}), 503

    with OCR_REQUEST_CONDITION:
        prune_cancelled_requests()
        while True:
            if document_remaining is not None and time.perf_counter() >= request_started + document_remaining:
                return jsonify({"error": "El PDF superó el presupuesto total de procesamiento."}), 408
            if extraction_id in OCR_CANCELLED_REQUESTS:
                return jsonify({"error": "Se canceló el procesamiento de la factura."}), 409
            if OCR_PROCESSING_LOCK.acquire(blocking=False):
                cancelled = threading.Event()
                OCR_ACTIVE_REQUEST = (extraction_id, cancelled)
                break
            if OCR_ACTIVE_REQUEST is None or not OCR_ACTIVE_REQUEST[1].is_set():
                return jsonify({"error": "Ya hay una factura en procesamiento. Esperá a que termine y reintentá."}), 429
            # Nunca ejecutar dos lecturas nativas sobre la misma instancia PaddleOCR.
            OCR_REQUEST_CONDITION.wait(timeout=None if document_remaining is None else
                    max(.001, request_started + document_remaining - time.perf_counter()))
    OCR_REQUEST_CONTEXT.cancelled = cancelled
    OCR_REQUEST_CONTEXT.budget_seconds = budget_override
    # El tiempo consumido esperando la última lectura cancelada también cuenta.
    OCR_REQUEST_CONTEXT.document_deadline = request_started + document_remaining if document_remaining is not None else None

    language = request.headers.get("X-OCR-Language", "es")

    try:
        check_cancelled()
        with Image.open(BytesIO(request.data)) as uploaded:
            image = ImageOps.exif_transpose(uploaded).convert("RGB")
        debug_dir = create_debug_dir()
        save_debug_image(debug_dir, "original.png", image)
        preview = {}
        reader = run_fast_ocr if budget_override is not None and budget_override <= 10 else run_best_ocr
        variant_name, lines, detections, score = reader(image, language, debug_dir, preview_sink=preview)
        check_cancelled()
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
    except OcrCancelled as exc:
        app.logger.info("OCR request cancelled: %s", extraction_id)
        return jsonify({"error": str(exc)}), 409
    except OcrDeadlineExceeded as exc:
        app.logger.info("OCR document budget exhausted")
        return jsonify({"error": str(exc)}), 408
    except Exception as exc:
        app.logger.error("OCR request failed (%s)", type(exc).__name__)
        if debug_enabled():
            app.logger.debug("OCR failure details", exc_info=True)
        return jsonify({"error": "No se pudo completar el OCR. Revisá el diagnóstico local."}), 500
    finally:
        del OCR_REQUEST_CONTEXT.cancelled
        del OCR_REQUEST_CONTEXT.budget_seconds
        del OCR_REQUEST_CONTEXT.document_deadline
        with OCR_REQUEST_CONDITION:
            OCR_ACTIVE_REQUEST = None
            OCR_PROCESSING_LOCK.release()
            OCR_REQUEST_CONDITION.notify_all()


@app.post("/ocr/cancel")
def cancel_ocr_endpoint():
    extraction_id = request.args.get("extractionId")
    if not valid_extraction_id(extraction_id):
        return jsonify({"error": "El identificador de extracción no es válido."}), 400
    with OCR_REQUEST_CONDITION:
        prune_cancelled_requests()
        OCR_CANCELLED_REQUESTS[extraction_id] = time.monotonic()
        if OCR_ACTIVE_REQUEST is not None and OCR_ACTIVE_REQUEST[0] == extraction_id:
            OCR_ACTIVE_REQUEST[1].set()
        OCR_REQUEST_CONDITION.notify_all()
    return jsonify({"cancelled": True}), 200


@app.get("/health")
def health_endpoint():
    with OCR_REQUEST_CONDITION:
        return jsonify({
            "status": "ok", "ocrReady": DEFAULT_LANGUAGE in OCR_CACHE,
            "ocrBusy": OCR_PROCESSING_LOCK.locked(),
            "ocrCancelling": OCR_ACTIVE_REQUEST is not None and OCR_ACTIVE_REQUEST[1].is_set(),
        }), 200


if __name__ == "__main__":
    threading.Thread(target=warmup_default_ocr, daemon=True).start()
    app.run(host=os.environ.get("OCR_BIND_HOST", "127.0.0.1"), port=int(os.environ.get("OCR_BIND_PORT", "5000")))
