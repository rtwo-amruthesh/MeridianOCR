FROM python:3.10-slim

# PaddleOCR pulls in OpenCV, which needs these at runtime.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 libgomp1 curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

# Model weights download on first use. Baking them in keeps cold starts sane;
# comment out if you would rather keep the image small.
RUN python -c "from paddleocr import PaddleOCR; PaddleOCR(lang='en')" || true

RUN useradd --create-home --uid 10001 ocr && chown -R ocr /app
USER ocr

ENV OCR_HOST=0.0.0.0 OCR_PORT=8000
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8000/health || exit 1

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
