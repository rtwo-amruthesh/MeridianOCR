"""
OCR service.

Wraps PaddleOCR behind a small HTTP API. Returns per-line text, confidence and
bounding polygons — the polygons are what let a reviewer trace an extracted
value back to the pixels it came from, so they are never discarded.

Notes on the two things that most often break here:

1. PaddleOCR changed its API between 2.x and 3.x. `_recognise` normalises both
   return shapes, so an upgrade doesn't silently produce empty results.
2. PaddleOCR instances are not thread-safe and inference is CPU-bound. Handlers
   are plain `def` (FastAPI runs them in a threadpool) and inference is
   serialised behind a lock.
"""

from __future__ import annotations

import io
import logging
import os
import secrets
import tempfile
import threading
from pathlib import Path
from typing import Any, Iterable

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ocr-service")

# ─────────────────────────── configuration ───────────────────────────

SERVICE_TOKEN = os.getenv("OCR_SERVICE_TOKEN", "")
MAX_BYTES = int(os.getenv("OCR_MAX_BYTES", 10 * 1024 * 1024))
MAX_PDF_PAGES = int(os.getenv("OCR_MAX_PDF_PAGES", 10))
PDF_DPI = int(os.getenv("OCR_PDF_DPI", 200))
OCR_LANG = os.getenv("OCR_LANG", "en")

ALLOWED = {
    "image/png": b"\x89PNG\r\n\x1a\n",
    "image/jpeg": b"\xff\xd8\xff",
    "image/webp": b"RIFF",
    "application/pdf": b"%PDF",
}

# ─────────────────────────── engine ───────────────────────────

_engine = None
_engine_lock = threading.Lock()   # guards construction
_infer_lock = threading.Lock()    # guards inference (PaddleOCR is not thread-safe)


def _build_engine():
    """Construct PaddleOCR against whichever major version is installed."""
    from paddleocr import PaddleOCR

    # 3.x renamed the angle-classifier flag and dropped use_gpu in favour of device.
    for kwargs in (
        {"lang": OCR_LANG, "use_textline_orientation": True},      # 3.x
        {"lang": OCR_LANG, "use_angle_cls": True, "use_gpu": False},  # 2.x
        {"lang": OCR_LANG},                                        # last resort
    ):
        try:
            engine = PaddleOCR(**kwargs)
            log.info("PaddleOCR initialised with %s", sorted(kwargs))
            return engine
        except (TypeError, ValueError) as exc:
            log.debug("PaddleOCR rejected %s: %s", sorted(kwargs), exc)
    raise RuntimeError("Could not initialise PaddleOCR with any known argument set.")


def get_engine():
    global _engine
    if _engine is None:
        with _engine_lock:
            if _engine is None:
                _engine = _build_engine()
    return _engine


def _normalise_2x(result: Any) -> list[dict]:
    """2.x: [[ [box, (text, score)], ... ]] — box is 4 [x, y] points."""
    lines: list[dict] = []
    if not result:
        return lines
    page = result[0]
    if not page:
        return lines
    for entry in page:
        try:
            box, (text, score) = entry[0], entry[1]
        except (TypeError, ValueError, IndexError):
            continue
        lines.append({
            "text": str(text),
            "confidence": round(float(score), 4),
            "bbox": [[round(float(p[0]), 1), round(float(p[1]), 1)] for p in box],
        })
    return lines


def _normalise_3x(result: Any) -> list[dict]:
    """3.x: list of dict-like results carrying rec_texts / rec_scores / rec_polys."""
    lines: list[dict] = []
    for page in _as_iterable(result):
        data = getattr(page, "json", None) or page
        if hasattr(data, "get") and "res" in data:
            data = data["res"]
        if not hasattr(data, "get"):
            continue
        texts = data.get("rec_texts") or []
        scores = data.get("rec_scores") or []
        polys = data.get("rec_polys") or data.get("dt_polys") or data.get("rec_boxes") or []
        for i, text in enumerate(texts):
            score = float(scores[i]) if i < len(scores) else 0.0
            poly = polys[i] if i < len(polys) else None
            lines.append({
                "text": str(text),
                "confidence": round(score, 4),
                "bbox": _poly(poly),
            })
    return lines


def _as_iterable(x: Any) -> Iterable:
    if x is None:
        return []
    return x if isinstance(x, (list, tuple)) else [x]


def _poly(poly) -> list[list[float]] | None:
    if poly is None:
        return None
    pts = poly.tolist() if hasattr(poly, "tolist") else list(poly)
    if len(pts) == 4 and not isinstance(pts[0], (list, tuple)):   # [x1, y1, x2, y2]
        x1, y1, x2, y2 = (float(v) for v in pts)
        return [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
    return [[round(float(p[0]), 1), round(float(p[1]), 1)] for p in pts]


def _recognise(image_path: str) -> list[dict]:
    """Run OCR on one image and return normalised lines."""
    engine = get_engine()
    with _infer_lock:
        if hasattr(engine, "predict"):
            try:
                return _normalise_3x(engine.predict(image_path))
            except (AttributeError, TypeError):
                pass  # 2.x exposes predict with a different contract
        try:
            raw = engine.ocr(image_path, cls=True)
        except TypeError:
            raw = engine.ocr(image_path)     # 3.x dropped the cls argument
    lines = _normalise_2x(raw)
    return lines if lines else _normalise_3x(raw)


# ─────────────────────────── input handling ───────────────────────────

def _sniff(head: bytes) -> str | None:
    for mime, magic in ALLOWED.items():
        if head.startswith(magic):
            return mime
    return None


def _read_upload(upload: UploadFile) -> tuple[bytes, str]:
    payload = upload.file.read(MAX_BYTES + 1)
    if len(payload) > MAX_BYTES:
        raise HTTPException(
            status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            f"File exceeds the {MAX_BYTES // 1048576} MB limit.",
        )
    if not payload:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "The uploaded file is empty.")

    # Trust the bytes, not the declared content type.
    sniffed = _sniff(payload[:16])
    if sniffed is None:
        raise HTTPException(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            "Unsupported file. Send PNG, JPEG, WebP or PDF.",
        )
    return payload, sniffed


def _pdf_to_images(payload: bytes, workdir: Path) -> list[Path]:
    try:
        import fitz  # PyMuPDF
    except ImportError as exc:
        raise HTTPException(
            status.HTTP_501_NOT_IMPLEMENTED,
            "PDF support needs PyMuPDF. Install it or send an image.",
        ) from exc

    pages: list[Path] = []
    with fitz.open(stream=payload, filetype="pdf") as doc:
        if doc.page_count > MAX_PDF_PAGES:
            raise HTTPException(
                status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                f"PDF has {doc.page_count} pages; the limit is {MAX_PDF_PAGES}.",
            )
        for number in range(doc.page_count):
            pix = doc.load_page(number).get_pixmap(dpi=PDF_DPI)
            out = workdir / f"page_{number + 1:03d}.png"
            pix.save(out.as_posix())
            pages.append(out)
    return pages


def _dimensions(path: Path) -> tuple[int, int]:
    try:
        from PIL import Image
        with Image.open(path) as im:
            return im.width, im.height
    except Exception:  # noqa: BLE001 — dimensions are a nicety, not a requirement
        return 0, 0


# ─────────────────────────── auth ───────────────────────────

def require_service_token(x_service_token: str | None = Header(default=None)) -> None:
    """Shared-secret gate. Set OCR_SERVICE_TOKEN in both this service and the API."""
    if not SERVICE_TOKEN:
        return  # unset means open — acceptable only when bound to localhost
    if not x_service_token or not secrets.compare_digest(x_service_token, SERVICE_TOKEN):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid or missing service token.")


# ─────────────────────────── schema ───────────────────────────

class Line(BaseModel):
    text: str
    confidence: float
    bbox: list[list[float]] | None = None


class Page(BaseModel):
    page: int
    width: int
    height: int
    lines: list[Line]


class OcrResult(BaseModel):
    lines: list[Line]
    pages: list[Page]
    lineCount: int
    meanConfidence: float
    accuracy: float          # meanConfidence as a percentage, for the Java client
    width: int
    height: int
    pageCount: int


# ─────────────────────────── app ───────────────────────────

app = FastAPI(
    title="OCR Service",
    version="2.0",
    description="Text, confidence and bounding polygons from scanned documents.",
)


@app.get("/health")
def health() -> dict:
    return {"status": "UP", "engineLoaded": _engine is not None, "lang": OCR_LANG}


@app.post("/warmup", dependencies=[Depends(require_service_token)])
def warmup() -> dict:
    """Load the model on demand so the first real request isn't slow."""
    get_engine()
    return {"status": "READY"}


@app.post("/ocr", response_model=OcrResult, dependencies=[Depends(require_service_token)])
def run_ocr(file: UploadFile = File(...)) -> OcrResult:
    payload, mime = _read_upload(file)

    # TemporaryDirectory removes everything even when inference raises.
    with tempfile.TemporaryDirectory(prefix="ocr_") as tmp:
        workdir = Path(tmp)

        if mime == "application/pdf":
            image_paths = _pdf_to_images(payload, workdir)
        else:
            single = workdir / "page_001.png"
            single.write_bytes(payload)
            image_paths = [single]

        pages: list[Page] = []
        for number, path in enumerate(image_paths, start=1):
            try:
                lines = _recognise(path.as_posix())
            except Exception:
                log.exception("Recognition failed on page %s", number)
                raise HTTPException(
                    status.HTTP_500_INTERNAL_SERVER_ERROR,
                    "The OCR engine failed while reading this document.",
                ) from None
            width, height = _dimensions(path)
            pages.append(Page(page=number, width=width, height=height,
                              lines=[Line(**l) for l in lines]))

    flat = [line for page in pages for line in page.lines]
    scores = [l.confidence for l in flat]
    mean = sum(scores) / len(scores) if scores else 0.0   # never divide by zero

    return OcrResult(
        lines=flat,
        pages=pages,
        lineCount=len(flat),
        meanConfidence=round(mean, 4),
        accuracy=round(mean * 100, 2),
        width=pages[0].width if pages else 0,
        height=pages[0].height if pages else 0,
        pageCount=len(pages),
    )


@app.exception_handler(HTTPException)
def http_error(_, exc: HTTPException) -> JSONResponse:
    return JSONResponse(status_code=exc.status_code, content={"error": exc.detail})


if __name__ == "__main__":
    import uvicorn
    # Default to loopback: this service has no user-level auth and should sit
    # behind the API, not on a public interface.
    uvicorn.run(app, host=os.getenv("OCR_HOST", "127.0.0.1"), port=int(os.getenv("OCR_PORT", 8000)))
