FROM python:3.11-slim

WORKDIR /app

ENV FLAGS_use_mkldnn=0
ENV FLAGS_use_onednn=0
ENV OMP_NUM_THREADS=1
ENV OPENBLAS_NUM_THREADS=1
ENV MKL_NUM_THREADS=1
ENV NUMEXPR_NUM_THREADS=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    libglib2.0-0 \
    libsm6 \
    libxrender1 \
    libxext6 \
    libgomp1 \
    libgl1 \
    && rm -rf /var/lib/apt/lists/*

COPY ocr/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --default-timeout=180 --retries=5 -r requirements.txt

COPY ocr/service.py ./service.py
COPY ocr/image_variants.py ./image_variants.py
COPY ocr/document_geometry.py ./document_geometry.py
COPY ocr/illumination.py ./illumination.py
COPY ocr/evaluate.py ./evaluate.py
COPY ocr/ocr_regions.py ./ocr_regions.py
COPY ocr/targeted_retry.py ./targeted_retry.py
COPY ocr/variant_fusion.py ./variant_fusion.py
COPY ocr/preview.py ./preview.py
COPY ocr/model_paths.py ./model_paths.py
COPY ocr/receipt_layout.py ./receipt_layout.py
COPY ocr/preprocess.py ./preprocess.py
COPY ocr/scoring.py ./scoring.py
COPY ocr/tests ./tests

EXPOSE 5000

CMD ["python", "service.py"]
