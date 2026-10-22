import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import benchmark
from evaluate import extract_ocr_response


class BenchmarkTest(unittest.TestCase):
    def test_same_cases_are_sent_with_separate_budgets_without_saving_private_responses_by_default(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            image = root / 'synthetic.png'; image.write_bytes(b'image')
            cases = [{'id': 'case', 'image': image, 'expected_text': 'LECHE 1200,00'}]
            with patch.object(benchmark, 'extract_ocr_response', return_value={'rawText': 'LECHE 1200,00'}) as extract:
                reports = benchmark.benchmark(cases, 'http://localhost:8080/api/receipts/extract', root / 'output', (20,40,90))
            self.assertEqual([20,40,90], [call.kwargs['budget_seconds'] for call in extract.call_args_list])
            self.assertEqual(1, len({report['cases'][0]['case_hash'] for report in reports}))
            self.assertFalse(list((root / 'output').glob('response-*')))
            self.assertTrue((root / 'output' / 'comparison.json').exists())

    def test_rejects_invalid_budget_before_reading_a_file_or_sending_a_request(self):
        for budget in (4,181,True,40.5):
            with self.assertRaises(ValueError):
                extract_ocr_response('missing.png', budget_seconds=budget)
