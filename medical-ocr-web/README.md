# Meridian OCR Bench

A verification console for machine-read clinical documents. Upload a scanned report, watch it move through the pipeline, then check every extracted field against the pixels it came from.

Built as the frontend for a three-tier stack: **Angular-free static web app → Spring Boot API → PaddleOCR service**.

---

## What it does

- **Sign in / register** against the Spring Boot JWT endpoints
- **Upload** a scan by drag-drop or file picker, with client-side type and size checks
- **Live pipeline** driven by the API's own `ProgressResponse` states — Uploading, Processing, Extracting, Structuring, Ready
- **The bench** — the source scan on one side, extracted data on the other. Hovering a region highlights its line, and hovering a line highlights its region. This is the point of the whole app: nothing is asserted without a way to check it.
- **Review threshold** — a live slider. Anything read below the threshold turns amber in the overlay, the field list and the line list simultaneously. Drag it to see how much of the document you'd actually trust at 90% vs 75%.
- **Structured fields** — demographics and analyte rows parsed out of the raw lines, with reference ranges and H/L flags. Flags marked `*` were computed from the printed range rather than read off the page.
- **History** of previous reads, click through to reopen
- **Download JSON** in a shape a HIS/LIS could consume, including which fields need human review
- Works on mobile, respects `prefers-reduced-motion`, keyboard-navigable

---

## Deploying to Netlify

Netlify serves static files. This app is static — no build step, no bundler, no `npm install`.

**Option A — drag and drop**

1. Go to Netlify → *Add new site* → *Deploy manually*
2. Drag this whole folder onto the drop zone
3. Done

**Option B — from Git**

1. Push this folder to GitHub
2. Netlify → *Import from Git* → pick the repo
3. Build command: *(leave empty)* · Publish directory: `.`

`netlify.toml` already sets the SPA redirect, cache headers and a CSP.

### Your backend does *not* go on Netlify

The Spring Boot API needs a JVM, and the OCR service needs Python plus about 2 GB of
PaddleOCR model weights. Neither runs on Netlify.

That 2 GB is the binding constraint on where the backend can live — most free tiers
cap at 512 MB and PaddleOCR will OOM. See `../medical-ocr-platform/README.md` for the
platform comparison. MongoDB Atlas M0 is genuinely free and sufficient.

For a demo without deploying anything, run the stack locally and expose it with a
Cloudflare quick tunnel:

```bash
cloudflared tunnel --url http://localhost:8080
```

Paste the printed URL plus `/api` into Settings. No account required; the URL changes
each restart.

---

## Connecting the backend

### 1. Point the client at the API

Either open **Settings** in the app and paste the URL, or set `DEFAULT_API_BASE` in
`assets/js/config.js` before deploying so every visitor gets it.

**The URL must include the `/api` context path**: `https://your-api.example.com/api`.
Without it every call 404s. This is the single most common setup mistake.

### 2. CORS

The API reads allowed origins from the `CORS_ALLOWED_ORIGINS` environment variable —
a comma-separated list, no code change needed:

```
CORS_ALLOWED_ORIGINS=https://medical-ocr.netlify.app,http://localhost:5173
```

It is an **exact string match**. No trailing slash, `https` not `http`. A mismatch
shows in the browser as a network error with *nothing in the API log*, because the
request never arrives.

### 3. Endpoints used

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register` | create account, returns JWT |
| POST | `/auth/login` | sign in, returns JWT |
| POST | `/ocr/upload` | multipart, field name `file` |
| GET | `/ocr/progress/{id}` | pipeline state |
| GET | `/ocr/result/{id}` | final extraction |
| GET | `/ocr/history` | previous reads |
| GET | `/ocr/file/{id}` | the original scan |
| DELETE | `/ocr/result/{id}` | remove a read |

All except `/auth/*` send `Authorization: Bearer <token>`.

### 4. Response shape

The API returns per-line data, which is what lights up the bench:

```json
{
  "id": "...",
  "fileName": "scan.png",
  "imageWidth": 900,
  "imageHeight": 1200,
  "lines": [
    { "text": "Haemoglobin 11.2 g/dL 12.0 - 15.5 L",
      "confidence": 0.953,
      "bbox": [[58,398],[846,398],[846,419],[58,419]],
      "page": 1 }
  ],
  "extractedText": ["Haemoglobin 11.2 g/dL 12.0 - 15.5 L"],
  "accuracy": 91.4,
  "status": "COMPLETED"
}
```

`bbox` is a four-point polygon in **source-image pixels**. `extractedText` is
retained so anything written against the older `string[]` contract keeps working.

The client also accepts `bbox` as `[x, y, w, h]`, `{x,y,w,h}` or `{x1,y1,x2,y2}`,
and `confidence` as either `0–1` or `0–100`.

---

## Demo Mode

The deployed site is walkable with no backend at all. Demo Mode reads a bundled synthetic haematology report (`assets/demo/specimen-report.svg`) with hand-registered bounding boxes, so the overlay lines up exactly as it would with real PaddleOCR output. Two fields are deliberately low-confidence so the review workflow has something to catch.

**The specimen is invented.** No real patient data appears anywhere in this repository, and none should be added.

---

## Running locally

Any static server works — ES modules need HTTP, not `file://`:

```bash
python3 -m http.server 5173
# or
npx serve .
```

Then open `http://localhost:5173`.

---

## Structure

```
.
├── index.html                    all views in one document
├── netlify.toml                  headers, caching, SPA redirect
├── _redirects
└── assets/
    ├── css/styles.css            token system + components
    ├── demo/specimen-report.svg  synthetic specimen
    └── js/
        ├── config.js             endpoint + limits
        ├── store.js              storage with in-memory fallback
        ├── api.js                HTTP client + response normalisation
        ├── mock.js               Demo Mode backend
        ├── extract.js            raw lines → typed clinical fields
        └── app.js                controller
```

No dependencies. Two web fonts.

---

## Known limits

- **PDF uploads read correctly but show no overlay.** `setImageFromFile()` nulls the
  image for `application/pdf` because a browser can't use a PDF as an `<img>` source,
  and the OCR service discards the page images it rasterises internally. The text,
  confidence and polygons are all correct — there is just nothing to draw them on.
  Fixing it means having the backend return and store the rendered page. **Images
  are unaffected and the overlay works fully for them.**
- **Field extraction is heuristic and lab-report shaped.** It handles `Label: value`
  pairs and `name value unit range flag` rows. On a document with a different
  structure it may find almost nothing even when every line was read perfectly.
  Multi-column layouts where OCR splits columns into separate lines will not parse
  cleanly. This is a review surface, not a source of truth.
- **Validation failures show a bare "Bad Request".** The API sends a `fieldErrors`
  map naming the offending field; this client ignores it. Worth wiring up — the
  password minimum is 12 characters and there is currently no way for a user to
  discover that.
- Progress state lives in a server-side map, so it's lost on restart and unreliable
  across replicas.

---

## Not for clinical use

Everything this produces is machine-extracted and unverified. Exported JSON carries a disclaimer field saying so. Treat it as a demonstration of an extraction and review workflow, not a validated medical device.
