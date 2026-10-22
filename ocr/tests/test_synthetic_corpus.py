import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

from PIL import Image, ImageFont
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from synthetic_corpus import generate
from evaluate import load_manifest


class SyntheticCorpusTest(unittest.TestCase):
    def test_references_are_disjoint_and_generation_is_reproducible(self):
        with tempfile.TemporaryDirectory() as folder, \
                patch("synthetic_corpus.load_font", return_value=ImageFont.load_default(size=30)):
            output = Path(folder)
            manifests = generate(output)
            groups = [load_manifest(path) for path in manifests.values()]
            self.assertEqual([10, 10], [len(group) for group in groups])
            references = [json.loads((output / f"{name}-01.json").read_text(encoding="utf-8"))
                          for name in manifests]
            names = [{item["descripcion"] for item in ref["items"]} for ref in references]
            self.assertFalse(names[0] & names[1])
            self.assertNotEqual(references[0]["date"], references[1]["date"])
            first = (output / "holdout-01.png").read_bytes()
            generate(output)
            self.assertEqual(first, (output / "holdout-01.png").read_bytes())
            with Image.open(output / "holdout-02.png") as rotated:
                self.assertEqual((620, 840), rotated.size)
