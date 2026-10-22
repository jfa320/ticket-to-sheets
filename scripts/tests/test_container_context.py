import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('container_check',Path(__file__).parents[1]/'check_container_context.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class ContainerContextTest(unittest.TestCase):
    def test_missing_local_import_fails_and_copying_the_module_repairs_it(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder);ocr=root/'ocr';ocr.mkdir()
            (ocr/'service.py').write_text('from search_policy import AdaptiveSearch\n',encoding='utf-8')
            (ocr/'search_policy.py').write_text('class AdaptiveSearch: pass\n',encoding='utf-8')
            dockerfile=ocr/'Dockerfile'
            dockerfile.write_text('COPY ocr/service.py ./service.py\n',encoding='utf-8')
            self.assertEqual(['search_policy'],module.missing_modules(root))
            dockerfile.write_text(dockerfile.read_text()+'COPY ocr/search_policy.py ./search_policy.py\n',encoding='utf-8')
            self.assertEqual([],module.missing_modules(root))
