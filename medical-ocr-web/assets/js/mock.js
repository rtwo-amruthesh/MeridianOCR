/* ------------------------------------------------------------------
   Demo Mode.

   A local stand-in for the API so the deployed site is walkable without a
   backend. The bounding boxes below are registered against the coordinates
   of assets/demo/specimen-report.svg, so the overlay lines up exactly the
   way it would with real PaddleOCR polygons.

   The specimen is invented. No real patient data appears anywhere here.
   ------------------------------------------------------------------ */

import { store } from "./store.js";

export const DEMO_IMAGE = "assets/demo/specimen-report.svg";
export const DEMO_IMAGE_SIZE = { w: 900, h: 1200 };

const L = (text, confidence, x, y, w, h) => ({ text, confidence, bbox: [[x, y], [x + w, y], [x + w, y + h], [x, y + h]] });

const SPECIMEN_LINES = [
  L("MERIDIAN DIAGNOSTICS", 0.987, 58, 64, 436, 30),
  L("1420 Carter Road, Bengaluru 560103", 0.962, 58, 99, 352, 18),
  L("Accredited Clinical Laboratory · NABL M-0099", 0.918, 58, 120, 318, 17),
  L("HAEMATOLOGY REPORT", 0.974, 574, 70, 270, 21),
  L("Page 1 of 1", 0.945, 768, 97, 76, 17),

  L("Patient Name: PRIYA RAMACHANDRAN", 0.958, 58, 180, 338, 21),
  L("MRN: MD-4417-2290", 0.642, 58, 210, 172, 21),
  L("Date of Birth: 14-Mar-1986", 0.731, 58, 240, 216, 21),
  L("Sex: F", 0.955, 518, 180, 54, 21),
  L("Collected: 02-Aug-2026 08:12", 0.889, 518, 210, 234, 21),
  L("Reported: 02-Aug-2026 14:40", 0.901, 518, 240, 230, 21),
  L("Ordering Physician: Dr. S. Krishnan", 0.934, 58, 272, 280, 21),
  L("Specimen: Whole Blood (EDTA)", 0.946, 518, 272, 252, 21),

  L("TEST   RESULT   UNIT   REF. RANGE   FLAG", 0.966, 58, 352, 805, 18),

  L("Haemoglobin 11.2 g/dL 12.0 - 15.5 L", 0.953, 58, 398, 805, 21),
  L("Total Leucocyte Count 12.8 10^3/uL 4.0 - 11.0 H", 0.911, 58, 438, 805, 21),
  L("Platelet Count 268 10^3/uL 150 - 410", 0.968, 58, 478, 805, 21),
  L("Haematocrit 34.6 % 36.0 - 46.0 L", 0.847, 58, 518, 805, 21),
  L("Mean Corpuscular Volume 82.4 fL 80.0 - 100.0", 0.923, 58, 558, 805, 21),
  L("Neutrophils 74 % 40 - 75", 0.958, 58, 598, 805, 21),
  L("Lymphocytes 18 % 20 - 40 L", 0.795, 58, 638, 805, 21),

  L("COMMENTS", 0.972, 58, 711, 110, 19),
  L("Mild microcytic anaemia with neutrophilic leucocytosis.", 0.887, 58, 740, 442, 21),
  L("Clinical correlation and iron studies advised.", 0.903, 58, 766, 352, 21),

  L("Verified by: Dr. A. Menon, MD (Pathology)", 0.941, 58, 864, 336, 21),
  L("Electronically signed · 02-Aug-2026 14:40 IST", 0.812, 58, 892, 306, 18),
  L("Authorised Signatory", 0.889, 58, 967, 134, 16),
];

const MEAN = Math.round(
  (SPECIMEN_LINES.reduce((a, l) => a + l.confidence, 0) / SPECIMEN_LINES.length) * 1000
) / 10;

const SUMMARY =
  `Extracted 27 lines from a single-page haematology report.\n\n` +
  `Detected document type: laboratory result (haematology panel).\n` +
  `Analytes parsed: 7. Values outside the printed reference range: 4 ` +
  `(Haemoglobin, Total Leucocyte Count, Haematocrit, Lymphocytes).\n\n` +
  `Two identifier fields — MRN and Date of Birth — were read at low confidence ` +
  `and should be checked against the source before this record is filed.`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const STAGES = [
  { status: "UPLOADING",   progress: 10,  message: "Uploading scan…",            wait: 420 },
  { status: "PROCESSING",  progress: 32,  message: "Preparing image…",           wait: 620 },
  { status: "EXTRACTING",  progress: 58,  message: "Detecting text regions…",    wait: 780 },
  { status: "EXTRACTING",  progress: 74,  message: "Recognising 27 lines…",      wait: 640 },
  { status: "SUMMARIZING", progress: 88,  message: "Structuring fields…",        wait: 560 },
  { status: "COMPLETED",   progress: 100, message: "Ready to verify",            wait: 260 },
];

function seedHistory() {
  const existing = store.get("demo:history", null);
  if (existing) return existing;
  const now = Date.now();
  const seeded = [
    { id: "demo-0002", fileName: "haematology-0817.png", accuracy: 91.4, status: "COMPLETED", processedAt: new Date(now - 864e5).toISOString() },
    { id: "demo-0003", fileName: "discharge-summary-p2.pdf", accuracy: 84.7, status: "COMPLETED", processedAt: new Date(now - 2 * 864e5).toISOString() },
    { id: "demo-0004", fileName: "requisition-scan-blurred.jpg", accuracy: 61.2, status: "FAILED", processedAt: new Date(now - 5 * 864e5).toISOString() },
  ];
  store.set("demo:history", seeded);
  return seeded;
}

export function createMockApi() {
  return {
    isMock: true,

    async login(username) {
      await sleep(320);
      return { token: "demo.token", type: "Bearer", username: username || "demo", email: `${username || "demo"}@example.org` };
    },

    async register(payload) {
      await sleep(420);
      return { token: "demo.token", type: "Bearer", username: payload.username, email: payload.email };
    },

    async upload() {
      await sleep(300);
      const id = "demo-" + Date.now().toString(36);
      return { id, fileName: "specimen-report.svg", status: "PROCESSING", message: "Demo Mode is reading the bundled specimen." };
    },

    /** Drives the pipeline UI by calling back on each stage. */
    async run(onStage, signal) {
      for (const s of STAGES) {
        if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
        await sleep(s.wait);
        onStage(s);
      }
    },

    async result(id) {
      await sleep(200);
      const record = {
        id,
        fileName: "specimen-report.svg",
        status: "COMPLETED",
        processedAt: new Date().toISOString(),
        accuracy: MEAN,
        summary: SUMMARY,
        imageWidth: DEMO_IMAGE_SIZE.w,
        imageHeight: DEMO_IMAGE_SIZE.h,
        lines: SPECIMEN_LINES.map((l) => ({ ...l })),
      };
      const hist = seedHistory();
      if (!hist.some((h) => h.id === id)) {
        hist.unshift({ id, fileName: record.fileName, accuracy: record.accuracy, status: "COMPLETED", processedAt: record.processedAt });
        store.set("demo:history", hist.slice(0, 25));
      }
      return record;
    },

    async history() {
      await sleep(220);
      return seedHistory();
    },

    async ping() {
      await sleep(150);
      return { reachable: true, status: 200 };
    },
  };
}
