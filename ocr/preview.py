"""Bounded, in-memory previews in the selected OCR coordinate frame."""

import base64
from io import BytesIO
import math

from PIL import Image


MAX_PIXELS = 2_000_000
MAX_SIDE = 8000
MAX_BYTES = 900_000


def build_preview(image):
    """Encode a preview without changing the coordinate units of detections."""
    width, height = image.size
    scale = min(1.0, math.sqrt(MAX_PIXELS / (width * height)), MAX_SIDE / max(width, height))
    size = (max(1, int(width * scale)), max(1, int(height * scale)))
    thumbnail = image.convert("RGB")
    if thumbnail.size != size:
        thumbnail = thumbnail.resize(size, Image.Resampling.LANCZOS)
    while True:
        output = BytesIO()
        thumbnail.save(output, format="JPEG", quality=80, optimize=True)
        encoded = output.getvalue()
        if len(encoded) <= MAX_BYTES:
            break
        reduced = (max(1, int(thumbnail.width * 0.75)), max(1, int(thumbnail.height * 0.75)))
        if reduced == thumbnail.size:
            raise ValueError("Preview byte budget is too small")
        thumbnail = thumbnail.resize(reduced, Image.Resampling.LANCZOS)
    return {
        "imageDataUrl": "data:image/jpeg;base64," + base64.b64encode(encoded).decode("ascii"),
        "width": width,
        "height": height,
    }
