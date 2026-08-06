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

The Spring Boot API needs a JVM, and the OCR service needs Python plus a few hundred MB of PaddleOCR model weights. Neither runs on Netlify. Host them on **Render**, **Railway** or **Fly.io**, and MongoDB on **MongoDB Atlas** (free tier is enough).

Then either:

- open **Settings** in the app and paste the API URL, or
- set `DEFAULT_API_BASE` in `assets/js/config.js` before deploying, so every visitor gets it

The URL must include the context path: `https://your-api.onrender.com/api`

---

## Connecting the backend

### 1. CORS

Your `SecurityConfig` currently allows only `localhost:3000` and `localhost:8080`. Add your Netlify origin:

```java
configuration.setAllowedOrigins(List.of(
    "http://localhost:5173",
    "https://your-site.netlify.app"
));
```

Also delete the `@CrossOrigin(origins = "*")` annotations on `AuthController` and `OcrController` — they contradict this config, and `*` with `allowCredentials(true)` is rejected by browsers.

### 2. Endpoints used

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/register` | create account, returns JWT |
| POST | `/auth/login` | sign in, returns JWT |
| POST | `/ocr/upload` | multipart, field name `file` |
| GET | `/ocr/progress/{id}` | pipeline state |
| GET | `/ocr/result/{id}` | final extraction |
| GET | `/ocr/history` | previous reads |

All except `/auth/*` send `Authorization: Bearer <token>`.

### 3. Response shape

The app reads your **current** shape without any changes:

```json
{ "id": "...", "fileName": "scan.png", "extractedText": ["line one", "line two"],
  "accuracy": 91.4, "summary": "...", "status": "COMPLETED", "processedAt": "..." }
```

With that shape you get everything except the overlay — there's nothing to draw regions from, and every line shares the same document-level confidence.

To light up the bench, return **per-line** data instead:

```json
{
  "id": "...",
  "fileName": "scan.png",
  "imageWidth": 900,
  "imageHeight": 1200,
  "lines": [
    { "text": "Haemoglobin 11.2 g/dL 12.0 - 15.5 L",
      "confidence": 0.953,
      "bbox": [[58,398],[846,398],[846,419],[58,419]] }
  ],
  "status": "COMPLETED"
}
```

`bbox` is a four-point polygon in **source-image pixels**. PaddleOCR already gives you this as `line[0]` — the current `app.py` throws it away. Keep it.

The client accepts `bbox` as a polygon, `[x, y, w, h]`, `{x,y,w,h}` or `{x1,y1,x2,y2}`, and `confidence` as either `0–1` or `0–100`.

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

- **PDF uploads** are accepted and forwarded, but the scan pane can't preview them — the backend would need to return a rendered page image.
- **Field extraction is heuristic.** It handles `Label: value` pairs and `name value unit range flag` rows. Multi-column layouts where OCR splits columns into separate lines will not parse cleanly. This is presented as a review surface, not a source of truth.
- **Reopening from history in live mode** shows the data without the scan, because the API doesn't serve the original file back. Add a `GET /ocr/file/{id}` endpoint to fix that.
- Progress state lives in a server-side `HashMap`, so it's lost on restart and unreliable across replicas.

---

## Not for clinical use

Everything this produces is machine-extracted and unverified. Exported JSON carries a disclaimer field saying so. Treat it as a demonstration of an extraction and review workflow, not a validated medical device.
