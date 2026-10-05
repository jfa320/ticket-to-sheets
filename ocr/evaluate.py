"""Evaluate local OCR through the backend without storing receipt transcripts."""

import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import ipaddress
import json
import math
import mimetypes
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


DEFAULT_ENDPOINT = "http://127.0.0.1:8080/api/receipts/extract"
DEFAULT_OUTPUT = "test-data/ocr-evaluation/report.json"
DEFAULT_TIMEOUT = 300
# Up to 20 PDF pages can now carry 900 KB JPEG previews encoded as base64.
MAX_RESPONSE_BYTES = 32_000_000
PRICE_PATTERN = re.compile(
    r"(?<![\d.,])(?:\$\s*-?\d+(?:[.,]\d{3})*(?:[.,]\d{2})?|-?\d+(?:[.,]\d{3})*[.,]\d{2})(?![\d.,])"
)


def normalized_lines(text):
    return [" ".join(line.split()).casefold() for line in text.splitlines() if line.strip()]


def normalize_text(text):
    return "\n".join(normalized_lines(text))


def edit_distance(expected, actual):
    """Exact Levenshtein distance using integer bit vectors and linear memory."""
    if expected == actual:
        return 0
    if len(expected) > len(actual):
        expected, actual = actual, expected
    if not expected:
        return len(actual)

    positions = {}
    for index, char in enumerate(expected):
        positions[char] = positions.get(char, 0) | (1 << index)
    mask = (1 << len(expected)) - 1
    last_bit = 1 << (len(expected) - 1)
    positive, negative, distance = mask, 0, len(expected)
    for char in actual:
        equal = positions.get(char, 0)
        vertical = equal | negative
        horizontal = (((equal & positive) + positive) ^ positive) | equal
        positive_horizontal = negative | ~(horizontal | positive)
        negative_horizontal = positive & horizontal
        distance += bool(positive_horizontal & last_bit) - bool(negative_horizontal & last_bit)
        positive_horizontal = (positive_horizontal << 1) | 1
        negative_horizontal <<= 1
        positive = (negative_horizontal | ~(vertical | positive_horizontal)) & mask
        negative = positive_horizontal & vertical & mask
    return distance


def canonical_price(value):
    if not isinstance(value, str):
        raise ValueError("expected_prices debe contener importes como strings")
    cleaned = value.strip().replace("$", "").replace(" ", "")
    if not re.fullmatch(r"-?\d+(?:[.,]\d{3})*(?:[.,]\d{2})?", cleaned):
        raise ValueError("Importe inválido en expected_prices")
    last_separator = max(cleaned.rfind(","), cleaned.rfind("."))
    if last_separator >= 0 and len(cleaned) - last_separator == 3:
        integer = cleaned[:last_separator].replace(",", "").replace(".", "")
        number = integer + "." + cleaned[last_separator + 1:]
    else:
        number = cleaned.replace(",", "").replace(".", "")
    return format(Decimal(number), ".2f")


def extract_prices(text):
    return [canonical_price(match.group()) for match in PRICE_PATTERN.finditer(text)]


def occurrence_metrics(expected, actual):
    expected_counts, actual_counts = Counter(expected), Counter(actual)
    matched = sum((expected_counts & actual_counts).values())
    expected_count, actual_count = len(expected), len(actual)
    return {
        "expected": expected_count,
        "recognized": actual_count,
        "matched": matched,
        "missing": expected_count - matched,
        "additional": actual_count - matched,
        "recall": matched / expected_count if expected_count else None,
        "precision": matched / actual_count if actual_count else None,
    }


def evaluate_text(expected_text, actual_text, expected_prices=None):
    expected, actual = normalize_text(expected_text), normalize_text(actual_text)
    distance = edit_distance(expected, actual)
    return {
        "expected_characters": len(expected),
        "recognized_characters": len(actual),
        "character_errors": distance,
        "character_error_rate": distance / max(1, len(expected)),
        "lines": occurrence_metrics(normalized_lines(expected_text), normalized_lines(actual_text)),
        "prices": occurrence_metrics(
            extract_prices(expected_text) if expected_prices is None else [canonical_price(value) for value in expected_prices],
            extract_prices(actual_text),
        ),
    }


def validate_endpoint(endpoint):
    parsed = urllib.parse.urlsplit(endpoint)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("El endpoint debe ser una URL HTTP/HTTPS local")
    if parsed.username is not None or parsed.password is not None or parsed.fragment:
        raise ValueError("El endpoint no admite credenciales ni fragmentos")
    try:
        loopback = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = parsed.hostname.lower() == "localhost"
    if not loopback:
        raise ValueError("El endpoint debe apuntar a loopback (localhost, 127.0.0.1 o ::1)")
    try:
        parsed.port
    except ValueError as exc:
        raise ValueError("Puerto inválido en el endpoint") from exc
    return endpoint


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def extract_ocr_response(image_path, endpoint=DEFAULT_ENDPOINT, timeout=DEFAULT_TIMEOUT, opener=None):
    validate_endpoint(endpoint)
    path = Path(image_path)
    image_bytes = path.read_bytes()
    if not image_bytes:
        raise ValueError("La imagen está vacía")
    boundary = "ocr-evaluation-" + uuid.uuid4().hex
    filename = re.sub(r"[^A-Za-z0-9._-]", "_", path.name)[:200] or "receipt.bin"
    content_type = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    header = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f"Content-Type: {content_type}\r\n\r\n"
    ).encode("ascii")
    body = header + image_bytes + f"\r\n--{boundary}--\r\n".encode("ascii")
    request = urllib.request.Request(endpoint, data=body, method="POST", headers={
        "Content-Type": f"multipart/form-data; boundary={boundary}",
        "Accept": "application/json",
    })
    if opener is None:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirectHandler())
    try:
        with opener.open(request, timeout=timeout) as response:
            if not 200 <= response.status < 300:
                raise ValueError(f"HTTP {response.status} del backend")
            response_type = response.headers.get_content_type()
            if response_type != "application/json" and not response_type.endswith("+json"):
                raise ValueError("El backend no respondió JSON")
            payload = response.read(MAX_RESPONSE_BYTES + 1)
            if len(payload) > MAX_RESPONSE_BYTES:
                raise ValueError("La respuesta del backend supera el límite de tamaño")
    except urllib.error.HTTPError as exc:
        exc.close()
        raise ValueError(f"HTTP {exc.code} del backend") from exc
    try:
        decoded = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError("La respuesta JSON del backend es inválida") from exc
    if not isinstance(decoded, dict) or not isinstance(decoded.get("rawText"), str):
        raise ValueError("La respuesta del backend no contiene rawText como string")
    return decoded


def extract_raw_text(image_path, endpoint=DEFAULT_ENDPOINT, timeout=DEFAULT_TIMEOUT, opener=None):
    return extract_ocr_response(image_path, endpoint, timeout, opener)["rawText"]


def load_manifest(manifest_path):
    path = Path(manifest_path).resolve()
    payload = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(payload, dict) or not isinstance(payload.get("cases"), list) or not payload["cases"]:
        raise ValueError("El manifest debe contener una lista cases no vacía")
    cases, ids = [], set()
    for case in payload["cases"]:
        if not isinstance(case, dict) or not isinstance(case.get("id"), str) or not case["id"].strip():
            raise ValueError("Cada case debe tener un id no vacío")
        if case["id"] in ids:
            raise ValueError("Los ids de cases deben ser únicos")
        ids.add(case["id"])
        if not isinstance(case.get("image"), str) or not case["image"].strip():
            raise ValueError("Cada case debe indicar una ruta image")
        if ("expected_text" in case) == ("text_file" in case):
            raise ValueError("Cada case debe tener expected_text o text_file, exclusivamente")
        text_key = "expected_text" if "expected_text" in case else "text_file"
        if not isinstance(case[text_key], str) or (text_key == "text_file" and not case[text_key].strip()):
            raise ValueError("expected_text/text_file debe ser un string válido")
        if "expected_prices" in case:
            if not isinstance(case["expected_prices"], list):
                raise ValueError("expected_prices debe ser una lista de importes")
            for price in case["expected_prices"]:
                canonical_price(price)
        resolved = dict(case, image=path.parent / case["image"])
        if "text_file" in case:
            resolved["text_file"] = path.parent / case["text_file"]
        cases.append(resolved)
    return cases


def summarize(cases):
    successful = [case for case in cases if case.get("status") == "ok"]
    expected_chars = sum(case["metrics"]["expected_characters"] for case in successful)
    errors = sum(case["metrics"]["character_errors"] for case in successful)
    summary = {
        "cases": len(cases), "successful_cases": len(successful), "failed_cases": len(cases) - len(successful),
        "elapsed_seconds": round(sum(case["elapsed_seconds"] for case in cases), 6),
        "expected_characters": expected_chars, "character_errors": errors,
        "character_error_rate": errors / max(1, expected_chars) if successful else None,
    }
    for field in ("lines", "prices"):
        metrics = {key: sum(case["metrics"][field][key] for case in successful)
                   for key in ("expected", "recognized", "matched", "missing", "additional")}
        metrics["recall"] = metrics["matched"] / metrics["expected"] if metrics["expected"] else None
        metrics["precision"] = metrics["matched"] / metrics["recognized"] if metrics["recognized"] else None
        summary[field] = metrics
    return summary


def run_evaluation(cases, endpoint=DEFAULT_ENDPOINT, timeout=DEFAULT_TIMEOUT, extractor=None):
    validate_endpoint(endpoint)
    if not math.isfinite(timeout) or timeout <= 0:
        raise ValueError("El timeout debe ser positivo")
    extract = extractor or extract_ocr_response
    results = []
    for case in cases:
        started = time.perf_counter()
        result = {"id": case["id"]}
        try:
            expected_text = case.get("expected_text")
            if expected_text is None:
                expected_text = case["text_file"].read_text(encoding="utf-8-sig")
            image_hash = hashlib.sha256(case["image"].read_bytes()).hexdigest()
            case_data = json.dumps({
                "image_sha256": image_hash, "expected_text": normalize_text(expected_text),
                "expected_prices": sorted(canonical_price(value) for value in case["expected_prices"])
                if "expected_prices" in case else extract_prices(expected_text),
            }, ensure_ascii=False, sort_keys=True).encode("utf-8")
            result["case_hash"] = hashlib.sha256(case_data).hexdigest()
            response = extract(case["image"], endpoint, timeout)
            raw_text = response.get("rawText") if isinstance(response, dict) else response
            if not isinstance(raw_text, str):
                raise ValueError("La respuesta del backend no contiene rawText como string")
            result.update(status="ok", metrics=evaluate_text(expected_text, raw_text, case.get("expected_prices")))
            if isinstance(response, dict):
                if isinstance(response.get("variant"), str):
                    result["variant"] = response["variant"]
                score = response.get("score")
                if isinstance(score, (int, float)) and not isinstance(score, bool) and math.isfinite(score):
                    result["score"] = score
        except (OSError, ValueError, TypeError) as exc:
            # No backend bodies, transcripts or local paths are copied to errors.
            message = str(exc) if isinstance(exc, ValueError) else f"No se pudo evaluar el archivo ({type(exc).__name__})"
            result.update(status="error", error={"type": type(exc).__name__, "message": message})
        result["elapsed_seconds"] = round(time.perf_counter() - started, 6)
        results.append(result)
    return {
        "schema_version": 1, "created_at": datetime.now(timezone.utc).isoformat(), "endpoint": endpoint,
        "normalization": "casefold; espacios consecutivos y líneas vacías normalizados; duplicados conservados",
        "price_scope": "todos los importes reconocidos en rawText; coincidencia numérica exacta sin tolerancia",
        "cases": results, "summary": summarize(results),
    }


def compare_reports(current, previous):
    if not isinstance(previous, dict) or previous.get("schema_version") != 1 or not isinstance(previous.get("cases"), list):
        raise ValueError("El informe previo no tiene un formato compatible")
    current_cases = {case["id"]: case for case in current["cases"]}
    previous_cases = {case["id"]: case for case in previous["cases"]}
    shared = sorted(current_cases.keys() & previous_cases.keys())
    comparable = [case_id for case_id in shared
                  if current_cases[case_id].get("status") == previous_cases[case_id].get("status") == "ok"
                  and current_cases[case_id].get("case_hash")
                  and current_cases[case_id]["case_hash"] == previous_cases[case_id].get("case_hash")]
    comparison = {
        "compared_ids": comparable,
        "not_compared_ids": sorted(set(shared) - set(comparable)),
        "only_current_ids": sorted(current_cases.keys() - previous_cases.keys()),
        "only_previous_ids": sorted(previous_cases.keys() - current_cases.keys()),
        "delta_direction": "current_minus_previous",
    }
    if not comparable:
        comparison["deltas"] = None
        return comparison
    current_summary = summarize([current_cases[case_id] for case_id in comparable])
    previous_summary = summarize([previous_cases[case_id] for case_id in comparable])
    comparison["current_summary"] = current_summary
    comparison["previous_summary"] = previous_summary
    comparison["deltas"] = {
        "character_error_rate": current_summary["character_error_rate"] - previous_summary["character_error_rate"],
        "elapsed_seconds": current_summary["elapsed_seconds"] - previous_summary["elapsed_seconds"],
    }
    for field in ("prices", "lines"):
        before, after = previous_summary[field]["recall"], current_summary[field]["recall"]
        comparison["deltas"][field + "_recall"] = after - before if after is not None and before is not None else None
    return comparison


def main(argv=None):
    parser = argparse.ArgumentParser(description="Evalúa OCR local con un manifest, sin guardar transcripciones en el informe.")
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, default=Path(DEFAULT_OUTPUT))
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args(argv)
    try:
        validate_endpoint(args.endpoint)
        cases = load_manifest(args.manifest)
        previous = json.loads(args.compare.read_text(encoding="utf-8-sig")) if args.compare else None
        if args.compare is not None:
            compare_reports({"cases": []}, previous)
        report = run_evaluation(cases, args.endpoint, args.timeout)
        if previous is not None:
            report["comparison"] = compare_reports(report, previous)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        parser.error(str(exc))
    summary = report["summary"]
    print(f"Casos correctos: {summary['successful_cases']}/{summary['cases']}; fallidos: {summary['failed_cases']}")
    print(f"Informe: {args.output}")
    return 1 if summary["failed_cases"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
