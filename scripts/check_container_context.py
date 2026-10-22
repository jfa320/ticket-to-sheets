"""Comprueba imports locales incluidos en la imagen; no reemplaza una ejecución Docker."""
import ast
from pathlib import Path
import shlex


def missing_modules(root):
    ocr = root / 'ocr'
    local = {path.stem for path in ocr.glob('*.py')}
    copied = set()
    sources = []
    for line in (ocr / 'Dockerfile').read_text(encoding='utf-8-sig').splitlines():
        if not line.lstrip().upper().startswith('COPY '):
            continue
        parts = shlex.split(line)
        if parts and parts[0].upper() == 'COPY' and len(parts) == 3:
            source = root / parts[1]
            if not source.exists():
                raise ValueError('Un COPY del Dockerfile apunta a un archivo inexistente')
            sources.extend(source.rglob('*.py') if source.is_dir() else [source] if source.suffix == '.py' else [])
            if source.is_file() and source.suffix == '.py': copied.add(source.stem)
    missing = set()
    for source in sources:
        for node in ast.walk(ast.parse(source.read_text(encoding='utf-8-sig'))):
            names = [alias.name.split('.')[0] for alias in node.names] if isinstance(node, ast.Import) else [node.module.split('.')[0]] if isinstance(node, ast.ImportFrom) and node.module else []
            missing.update(name for name in names if name in local and name not in copied)
    return sorted(missing)


def main():
    missing = missing_modules(Path(__file__).resolve().parents[1])
    if missing:
        print('Módulos locales ausentes de la imagen OCR: ' + ', '.join(missing))
        return 1
    print('OK: imports locales incluidos en el contexto OCR. Docker no fue ejecutado por esta comprobación.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
