"""Compara presupuestos OCR sobre las mismas imágenes y referencias locales."""
import argparse
import json
from pathlib import Path

from evaluate import load_manifest, run_evaluation, extract_ocr_response, compare_reports


def benchmark(cases, endpoint, output, budgets=(20, 40, 90), save_responses=False):
    if not budgets or any(type(value) is not int or not 5 <= value <= 180 for value in budgets):
        raise ValueError("Los presupuestos deben estar entre 5 y 180 segundos")
    output.mkdir(parents=True, exist_ok=True)
    reports = []
    for budget in budgets:
        position = 0
        def extract(image, url, timeout):
            nonlocal position
            position += 1
            response = extract_ocr_response(image, url, timeout, budget_seconds=budget)
            if save_responses:
                # Datos privados: usar únicamente un directorio ignorado por Git.
                target = output / f"response-{budget}-{position:02}.json"
                target.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
            return response
        report = run_evaluation(cases, endpoint, extractor=extract, progress=lambda case:
            print(f"{budget}s / {case['id']}: {case['status']} ({case['elapsed_seconds']:.2f}s)", flush=True))
        report["ocr_budget_seconds"] = budget
        (output / f"budget-{budget}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        reports.append(report)
    result = {"budgets": list(budgets), "summaries": [report["summary"] for report in reports],
              "comparisons_to_last": [compare_reports(report, reports[-1]) for report in reports[:-1]]}
    (output / "comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--endpoint", default="http://127.0.0.1:8080/api/receipts/extract")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--budgets", nargs="+", type=int, default=[20, 40, 90])
    parser.add_argument("--save-responses", action="store_true", help="Guarda datos privados para investigar; usar test-data/")
    args = parser.parse_args()
    reports = benchmark(load_manifest(args.manifest), args.endpoint, args.output, args.budgets, args.save_responses)
    return int(any(report["summary"]["failed_cases"] for report in reports))


if __name__ == "__main__":
    raise SystemExit(main())
