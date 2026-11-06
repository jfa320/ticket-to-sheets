import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('browser_fixture', Path(__file__).resolve().parents[1] / 'browser_fixture.py')
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)

class BrowserFixtureTest(unittest.TestCase):
    def test_digital_fixture_is_textual_and_its_cross_reference_matches_objects(self):
        pdf = fixture.make_digital_pdf()
        self.assertTrue(pdf.startswith(b'%PDF-1.4'))
        self.assertIn(b'/Type /Font', pdf)
        self.assertNotIn(b'/Subtype /Image', pdf)
        self.assertIn(b'LECHE ENTERA', pdf)
        xref = int(pdf.split(b'startxref\n')[1].splitlines()[0])
        self.assertEqual(b'xref', pdf[xref:xref+4])
    def test_control_fixture_contains_only_fictitious_ocr_and_no_credentials(self):
        value = fixture.Fixture('http://127.0.0.1:18081')
        self.assertEqual(0, value.ocr_delay)
        self.assertEqual([], value.writes)
        self.assertTrue(value.config['oauthConfigured'])
