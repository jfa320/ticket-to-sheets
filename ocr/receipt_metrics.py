"""Exact product/field metrics; reports contain counts, never purchase values."""

from collections import defaultdict
from datetime import datetime
from decimal import Decimal
import re

ITEM_FIELDS = ("descripcion", "marca", "lugarDeCompra", "categoria", "cantidad", "precioUnitario", "fecha")
REQUIRED_FIELDS = {"descripcion", "cantidad", "precioUnitario"}
RECEIPT_FIELDS = ("storeName", "date", "total")


def canonical(value, field):
    if not isinstance(value, str):
        raise ValueError("Los campos de expected_receipt deben ser strings")
    text = " ".join(value.split())
    if field in {"cantidad", "precioUnitario", "total"}:
        number = text.replace(" ", "").removeprefix("$")
        if field != "cantidad" and re.fullmatch(r"[+-]?\d{1,3}(?:\.\d{3})+(?:,\d+)?", number):
            number = number.replace(".", "")
        if not re.fullmatch(r"[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)", number):
            raise ValueError("Importe o cantidad inválido en expected_receipt")
        return Decimal(number.replace(",", "."))
    if field in {"date", "fecha"} and text:
        try:
            return datetime.strptime(text, "%d/%m/%Y").date().isoformat()
        except ValueError as exc:
            raise ValueError("Fecha inválida en expected_receipt") from exc
    return text.casefold()


def validate_receipt(expected):
    if not isinstance(expected, dict) or not isinstance(expected.get("items"), list):
        raise ValueError("expected_receipt debe contener una lista items")
    if len(expected["items"]) > 500:
        raise ValueError("expected_receipt admite hasta 500 productos")
    if set(expected) - {*RECEIPT_FIELDS, "items"}:
        raise ValueError("Campo desconocido en expected_receipt")
    for field in RECEIPT_FIELDS:
        if field in expected:
            canonical(expected[field], field)
    associations = {}
    for item in expected["items"]:
        if not isinstance(item, dict) or not REQUIRED_FIELDS <= item.keys() or set(item) - {*ITEM_FIELDS, "association_descriptions"}:
            raise ValueError("Cada producto esperado requiere descripcion, cantidad y precioUnitario")
        for field, value in item.items():
            if field == "association_descriptions":
                if not isinstance(value, list) or len(value) > 20 or any(not isinstance(alias, str) or not alias.strip() for alias in value):
                    raise ValueError("association_descriptions requiere hasta 20 textos revisados no vacíos")
                continue
            canonical(value, field)
        if not item["descripcion"].strip():
            raise ValueError("La descripción esperada no puede estar vacía")
        name = canonical(item["descripcion"], "descripcion")
        for alias in [item["descripcion"], *item.get("association_descriptions", [])]:
            identity = canonical(alias, "descripcion")
            if identity in associations and associations[identity] != name:
                raise ValueError("Una descripción de asociación no puede identificar productos distintos")
            associations[identity] = name
    return expected


def reference_fields(target):
    return {field: value for field, value in target.items() if field in ITEM_FIELDS}


def evaluate_receipt(expected, response):
    validate_receipt(expected)
    if not isinstance(response, dict) or not isinstance(response.get("items"), list):
        raise ValueError("La respuesta no contiene items para evaluar productos")
    actual = response["items"]
    if len(actual) > 500 or any(not isinstance(item, dict) for item in actual):
        raise ValueError("La respuesta contiene productos inválidos")

    def equal(value, target, field):
        try:
            return canonical(value, field) == canonical(target, field)
        except ValueError:
            return False

    groups = defaultdict(list)
    for index, item in enumerate(actual):
        if isinstance(item.get("descripcion"), str):
            groups[canonical(item["descripcion"], "descripcion")].append(index)
    assigned, used = {}, set()
    # First preserve every complete exact match, including repeated products.
    for complete_only in (True, False):
        for index, target in enumerate(expected["items"]):
            if index in assigned:
                continue
            identities = {canonical(value, "descripcion") for value in [target["descripcion"], *target.get("association_descriptions", [])]}
            candidates = [i for identity in sorted(identities) for i in groups[identity] if i not in used]
            if not candidates:
                continue
            score = lambda i: sum(equal(actual[i].get(field), value, field) for field, value in reference_fields(target).items())
            best = max(candidates, key=score)
            if complete_only and score(best) != len(reference_fields(target)):
                continue
            assigned[index] = best
            used.add(best)

    fields = {}
    fully_matched = 0
    for index, target in enumerate(expected["items"]):
        item = actual[assigned[index]] if index in assigned else {}
        complete = True
        for field, value in reference_fields(target).items():
            matched = equal(item.get(field), value, field)
            metric = fields.setdefault("items." + field, {"expected": 0, "matched": 0})
            metric["expected"] += 1
            metric["matched"] += int(matched)
            complete &= matched
        fully_matched += int(complete)
    for field in RECEIPT_FIELDS:
        if field in expected:
            fields[field] = {"expected": 1, "matched": int(equal(response.get(field), expected[field], field))}
    for metric in fields.values():
        metric["accuracy"] = metric["matched"] / metric["expected"]
    count = len(expected["items"])
    return {"items": {"expected": count, "recognized": len(actual), "matched": fully_matched,
                      "missing": count - fully_matched, "additional": len(actual) - fully_matched,
                      "recall": fully_matched / count if count else None,
                      "precision": fully_matched / len(actual) if actual else None}, "fields": fields}


def summarize_receipts(metrics):
    fields = {}
    items = {key: sum(metric["items"][key] for metric in metrics)
             for key in ("expected", "recognized", "matched", "missing", "additional")}
    items["recall"] = items["matched"] / items["expected"] if items["expected"] else None
    items["precision"] = items["matched"] / items["recognized"] if items["recognized"] else None
    for metric in metrics:
        for field, value in metric["fields"].items():
            total = fields.setdefault(field, {"expected": 0, "matched": 0})
            for key in ("expected", "matched"):
                total[key] += value[key]
    for value in fields.values():
        value["accuracy"] = value["matched"] / value["expected"]
    return {"annotated_cases": len(metrics), "items": items, "fields": fields}


def latency_summary(seconds):
    values = sorted(seconds)
    def percentile(fraction):
        position = (len(values) - 1) * fraction
        index = int(position)
        return round(values[index] + (values[min(index + 1, len(values) - 1)] - values[index]) * (position - index), 6)
    return {"count": len(values), "p50": percentile(.50) if values else None,
            "p95": percentile(.95) if values else None, "max": max(values) if values else None}
