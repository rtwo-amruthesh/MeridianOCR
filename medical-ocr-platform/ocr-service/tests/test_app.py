"""
Tests for the OCR service.

PaddleOCR is stubbed out — these cover request handling, normalisation and the
edge cases the first version crashed on (blank pages, no detections, oversized
uploads, disguised file types), not the accuracy of the model itself.
"""

import importlib
import io
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import app as service  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
JPEG = b"\xff\xd8\xff" + b"\x00" * 64
PDF = b"%PDF-1.4" + b"\x00" * 64


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(service, "SERVICE_TOKEN", "")
    monkeypatch.setattr(service, "_dimensions", lambda p: (900, 1200))
    return TestClient(service.app)


def _stub(monkeypatch, lines):
    monkeypatch.setattr(service, "_recognise", lambda path: lines)


def _post(client, payload=PNG, name="scan.png"):
    return client.post("/ocr", files={"file": (name, io.BytesIO(payload), "image/png")})


# ─────────────── happy path ───────────────

def test_returns_lines_with_boxes_and_confidence(client, monkeypatch):
    _stub(monkeypatch, [
        {"text": "Haemoglobin 11.2 g/dL", "confidence": 0.95,
         "bbox": [[58, 398], [846, 398], [846, 419], [58, 419]]},
        {"text": "MRN: MD-4417-2290", "confidence": 0.64,
         "bbox": [[58, 210], [230, 210], [230, 231], [58, 231]]},
    ])
    res = _post(client)
    assert res.status_code == 200
    body = res.json()

    assert body["lineCount"] == 2
    assert body["accuracy"] == pytest.approx(79.5, abs=0.1)
    assert body["width"] == 900 and body["height"] == 1200
    assert body["pageCount"] == 1
    # The polygon is the whole point — it must survive the round trip.
    assert body["lines"][0]["bbox"][0] == [58, 398]


def test_pages_are_reported_separately(client, monkeypatch):
    _stub(monkeypatch, [{"text": "x", "confidence": 0.9, "bbox": None}])
    res = _post(client)
    assert len(res.json()["pages"]) == 1
    assert res.json()["pages"][0]["page"] == 1


# ─────────────── the crashes in v1 ───────────────

def test_blank_page_does_not_divide_by_zero(client, monkeypatch):
    """The original computed sum(conf)/len(conf) and raised ZeroDivisionError."""
    _stub(monkeypatch, [])
    res = _post(client)
    assert res.status_code == 200
    assert res.json()["lineCount"] == 0
    assert res.json()["meanConfidence"] == 0.0


def test_empty_upload_is_rejected(client):
    res = client.post("/ocr", files={"file": ("empty.png", io.BytesIO(b""), "image/png")})
    assert res.status_code == 400


def test_oversized_upload_is_rejected(client, monkeypatch):
    monkeypatch.setattr(service, "MAX_BYTES", 128)
    res = _post(client, PNG + b"\x00" * 512)
    assert res.status_code == 413


def test_content_type_is_sniffed_not_trusted(client):
    """A binary renamed .png with an image/png header must still be refused."""
    res = client.post("/ocr", files={"file": ("evil.png", io.BytesIO(b"MZ\x90\x00"), "image/png")})
    assert res.status_code == 415


def test_engine_failure_returns_500_not_a_stack_trace(client, monkeypatch):
    def boom(_):
        raise RuntimeError("paddle exploded")
    monkeypatch.setattr(service, "_recognise", boom)
    res = _post(client)
    assert res.status_code == 500
    assert "paddle exploded" not in res.text


# ─────────────── normalisation across PaddleOCR versions ───────────────

def test_normalise_2x_shape():
    raw = [[
        [[[10, 20], [110, 20], [110, 40], [10, 40]], ("Platelet Count", 0.968)],
        [[[10, 50], [110, 50], [110, 70], [10, 70]], ("268", 0.991)],
    ]]
    lines = service._normalise_2x(raw)
    assert [l["text"] for l in lines] == ["Platelet Count", "268"]
    assert lines[0]["confidence"] == 0.968
    assert lines[0]["bbox"][2] == [110.0, 40.0]


def test_normalise_2x_tolerates_none_page():
    """2.x returns [None] when nothing is detected."""
    assert service._normalise_2x([None]) == []
    assert service._normalise_2x([]) == []
    assert service._normalise_2x(None) == []


def test_normalise_3x_shape():
    raw = [{"res": {
        "rec_texts": ["Haematocrit", "34.6"],
        "rec_scores": [0.847, 0.972],
        "rec_polys": [[[10, 20], [110, 20], [110, 40], [10, 40]],
                      [[10, 50], [60, 50], [60, 70], [10, 70]]],
    }}]
    lines = service._normalise_3x(raw)
    assert [l["text"] for l in lines] == ["Haematocrit", "34.6"]
    assert lines[0]["confidence"] == 0.847


def test_poly_accepts_xyxy_boxes():
    assert service._poly([10, 20, 30, 40]) == [[10, 20], [30, 20], [30, 40], [10, 40]]


# ─────────────── auth ───────────────

def test_service_token_is_enforced_when_set(monkeypatch):
    monkeypatch.setattr(service, "SERVICE_TOKEN", "s3cret")
    monkeypatch.setattr(service, "_recognise", lambda p: [])
    monkeypatch.setattr(service, "_dimensions", lambda p: (10, 10))
    c = TestClient(service.app)

    assert _post(c).status_code == 401
    ok = c.post("/ocr",
                files={"file": ("scan.png", io.BytesIO(PNG), "image/png")},
                headers={"X-Service-Token": "s3cret"})
    assert ok.status_code == 200


def test_health_needs_no_token(monkeypatch):
    monkeypatch.setattr(service, "SERVICE_TOKEN", "s3cret")
    assert TestClient(service.app).get("/health").status_code == 200
