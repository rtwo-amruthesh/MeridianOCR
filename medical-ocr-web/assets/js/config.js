/* Runtime configuration.
   Edit DEFAULT_API_BASE before deploying, or leave it blank and let each
   visitor set the endpoint in Settings. Whatever the user saves wins. */

export const DEFAULT_API_BASE = "";   // e.g. "https://medical-ocr-api.onrender.com/api"

export const APP = {
  name: "OCR Bench",
  org: "Meridian",
  maxFileBytes: 10 * 1024 * 1024,
  acceptTypes: ["image/png", "image/jpeg", "image/webp", "application/pdf"],
  pollIntervalMs: 900,
  pollTimeoutMs: 120000,
  defaultThreshold: 85,
};

export const STEPS = ["UPLOADING", "PROCESSING", "EXTRACTING", "SUMMARIZING", "COMPLETED"];
