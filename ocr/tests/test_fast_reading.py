import unittest
import sys
from pathlib import Path
from unittest.mock import Mock
from PIL import Image
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fast_reading import run_fast_reading


def row(text,y,confidence=.99):
    return dict(text=text,confidence=confidence,score=confidence,left=5,right=180,top=y,bottom=y+15,width=175,height=15,
        box=[[5,y],[180,y],[180,y+15],[5,y+15]])


class FastReadingTest(unittest.TestCase):
    def test_a_full_pass_that_uses_five_seconds_does_not_start_unbudgeted_rereads(self):
        elapsed=[0.0];rows=[row('LOCAL',10),row('LECHE',40),row('1200,00',40,.93),row('TOTAL 1200,00',70)]
        def recognize(image):elapsed[0]=5;return rows,rows
        crop=Mock()
        result,_,_=run_fast_reading(Image.new('RGB',(300,200)),recognize,crop,lambda:None,clock=lambda:elapsed[0])
        crop.assert_not_called();self.assertTrue(result[2][2]['reviewRequired'])
    def test_reads_a_complete_frame_once_without_orientation_probes(self):
        rows=[row('LOCAL',10),row('LECHE 1200,00',40),row('TOTAL 1200,00',70)]
        recognize=Mock(return_value=(rows,rows)); crop=Mock()
        result, selected, stats=run_fast_reading(Image.new('RGB',(4000,3000)),recognize,crop,lambda:None,retry=False)
        self.assertEqual(1,recognize.call_count);self.assertEqual(0,crop.call_count)
        self.assertLessEqual(max(selected.size),1280);self.assertEqual('fast-0',result[0]);self.assertEqual(1,stats['fullPasses'])
    def test_retains_uncertainty_when_the_budget_cannot_confirm_a_price(self):
        rows=[row('LOCAL',10),row('LECHE',40),row('1200,00',40,.93),row('TOTAL 1200,00',70)]
        crop=Mock();result,_,_=run_fast_reading(Image.new('RGB',(300,200)),lambda image:(rows,rows),crop,lambda:None,retry=False)
        self.assertTrue(result[2][2]['reviewRequired']);self.assertEqual('1200,00',result[2][2]['text']);crop.assert_not_called()
    def test_rotates_only_after_an_unreadable_result_and_keeps_the_complete_reading(self):
        rows=[row('LOCAL',10),row('PAN 800,00',40),row('TOTAL 800,00',70)]
        recognize=Mock(side_effect=[([],[]),(rows,rows)])
        result,_,stats=run_fast_reading(Image.new('RGB',(300,200)),recognize,Mock(),lambda:None,retry=False)
        self.assertEqual('fast-90',result[0]);self.assertEqual(2,stats['fullPasses'])
    def test_does_not_start_another_native_pass_when_its_estimate_exceeds_the_budget(self):
        elapsed=[0.0]
        def recognize(image):elapsed[0]+=5;return [],[]
        _,_,stats=run_fast_reading(Image.new('RGB',(300,200)),recognize,Mock(),lambda:None,clock=lambda:elapsed[0])
        self.assertEqual(1,stats['fullPasses'])
    def test_checks_cancellation_before_the_first_native_call(self):
        recognize=Mock()
        def cancelled():raise RuntimeError('cancelled')
        with self.assertRaisesRegex(RuntimeError,'cancelled'):
            run_fast_reading(Image.new('RGB',(300,200)),recognize,Mock(),cancelled)
        recognize.assert_not_called()
