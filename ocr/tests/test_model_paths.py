import os
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import model_paths


class ModelPathsTest(unittest.TestCase):
    def test_non_windows_keeps_paddle_defaults(self):
        with patch.object(model_paths.os, "name", "posix"), patch.object(model_paths.importlib, "import_module") as load:
            self.assertEqual({}, model_paths.model_path_options("es"))
            load.assert_not_called()

    def test_windows_uses_short_cache_and_language_specific_models_without_changing_environment(self):
        api = Mock()
        api.DEFAULT_OCR_MODEL_VERSION = "PP-OCRv4"
        api.parse_lang.return_value = ("latin", "en")
        api.get_model_config.side_effect = lambda family, version, kind, lang: {"url": f"https://example.invalid/{kind}-{lang}.tar"}
        api.confirm_model_dir_url.side_effect = lambda custom, parent, url: (os.path.join(parent, "model"), url)
        previous = dict(os.environ)
        with patch.object(model_paths.os, "name", "nt"), \
                patch.object(model_paths.importlib, "import_module", return_value=api), \
                patch.object(model_paths.os.path, "expanduser", return_value="C:/Users/UsuarioÁ/.paddleocr"), \
                patch.object(model_paths.os, "makedirs"), \
                patch.object(model_paths, "short_windows_path", return_value="C:/Users/USUARI~1/.paddleocr"):
            options = model_paths.model_path_options("es")
        self.assertEqual({"det_model_dir", "rec_model_dir", "cls_model_dir"}, set(options))
        self.assertTrue(all(path.isascii() for path in options.values()))
        self.assertIn(os.path.join("rec", "latin"), options["rec_model_dir"])
        self.assertIn(os.path.join("det", "en"), options["det_model_dir"])
        self.assertEqual(previous, dict(os.environ))

    def test_ascii_directory_needs_no_short_path_conversion(self):
        self.assertEqual("C:/cache/paddle", model_paths.short_windows_path("C:/cache/paddle"))
