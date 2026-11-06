import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('benchmark_local',Path(__file__).resolve().parents[1]/'benchmark_local.py')
benchmark=importlib.util.module_from_spec(spec);spec.loader.exec_module(benchmark)


class LatencyTargetTest(unittest.TestCase):
    def test_only_successful_requests_strictly_below_the_target_pass(self):
        self.assertTrue(benchmark.assess_target({'cases':[{'id':'a','status':'ok','request_seconds':9.99}]})['passed'])
        result=benchmark.assess_target({'cases':[{'id':'a','status':'ok','request_seconds':10}, {'id':'b','status':'error'}]})
        self.assertFalse(result['passed']);self.assertEqual(2,result['cases_over_target_or_failed'])
    def test_missing_latency_is_not_a_fast_success(self):
        self.assertFalse(benchmark.assess_target({'cases':[{'id':'a','status':'ok'}]})['passed'])
