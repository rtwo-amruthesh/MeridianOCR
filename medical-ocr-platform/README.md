# Medical OCR Platform

Reads scanned clinical documents and returns per-line text, confidence and bounding polygons — so every extracted value can be traced back to the pixels it came from and checked by a person.

```
┌──────────────┐    JWT     ┌──────────────┐   shared    ┌──────────────┐
│  web client  │ ─────────▶ │  Spring Boot │   secret    │  PaddleOCR   │
│  (static)    │            │     API      │ ──────────▶ │   service    │
└──────────────┘            └──────┬───────┘             └──────────────┘
                                   │
                             ┌─────▼─────┐
                             │  MongoDB  │
                             └───────────┘
```

| Component | Stack | Role |
|---|---|---|
| `ocr-service/` | Python 3.10, FastAPI, PaddleOCR | Recognition. Text, confidence, polygons. |
| `backend/` | Java 17, Spring Boot 3.3 | Auth, orchestration, persistence, ownership. |
| `../medical-ocr-web/` | Static HTML/CSS/JS | Upload and review interface. |

---

## Running it

```bash
cp .env.example .env

# generate the three secrets
echo "JWT_SECRET=$(openssl rand -base64 48)"   >> .env
echo "OCR_SERVICE_TOKEN=$(openssl rand -hex 32)" >> .env
echo "MONGO_PASSWORD=$(openssl rand -hex 16)"  >> .env
# then delete the blank placeholders at the top of .env

docker compose up --build
```

API on `http://localhost:8080/api`, docs at `http://localhost:8080/api/swagger-ui.html`.

To serve the web client alongside it: `docker compose --profile with-web up --build` → `http://localhost:5173`.

First start is slow — PaddleOCR downloads model weights. `docker compose logs -f ocr-service` to watch.

### Tests

```bash
cd ocr-service && pip install -r requirements-dev.txt && pytest tests -q
cd backend     && mvn verify
```

---

## What changed from v1

Grouped by why it mattered.

### Wouldn't run

| Problem | Fix |
|---|---|
| `AuthController` called `Map.of()` with no `java.util.Map` import — the class did not compile | Controllers return typed DTOs; errors go through `GlobalExceptionHandler` |
| **Login could never succeed.** `authenticationManager.authenticate()` was called with no `UserDetailsService` bean anywhere, so nothing knew how to load a user from Mongo | `MongoUserDetailsService` + `DaoAuthenticationProvider` |
| `PaddleOCR(use_gpu=…, use_textline_orientation=…)` mixed 2.x and 3.x arguments — fails on both | `_build_engine()` tries known argument sets; `_normalise_2x` / `_normalise_3x` handle either return shape |
| `result[0]` raised `TypeError` when nothing was detected (2.x returns `[None]`) | Guarded, returns an empty line list |
| `sum(confidences)/len(confidences)` — **ZeroDivisionError on a blank page** | Guarded, mean is `0.0` |
| `file.upload.max-size: 10MB` was a custom key nothing read, so Spring's 1 MB default applied and any larger upload failed | `spring.servlet.multipart.max-file-size`, the property Spring actually enforces |

### Security

| Problem | Fix |
|---|---|
| **Path traversal.** `getOriginalFilename()` is attacker-controlled and was resolved straight against the upload directory; a UUID prefix doesn't stop `../../../etc/…` | `StorageService.safeName()` reduces to the final segment and scrubs it; `resolveInsideRoot()` verifies containment before any read or write |
| **JWT signing key committed in plaintext** — anyone with the repo could forge a token for any account | `JWT_SECRET` env var; `JwtTokenProvider` refuses to start on the old placeholder or a key under 32 bytes |
| OCR service was **completely unauthenticated** and bound to all interfaces | `X-Service-Token` shared secret; binds to loopback by default; not published in compose |
| **IDOR:** `/ocr/progress/{id}` took no principal, so any user could poll any job | Takes `Authentication`; ownership enforced in the Mongo query via `findByIdAndUserId` |
| CORS contradicted itself — `@CrossOrigin("*")` on controllers vs. specific origins with `allowCredentials(true)` in config (illegal per spec) | Annotations removed; origins come from `CORS_ALLOWED_ORIGINS` |
| No file type validation | Content type + extension allowlist in Java; **magic-byte sniffing** in Python, because a declared content type is just a claim |
| `Map.of("error", e.getMessage())` leaked Mongo and Spring internals to clients | `GlobalExceptionHandler` — typed exceptions keep their status, everything else becomes a generic 500 |

### Correctness

| Problem | Fix |
|---|---|
| Progress lived in a plain `HashMap` written from async threads and read from request threads — unsynchronised concurrent mutation, and it grew forever | `ProgressTracker` with `ConcurrentHashMap` + scheduled sweep, and a fallback to persisted status after restart |
| One `OcrResult` object was mutated and saved by two threads — lost updates | Request thread saves once; the worker re-reads by id before writing |
| `async def` handler ran blocking CPU inference, **stalling the FastAPI event loop** | Plain `def` — FastAPI runs it in a threadpool |
| Global `PaddleOCR` instance shared across concurrent requests (not thread-safe) | Serialised behind `_infer_lock` |
| `os.remove()` outside a `finally` leaked a temp file on every failure | `tempfile.TemporaryDirectory()` |
| `CompletableFuture.supplyAsync` with no executor ran on the common ForkJoinPool | Bounded `ocrExecutor` pool |
| `transferTo(File)` with a relative path resolves against the servlet temp dir on some containers | `Files.copy` |
| Blocking `WebClient.block()` pulled in WebFlux to do synchronous work | `RestClient` |
| `accuracy` was mean confidence, not accuracy | Still exposed as `accuracy` for compatibility, documented as mean confidence, and `meanConfidence` added |

### Capability

- **Bounding polygons are kept.** `line[0]` used to be discarded. It is now carried through Python → Java → Mongo → client, which is what makes region-linked review possible.
- **PDF support** via PyMuPDF, rasterised per page with per-page results.
- `GET /ocr/file/{id}` serves the original scan back, so a saved result can be reopened beside it.
- `DELETE /ocr/result/{id}`, `/health` on both services, OpenAPI docs, Docker images, GitHub Actions CI.
- `SummaryService` replaces the keyword-grep that was labelled "medical document summarization logic". It now states only what the code can actually determine and says plainly that nothing was interpreted.

---

## API

Base path `/api`. Everything except `/auth/*` and `/health` needs `Authorization: Bearer <token>`.

| Method | Path | Returns |
|---|---|---|
| POST | `/auth/register` | 201 + token |
| POST | `/auth/login` | 200 + token |
| POST | `/ocr/upload` | 202 + `{id, status}` |
| GET | `/ocr/progress/{id}` | pipeline state |
| GET | `/ocr/result/{id}` | full extraction |
| GET | `/ocr/history` | your previous reads |
| GET | `/ocr/file/{id}` | the original scan |
| DELETE | `/ocr/result/{id}` | 204 |

Result shape:

```json
{
  "id": "…", "fileName": "report.png", "status": "COMPLETED",
  "accuracy": 91.4, "lineCount": 27, "pageCount": 1,
  "imageWidth": 900, "imageHeight": 1200,
  "lines": [
    { "text": "Haemoglobin 11.2 g/dL 12.0 - 15.5 L",
      "confidence": 0.953,
      "bbox": [[58,398],[846,398],[846,419],[58,419]],
      "page": 1 }
  ],
  "extractedText": ["Haemoglobin 11.2 g/dL 12.0 - 15.5 L"],
  "summary": "…"
}
```

`extractedText` is retained so anything written against the original `string[]` contract keeps working.

---

## Deploying

Render, Railway or Fly.io — both services have Dockerfiles. MongoDB Atlas for the database. The web client goes on Netlify.

Set on the API: `JWT_SECRET`, `MONGODB_URI`, `OCR_SERVICE_URL`, `OCR_SERVICE_TOKEN`, `CORS_ALLOWED_ORIGINS`.
Set on the OCR service: `OCR_SERVICE_TOKEN`.

Give the OCR service **at least 2 GB of memory** — PaddleOCR's models won't fit in a 512 MB tier.

Keep the OCR service on a private network. It has no user-level auth beyond the shared secret and should never face the internet directly.

---

## Still open

- Progress is process-local. Move to Redis before running more than one API replica.
- Uploads are never garbage-collected. Add a scheduled cleanup, or push to object storage with a lifecycle rule.
- No rate limiting on `/auth/login`.
- No integration tests against a real Mongo — add Testcontainers.
- Image preprocessing (deskew, denoise, adaptive threshold) would measurably lift accuracy on phone-camera scans.

---

## Not for clinical use

Everything this produces is machine-extracted and unverified. It is a demonstration of an extraction and review workflow, not a validated medical device. Do not put real patient data in this repository.
