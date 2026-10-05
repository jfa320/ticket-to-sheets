"""Model locations usable by Paddle's native Windows file reader."""

import ctypes
import importlib
import os


def short_windows_path(path):
    """Resolve an existing directory without changing HOME or USERPROFILE."""
    if os.name != "nt" or path.isascii():
        return path
    function = ctypes.windll.kernel32.GetShortPathNameW
    function.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32]
    function.restype = ctypes.c_uint32
    size = function(path, None, 0)
    if size:
        buffer = ctypes.create_unicode_buffer(size)
        written = function(path, buffer, size)
        if 0 < written < size and buffer.value.isascii():
            return buffer.value
    raise RuntimeError("PaddleOCR necesita una ruta de modelos sin acentos. Windows no devolvió un nombre corto para la caché.")


def model_path_options(language):
    if os.name != "nt":
        return {}
    api = importlib.import_module("paddleocr.paddleocr")
    base = os.path.expanduser("~/.paddleocr")
    os.makedirs(base, exist_ok=True)
    base = short_windows_path(base)
    rec_language, det_language = api.parse_lang(language)
    options = {}
    for kind, model_language in (("det", det_language), ("rec", rec_language), ("cls", "ch")):
        config = api.get_model_config("OCR", api.DEFAULT_OCR_MODEL_VERSION, kind, model_language)
        parent = os.path.join(base, "whl", kind, model_language) if kind != "cls" else os.path.join(base, "whl", kind)
        directory, _ = api.confirm_model_dir_url(None, parent, config["url"])
        options[kind + "_model_dir"] = directory
    return options
