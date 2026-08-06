# Recovery note — 6 August 2026

What you downloaded was a flat dump of individual files. Two things had gone wrong:
the directory structure was lost, and roughly two thirds of the backend was never
in the download at all. Both are fixed here.

---

## What was wrong

**Structure.** Every file sat in one of two flat folders. Java needs its package
declaration to match its directory — `com.medicalocr.controller.AuthController`
has to live at `src/main/java/com/medicalocr/controller/AuthController.java` — so
Maven would not have compiled a single class. The web client referenced
`assets/css/…` and `assets/js/…` which did not exist, so nothing would have
loaded past the bare HTML.

**Two different Dockerfiles collided.** The one at the top of `files (1)` is the
**Python** image. The one buried at `files (1)/mnt/user-data/outputs/…/backend/`
is the **Java** image. Same filename, different services — the nested path was
not a stale duplicate, it was the second Dockerfile surviving a name clash. Both
are restored to their correct folders.

**Missing classes.** Seventeen classes were imported by the files you had, and
were not present. Three more were referenced from within their own package, so
they did not show up in the import list either: `ApiException`,
`JwtAuthenticationFilter`, and the `ocrExecutor` bean. Nothing compiled without
them.

---

## Reconstructed (20 files)

Written to match exactly how the surviving code calls them — every constructor
signature, every getter, every repository method name is derived from an actual
call site, not guessed.

| Package | Classes |
|---|---|
| `com.medicalocr` | `MedicalOcrApplication` |
| `config` | `AsyncConfig`, `OpenApiConfig` |
| `controller` | `HealthController` |
| `security` | `JwtAuthenticationFilter` |
| `dto` | `AuthResponse`, `LoginRequest`, `RegisterRequest`, `ErrorResponse`, `HistoryItem`, `LineDto`, `OcrResponse`, `ProgressResponse` |
| `model` | `User`, `OcrLine`, `OcrResult` |
| `repository` | `UserRepository`, `OcrResultRepository` |
| `exception` | `ApiException`, `BadRequestException`, `NotFoundException`, `ConflictException`, `UnprocessableException` |

Plus `backend/lombok.config` (the backend Dockerfile copies it and the build
fails if it is absent), `ocr-service/requirements-dev.txt` (the README's test
command installs from it), and a `.dockerignore` for each service.

Three decisions worth knowing about, because they are load-bearing:

- **`findByIdAndUserId` enforces ownership in the query, not after it.** Fetching
  by id and then comparing the owner in Java is one forgotten check away from the
  IDOR the v2 rewrite closed. Every read path goes through this method.
- **`JwtAuthenticationFilter` never rejects anything itself.** A bad token leaves
  the security context empty and `SecurityConfig` decides what that means, so the
  401 body stays consistent with every other error instead of being written by
  hand inside a filter.
- **`@EnableScheduling` is on the application class.** `ProgressTracker.sweep()`
  is a `@Scheduled` method; without the annotation the progress map grows forever
  and the memory-leak fix silently does nothing.

Also added to `application.yml`: the `ocr.executor.*` keys `AsyncConfig` reads.

---

## Verified

- **OCR service tests: 13 passed.** Run in this exact tree.
- **Frontend: all assets resolve** — `index.html`, `styles.css`, the six ES
  modules and `specimen-report.svg` all served 200 over HTTP, and `mock.js`'s
  `DEMO_IMAGE` path matches where the SVG now sits.
- **Java: every `com.medicalocr` import resolves to a class that exists.**
  Checked mechanically across all 37 files.

## Not verified

**The Java code has not been compiled.** Maven Central is not reachable from my
sandbox, so `mvn verify` could not run. The reconstructed classes are consistent
with every call site I could find, but the compiler is the only thing that proves
it. Run this first, before anything else:

```
cd medical-ocr-platform/backend
mvn -B verify
```

If something fails there it will be a small signature mismatch in a reconstructed
DTO or entity — send me the compiler output and it is a quick fix.

---

## Running it

```
cd medical-ocr-platform
cp .env.example .env
```

Generate the three secrets into `.env`, then delete the blank placeholders at the
top of the file:

```
openssl rand -base64 48     → JWT_SECRET
openssl rand -hex 32        → OCR_SERVICE_TOKEN
openssl rand -hex 16        → MONGO_PASSWORD
```

Then `docker compose up --build`. API on `http://localhost:8080/api`, docs at
`/api/swagger-ui.html`. First start is slow while PaddleOCR pulls model weights.

`docker compose --profile with-web up --build` also serves the web client at
`http://localhost:5173` — this is why `medical-ocr-web` must stay a **sibling**
of `medical-ocr-platform`. The compose file mounts `../medical-ocr-web`. Moving
either folder breaks that mount.

---

## Still open

Carried over from the original README, unchanged:

- Progress is process-local. Redis before more than one API replica.
- Uploads are never garbage-collected.
- No rate limiting on `/auth/login`.
- No integration tests against a real Mongo — Testcontainers.
- Image preprocessing (deskew, denoise, threshold) would lift accuracy on
  phone-camera scans more than any other single change.

---

## Not for clinical use

Everything this produces is machine-extracted and unverified. It demonstrates an
extraction and review workflow. It is not a validated medical device, and no real
patient data belongs in this repository.
