import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('checks', Path(__file__).parents[1] / 'check_python.py')
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class StrictResultTest(unittest.TestCase):
    def test_skipped_empty_and_failed_suites_cannot_report_success(self):
        for passed, count, skipped, expected in [(True, 3, [], True), (True, 3, [('test', 'dependency')], False),
                                                  (True, 0, [], False), (False, 3, [], False)]:
            result = Mock(testsRun=count, skipped=skipped)
            result.wasSuccessful.return_value = passed
            self.assertEqual(expected, checks.successful(result))
