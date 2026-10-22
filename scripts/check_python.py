"""Valida dependencias y ejecuta todas las pruebas Python sin omisiones silenciosas."""
import importlib
from pathlib import Path
import sys
import unittest


def successful(result):
    return result.wasSuccessful() and not result.skipped and result.testsRun > 0


def main():
    missing = []
    for module in ('numpy', 'PIL', 'cv2', 'flask', 'paddle', 'paddleocr'):
        try:
            importlib.import_module(module)
        except ImportError:
            missing.append(module)
    if missing:
        print('Faltan dependencias Python: ' + ', '.join(missing), file=sys.stderr)
        print('Usá un entorno con ocr/requirements.txt instalado.', file=sys.stderr)
        return 1
    root = Path(__file__).resolve().parents[1]
    suite = unittest.TestSuite()
    for folder in ('ocr/tests', 'scripts/tests'):
        suite.addTests(unittest.TestLoader().discover(str(root / folder)))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if result.skipped:
        print(f'VALIDACIÓN INCOMPLETA: {len(result.skipped)} pruebas omitidas.', file=sys.stderr)
    return 0 if successful(result) else 1


if __name__ == '__main__':
    raise SystemExit(main())
