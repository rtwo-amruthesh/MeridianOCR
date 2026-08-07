# Meridian OCR Bench

Reads scanned clinical documents and returns the text with per-line confidence and
the polygon each line was read from — so every extracted value can be traced back
to the pixels it came from and checked by a person.

**[Frontend →](https://meridianocr.netlify.app)** — the viewer. It needs the
backend running to do anything; see below.

---

## Layout

```
medical-ocr/
├── HANDOVER.md              ← start here: full state, setup, pending work
├── medical-ocr-platform/    ← backend (Docker Compose)
│   ├── backend/             Spring Boot 3.3, Java 17
│   ├── ocr-service/         FastAPI + PaddleOCR
│   └── docker-compose.yml
└── medical-ocr-web/         ← static frontend (Netlify)
```

`medical-ocr-web` must stay a **sibling** of `medical-ocr-platform` — the compose
`web` profile mounts `../medical-ocr-web`.

---

## Quick start

```bash
cd medical-ocr-platform
cp .env.example .env      # then generate the three secrets — see that README
docker compose up --build
```

First build takes 15–25 minutes (Maven tree, PaddlePaddle wheels, model weights).
Later starts are about 30 seconds.

API on `http://localhost:8080/api`, docs at `/api/swagger-ui.html`.

For the web client, serve `medical-ocr-web` with any static server and point its
Settings at `http://localhost:8080/api`. **The `/api` suffix is required.**

---

## Where to read what

| Question | File |
|---|---|
| What's the current state? What should I do next? | **`HANDOVER.md`** |
| How does the backend work? What's the API? | `medical-ocr-platform/README.md` |
| How does the frontend work? How do I deploy it? | `medical-ocr-web/README.md` |

---

## Status

Working: auth, real OCR on images and PDFs, review bench with region linking,
history, JSON export. Verified at 99.2% mean confidence over 115 lines on a real
medical PDF.

Known gaps: PDF overlay is blank (text is fine, there's no page image to draw on),
field extraction only understands lab-report structure, validation errors don't
name the field. All detailed in `HANDOVER.md`.

Not currently deployed — the backend runs locally.

---

## Not for clinical use

Everything this produces is machine-extracted and unverified. It demonstrates an
extraction and review workflow. It is not a validated medical device, and no real
patient data belongs in this repository.
