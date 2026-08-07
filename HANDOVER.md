# Meridian OCR Bench — project handover

**Written 6 August 2026.** For whoever picks this up next, human or AI. It assumes
you have the repository and nothing else — no memory of how it got here.

Read sections 1–3 to understand what this is. Read section 5 to run it. Read
section 8 before changing anything.

---

## 1. What this is

A medical document OCR platform. You upload a scanned lab report or clinical
form; it returns the text with per-line confidence scores and the polygon each
line was read from, laid over the original image so a human can check any value
against the pixels it came from.

The point is not the OCR. The point is the **review bench** — the interface that
lets a person verify machine output quickly, because in a clinical setting an
unverified extraction is worse than no extraction. Everything in the design
follows from that: the polygons, the confidence threshold slider, the
hover-to-link between text and region.

**Live frontend:** https://medical-ocr.netlify.app
**Repository:** https://github.com/rtwo-amruthesh/medical-ocr

**Not a validated medical device.** Everything it produces is machine-extracted
and unverified. No real patient data belongs in this repository or in any
deployment of it that isn't properly controlled.

---

## 2. Architecture

Three services, one static frontend.

```
  Browser  ──HTTPS──►  Spring Boot API  ──HTTP──►  Python OCR service
 (Netlify)              (port 8080)                  (port 8000)
                             │                            │
                             ▼                       PaddleOCR
                          MongoDB                   (2 GB of weights)
```

### medical-ocr-web — static frontend

Vanilla JS ES modules. No build step, no framework, no npm install. Deploys to
Netlify by copying files.

| File | Role |
|---|---|
| `index.html` | Single page; all views are sections toggled by `hidden` |
| `assets/js/config.js` | API base URL, storage keys, pipeline stage labels |
| `assets/js/store.js` | Auth token and settings in localStorage |
| `assets/js/api.js` | Every network call to the Spring API |
| `assets/js/mock.js` | Demo Mode — replays a bundled specimen with hardcoded boxes |
| `assets/js/extract.js` | Parses OCR lines into analyte/value/unit/reference rows |
| `assets/js/app.js` | Views, overlay drawing, state |
| `assets/demo/specimen-report.svg` | The Demo Mode document |

**Demo Mode matters.** With no backend, the site still walks the entire review
flow using a bundled synthetic report. That's what makes the Netlify link
worth sharing even when nothing else is running. It is *not* OCR — it replays a
fixture.

### backend — Spring Boot 3.3.5, Java 17

Context path is **`/api`**. Every URL below is relative to that. This trips
people up constantly: the API base URL must end in `/api`.

| Endpoint | Purpose |
|---|---|
| `POST /auth/register` | Create account |
| `POST /auth/login` | Returns JWT |
| `POST /ocr/upload` | Accepts file, returns id immediately, works async |
| `GET /ocr/progress/{id}` | Poll for stage and percentage |
| `GET /ocr/result/{id}` | Full extraction |
| `GET /ocr/history` | Past reads, without line data |
| `GET /ocr/file/{id}` | The stored original |
| `DELETE /ocr/result/{id}` | Remove a read |
| `GET /health` | Unauthenticated liveness |
| `/swagger-ui.html` | API docs |

Packages under `com.medicalocr`: `controller`, `service`, `security`,
`repository`, `model`, `dto`, `exception`, `config`.

### ocr-service — FastAPI + PaddleOCR

Internal only. Guarded by a shared token in the `X-Service-Token` header. It has
no user auth and **must never be exposed to the internet** — `docker-compose.yml`
uses `expose:` rather than `ports:` for exactly this reason.

- `POST /ocr` — image or PDF in, lines with polygons and confidence out
- `POST /warmup` — force model load
- `GET /health` — liveness

PDFs are rasterised internally with PyMuPDF at 200 DPI (`_pdf_to_images`). See
section 7 for why that matters.

### Data model

**users** — username and email both uniquely indexed. Password is a BCrypt hash.

**ocr_results** — the read. `userId` is indexed. Lines are nested documents, each
with text, confidence and a four-point polygon in source-image pixels.

---

## 3. How the project arrived in this state

Worth knowing, because it explains some of what you'll find.

The project was previously downloaded from a chat session in a way that
**flattened the directory structure and lost about two-thirds of the backend.**
What survived was 26 loose files in two folders — Java classes with package
declarations that no longer matched any directory, a frontend whose asset paths
pointed at folders that didn't exist, and two different `Dockerfile`s that had
collided by name.

The recovery, on 6 August 2026:

1. **Rebuilt the directory structure** so packages matched paths and the web
   client's `assets/` references resolved.
2. **Untangled the Dockerfiles.** One was the Python image, one the Java image.
   The nested `mnt/user-data/outputs/.../backend/Dockerfile` was not a stale
   duplicate — it was the second file surviving a name clash.
3. **Reconstructed 20 missing classes** — all DTOs, all entities, both
   repositories, the exception hierarchy, `JwtAuthenticationFilter`, `AsyncConfig`,
   `OpenApiConfig`, `HealthController` and the application class. Every signature
   was derived from an actual call site in the surviving code, not guessed.
4. **Fixed one runtime bug** (see 8.1).

**Consequence for you:** the reconstructed classes are consistent with how the
surviving code calls them, and the whole thing compiles and runs. But they were
inferred, not recovered. If something in a DTO or entity looks arbitrary, it
probably is — it was the minimum shape that satisfied the callers.

---

## 4. Current state

### Working

- Frontend live on Netlify, Demo Mode functional
- Full stack runs locally via Docker Compose
- Registration, login, JWT auth
- Real OCR on images and PDFs — **verified at 99.2% mean confidence, 115 lines,
  on a real medical PDF**
- History, per-line confidence, threshold slider, JSON export
- CI green: OCR service tests (13), `mvn verify`, both Docker images build

### Not working

- **PDF overlay is blank** (see 7.1) — images are fine
- Validation errors show "Bad Request" instead of naming the field (7.2)
- Field extraction only recognises lab-report structure; other document types
  return few or no structured fields (7.3)

### Not deployed

The backend runs on a laptop. Public access is via a Cloudflare quick tunnel,
which dies when the laptop closes. Section 6 covers making that permanent.

---

## 5. Running it locally

### Prerequisites

- **Docker Desktop** with WSL2. On Windows this needs virtualisation enabled in
  BIOS — see 9.1, it's the single most likely thing to block you.
- **Git**
- **cloudflared** (optional, only for sharing) — `cloudflared.exe` from
  github.com/cloudflare/cloudflared/releases

### First-time setup

```powershell
cd medical-ocr\medical-ocr-platform
```

Create `.env` (PowerShell, not cmd):

```powershell
$jwt   = [Convert]::ToBase64String((1..48 | % {Get-Random -Max 256}))
$token = -join ((1..64) | % { '{0:x}' -f (Get-Random -Max 16) })
$mpw   = -join ((1..32) | % { '{0:x}' -f (Get-Random -Max 16) })

@"
JWT_SECRET=$jwt
OCR_SERVICE_TOKEN=$token
MONGO_PASSWORD=$mpw
MONGO_USER=medicalocr
API_PORT=8080
JWT_EXPIRATION_MS=86400000
LOG_LEVEL=INFO
CORS_ALLOWED_ORIGINS=https://medical-ocr.netlify.app,http://localhost:5173
OCR_LANG=en
WEB_PORT=5173
"@ | Out-File -Encoding ascii .env
```

`.env` is gitignored. Never commit it. `docker-compose.yml` uses `${VAR:?message}`
syntax, so a missing secret fails loudly rather than starting insecurely.

```powershell
docker compose up --build
```

**First build takes 15–25 minutes** — Maven pulls the Spring dependency tree,
pip pulls PaddlePaddle (~126 MB) and PaddleOCR's dependency set. It looks frozen
during the quiet stretches. It isn't. Subsequent starts are ~30 seconds.

Ready when you see:

```
Tomcat started on port 8080 (http) with context path '/api'
Started MedicalOcrApplication in N seconds
```

Verify:

```
curl http://localhost:8080/api/health
→ {"status":"UP","service":"medical-ocr-api",...}
```

### Using it — local only

```powershell
cd medical-ocr\medical-ocr-web
python -m http.server 5173
```

Open `http://localhost:5173` → Settings → API base URL `http://localhost:8080/api`.

### Using it — shareable

```powershell
cloudflared.exe tunnel --url http://localhost:8080
```

Prints a URL like `https://random-words.trycloudflare.com`. No account needed.
Then at https://medical-ocr.netlify.app → Settings → paste **the URL plus `/api`**.

**The `/api` suffix is mandatory.** Without it every call 404s.

The tunnel URL changes on every restart. CORS doesn't need updating — it keys
off the Netlify origin, not the tunnel.

### First upload is slow

~30 seconds, because PaddleOCR loads its weights on first use. It may time out;
just upload again. Everything after is a few seconds per page. Section 7.4
explains why the weights aren't pre-baked.

### Stopping

1. Ctrl+C the tunnel
2. Ctrl+C compose, then `docker compose down`
3. Quit Docker Desktop from the tray

Data survives in the `mongo-data` and `uploads` named volumes. **`docker compose
down -v` wipes them** — accounts and history gone. Only use `-v` deliberately.

### Restarting

```
Docker Desktop → wait for "Engine running"
cd medical-ocr\medical-ocr-platform && docker compose up
cloudflared.exe tunnel --url http://localhost:8080
Netlify site → Settings → new tunnel URL + /api
```

---

## 6. Hosting

### The constraint

**PaddleOCR needs about 2 GB of RAM.** That single fact rules out most free
tiers. Everything below follows from it.

### Investigated and rejected

| Platform | Why not |
|---|---|
| **Render** | Private services have no free instance type. Free web tier is 512 MB — PaddleOCR OOMs. Real cost: ~$25/mo Standard for OCR + $7/mo Starter for API. |
| **Hugging Face Spaces** | Free CPU Basic is 2 vCPU / 16 GB, which would fit — but Docker Spaces require a paid plan. Verified directly: the SDK picker shows Docker as **Paid**. |
| **Fly.io, Railway** | Free tiers withdrawn or credit-limited. |

### Recommended: Oracle Cloud Always Free

An ARM VM at **4 vCPU / 24 GB RAM / 200 GB disk, free indefinitely.** Runs the
entire compose stack on one box — you wouldn't even need Atlas.

Catches, honestly:

- **Card required** for identity verification. Not charged; small auth and refund.
- **ARM capacity is frequently exhausted.** "Out of host capacity" is normal;
  it takes retrying over a day or two. Persistence usually wins.
- **You manage the VM** — SSH, firewall, TLS via Caddy or nginx. Half a day first time.
- **`paddlepaddle==2.6.2` is pinned to x86.** ARM64 will need a version bump or
  a different wheel. This is the one real technical risk in the plan.

Home region is permanent — pick Hyderabad or Mumbai from India.

### Deployment steps, once you have a host

1. **MongoDB Atlas** — an M0 free cluster already exists on this project's
   account. Connection string format:
   `mongodb+srv://user:pass@cluster.mongodb.net/medical_ocr?retryWrites=true&w=majority`
   The `/medical_ocr` path segment is required or it connects to `test`.
   Network access must allow `0.0.0.0/0` — cloud hosts have no fixed IP.
   *(Alternatively skip Atlas entirely and keep the Mongo container.)*
2. **Deploy the stack.** On a VM: clone, write `.env`, `docker compose up -d`.
3. **TLS.** Caddy is the least work — two lines of config gets automatic Let's Encrypt.
4. **Set `CORS_ALLOWED_ORIGINS`** to `https://medical-ocr.netlify.app` exactly.
   No trailing slash, `https` not `http`.
5. **Set `DEFAULT_API_BASE`** in `medical-ocr-web/assets/js/config.js` to the
   public API URL including `/api`. Commit and push; Netlify redeploys itself.
6. **Mount a volume at `/app/uploads`** or every restart loses stored scans and
   `GET /ocr/file/{id}` 404s on old records.

### If it fails, test in this order

1. `curl https://your-api/api/health` → is the API alive at all?
2. Register from the Netlify site. Network error with nothing in the API log
   means CORS. Check the origin string character by character.
3. Upload. Failure here with auth working means the OCR service — check its
   logs and its memory limit.

---

## 7. Pending work

Ordered by value.

### 7.1 PDF overlay is blank — highest value

**Symptom:** upload a PDF, get "The original scan isn't stored by the API, so
there's nothing to overlay here." Text and confidence are correct; boxes aren't drawn.

**Cause, precisely.** Two things combine:

- `app.js:setImageFromFile()` explicitly sets `state.imageUrl = null` for
  `application/pdf`, because a browser can't use a PDF as an `<img>` source.
- The Python service rasterises PDF pages to PNG in a **temp directory that is
  discarded after the request** (`_pdf_to_images`). The Java side stores the
  original PDF, so `GET /ocr/file/{id}` returns a PDF — still not usable as an image.

So the page image that the polygons refer to exists momentarily and is thrown away.

**Fix.** Have the OCR service return the rasterised page (base64 in the response,
or written to shared storage), have `OcrService` persist it alongside the record,
and have the frontend fetch `/ocr/file/{id}` for PDFs rather than giving up.
Multi-page PDFs need a page selector too — `OcrLine.page` already carries the number.

**Why it's top of the list:** the overlay is the entire point of the product, and
PDFs are what medical documents actually arrive as.

### 7.2 Validation errors don't say what's wrong

`RegisterRequest` has real rules — username 3–40 chars from `[A-Za-z0-9._-]`,
valid email, **password 12+ characters**. Break any and the UI shows a bare
"Bad Request".

`GlobalExceptionHandler` already populates `ErrorResponse.fieldErrors` with
field → message. The frontend ignores it. This is a small change in `api.js`
and the auth view, and it removes a genuinely confusing first-run experience.

### 7.3 Field extraction only understands lab reports

`extract.js` matches analyte / value / unit / reference-range patterns. On a lab
report it works. On the test PDF — a different kind of medical form — it found
one field out of 115 correctly-read lines.

Options, cheapest first: widen the patterns; add document-type detection with
per-type extractors; or send the lines to a model for structuring. The OCR is
not the weak link here, the parser is.

### 7.4 PaddleOCR warm-up segfaults during build

The Dockerfile line

```dockerfile
RUN python -c "from paddleocr import PaddleOCR; PaddleOCR(lang='en')" || true
```

prints `Segmentation fault (core dumped)` and the `|| true` swallows it. The
weights therefore aren't baked into the image; they download at first use.
That's the cause of the first-upload timeout.

Worth fixing before deploying — on a server that first request may be a user's,
not yours. Likely a version interaction between `paddlepaddle==2.6.2` and the
model download path in a container without a TTY.

### 7.5 No integration test

`mvn verify` compiles and runs unit tests but **never starts the Spring context.**
That's exactly how the `@Qualifier` bug (8.1) reached runtime with CI green.

One `@SpringBootTest` that boots the application would have caught it in seconds.
Testcontainers for a real Mongo would be better still. This is the highest-value
test you can add, and there's now a concrete incident to justify it.

### 7.6 Carried over from the original README

- **Progress tracking is in-memory** (`ProgressTracker`). Fine for one instance;
  needs Redis before a second API replica exists.
- **Uploads are never garbage-collected.** Disk grows without bound.
- **No rate limiting on `/auth/login`.** Trivially brute-forceable.
- **Image preprocessing** — deskew, denoise, adaptive threshold — would improve
  accuracy on phone-camera scans more than any other single change.

---

## 8. Things that will bite you

### 8.1 Lombok and @Qualifier — already fixed, don't undo it

`OcrService` uses `@RequiredArgsConstructor` with a `@Qualifier("ocrExecutor")`
field. **Lombok does not copy annotations onto generated constructor parameters
unless told to.** Spring then saw an unqualified `TaskExecutor`, found two
candidates (`ocrExecutor` and Spring's own `taskScheduler`), and refused to start
— in a crash loop, with a green CI build.

The fix is one line in `backend/lombok.config`:

```
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```

**If you ever regenerate or replace `lombok.config`, this line must survive.**

### 8.2 The `/api` suffix

Spring's context path is `/api`. Every API base URL needs it. This has caused
more confusion than anything else in this project.

### 8.3 CORS is an exact string match

`CORS_ALLOWED_ORIGINS` must match the browser's origin exactly. No trailing
slash. `https` not `http`. A mismatch shows as a network error in the browser
with **nothing in the API log** — because the request never arrives.

### 8.4 Windows setup

Assume half a day if the machine is fresh.

- **Virtualisation must be enabled in BIOS.** Docker Desktop says "Virtualization
  support not detected". On Acer: F2 on boot → Advanced → Intel Virtualization
  Technology (or SVM Mode on AMD) → Enable. May need a supervisor password set
  first before the option is editable.
- **WSL2 features**, as Administrator:
  ```
  dism /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart
  dism /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart
  ```
  Reboot.
- **WSL may be corrupted** (`REGDB_E_CLASSNOTREG`). `wsl --update` can't fix it.
  Install the MSI from github.com/microsoft/WSL/releases.
- **`winget` may not exist.** Download binaries directly.
- **PowerShell vs cmd.** `Add-Content`, `Out-File`, `$var` are PowerShell.
  cmd uses `echo text>>file` with **no space before `>>`**. Mixing them wastes time.

### 8.5 The frontend has no build step

Deliberate. No npm, no bundler, no `dist/`. Files are served as written. Netlify
config is: base directory `medical-ocr-web`, build command **empty**, publish `.`.
Don't add a build step without a reason — the absence of one is why deployment is
trivial.

### 8.6 Secrets

`.env` is gitignored and must stay that way. `docker-compose.yml` fails loudly
on missing secrets rather than falling back to defaults — that's intentional.
If credentials ever appear in a screenshot or a chat, rotate them.

---

## 9. Repository layout

```
medical-ocr/                          ← repo root
├── .github/workflows/ci.yml          ← must be at root to run
├── medical-ocr-platform/
│   ├── .env                          ← gitignored, create locally
│   ├── .env.example
│   ├── docker-compose.yml
│   ├── README.md
│   ├── backend/
│   │   ├── Dockerfile
│   │   ├── lombok.config             ← see 8.1
│   │   ├── pom.xml
│   │   └── src/main/java/com/medicalocr/
│   │       ├── MedicalOcrApplication.java
│   │       ├── config/ controller/ dto/ exception/
│   │       ├── model/ repository/ security/ service/
│   └── ocr-service/
│       ├── Dockerfile
│       ├── app.py
│       ├── requirements.txt
│       └── tests/test_app.py
└── medical-ocr-web/                  ← must stay a SIBLING
    ├── index.html
    ├── netlify.toml
    └── assets/{css,js,demo}/
```

**`medical-ocr-web` must remain a sibling of `medical-ocr-platform`.** The compose
`web` profile mounts `../medical-ocr-web`. Moving either breaks it.

---

## 10. If you have one hour

1. `docker compose up`, upload an **image** (not PDF), confirm the overlay draws.
   That's the product working as designed — worth seeing before changing anything.
2. Read `OcrService.java`. It's the spine: upload → store → async OCR → summarise
   → persist. Everything else supports it.
3. Fix 7.2 — surface `fieldErrors` in the UI. Small, self-contained, removes real
   confusion, and gets you oriented in both frontend and backend.

## If you have one day

Fix 7.1, the PDF overlay. It's the difference between a demo and a tool, and it
touches the Python service, the Java service and the frontend — so you'll end up
understanding the whole system.

## If you have one week

Deploy to Oracle Cloud, then 7.5 and 7.4. A real URL plus a build you can trust
is worth more than any feature.

---

## 11. Credentials and accounts

- **GitHub** — `rtwo-amruthesh/medical-ocr`, public
- **Netlify** — connected to that repo, auto-deploys `main`
- **MongoDB Atlas** — M0 free cluster `Cluster0`, region Mumbai, provisioned but
  **not currently used by anything**. Ready for when you deploy. Rotate the
  password before use.
- **Render** — nothing was created
- **Hugging Face** — account exists, ruled out

Local secrets live only in `medical-ocr-platform/.env`. If that file is lost,
regenerate it with the script in section 5 — but note that a new `MONGO_PASSWORD`
won't match the existing `mongo-data` volume. You'd need to keep the old password
or `docker compose down -v` and start fresh.
