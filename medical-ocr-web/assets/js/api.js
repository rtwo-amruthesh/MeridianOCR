/* ------------------------------------------------------------------
   Client for the Spring Boot API.

   Tolerates two response shapes from /ocr/result so the bench works both
   with the current backend (extractedText: string[]) and with the richer
   shape once per-line confidence and bounding boxes are returned.
   ------------------------------------------------------------------ */

export class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

const trimSlash = (s) => (s || "").replace(/\/+$/, "");

export function createApi(getConfig) {
  async function call(path, { method = "GET", body, headers = {}, isForm = false, signal } = {}) {
    const { apiBase, token } = getConfig();
    if (!apiBase) throw new ApiError("No API endpoint configured. Add one in Settings, or switch on Demo Mode.", 0);

    const h = { Accept: "application/json", ...headers };
    if (token) h.Authorization = `Bearer ${token}`;
    if (!isForm && body !== undefined) h["Content-Type"] = "application/json";

    let res;
    try {
      res = await fetch(trimSlash(apiBase) + path, {
        method,
        headers: h,
        body: isForm ? body : body !== undefined ? JSON.stringify(body) : undefined,
        signal,
      });
    } catch (e) {
      if (e.name === "AbortError") throw e;
      throw new ApiError("Can't reach the API. Check the endpoint, that the service is awake, and that CORS allows this origin.", 0);
    }

    const raw = await res.text();
    let data = null;
    if (raw) { try { data = JSON.parse(raw); } catch { data = { message: raw }; } }

    if (!res.ok) {
      const msg = data?.error || data?.message || `Request failed (${res.status})`;
      throw new ApiError(msg, res.status);
    }
    return data;
  }

  return {
    login: (username, password) => call("/auth/login", { method: "POST", body: { username, password } }),
    register: (payload) => call("/auth/register", { method: "POST", body: payload }),

    upload(file, signal) {
      const fd = new FormData();
      fd.append("file", file, file.name);
      return call("/ocr/upload", { method: "POST", body: fd, isForm: true, signal });
    },

    progress: (id, signal) => call(`/ocr/progress/${encodeURIComponent(id)}`, { signal }),
    result: (id, signal) => call(`/ocr/result/${encodeURIComponent(id)}`, { signal }),
    history: (signal) => call("/ocr/history", { signal }),

    async ping() {
      const { apiBase } = getConfig();
      if (!apiBase) throw new ApiError("No API endpoint set.", 0);
      const res = await fetch(trimSlash(apiBase) + "/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: "__ping__", password: "__ping__" }),
      });
      // Any HTTP answer means the service is reachable and CORS is satisfied.
      return { reachable: true, status: res.status };
    },
  };
}

/* ---------------- normalisation ---------------- */

/**
 * Flattens whatever the API returned into one predictable shape.
 * Accepts:
 *   extractedText: ["line", ...]                                   (current backend)
 *   extractedText: [{text, confidence, bbox}, ...]                 (extended)
 *   lines:         [{text, confidence, bbox}, ...]                 (extended)
 * bbox is a 4-point polygon [[x,y],[x,y],[x,y],[x,y]] in source-image pixels.
 */
export function normaliseResult(raw) {
  const source = Array.isArray(raw.lines) ? raw.lines
    : Array.isArray(raw.extractedText) ? raw.extractedText
    : [];

  const fallbackConf = typeof raw.accuracy === "number" ? raw.accuracy / 100 : 0.9;

  const lines = source.map((item, index) => {
    if (typeof item === "string") {
      return { index, text: item, confidence: fallbackConf, bbox: null };
    }
    return {
      index,
      text: item.text ?? item.value ?? "",
      confidence: typeof item.confidence === "number"
        ? (item.confidence > 1 ? item.confidence / 100 : item.confidence)
        : fallbackConf,
      bbox: normaliseBox(item.bbox ?? item.box ?? item.polygon ?? null),
    };
  });

  const withConf = lines.filter((l) => typeof l.confidence === "number");
  const mean = withConf.length
    ? withConf.reduce((a, l) => a + l.confidence, 0) / withConf.length
    : fallbackConf;

  return {
    id: raw.id,
    fileName: raw.fileName || "scan",
    status: raw.status || "COMPLETED",
    processedAt: raw.processedAt || null,
    summary: raw.summary || "",
    accuracy: typeof raw.accuracy === "number" ? raw.accuracy : Math.round(mean * 1000) / 10,
    lines,
    hasBoxes: lines.some((l) => l.bbox),
    imageWidth: raw.imageWidth || raw.width || null,
    imageHeight: raw.imageHeight || raw.height || null,
  };
}

function normaliseBox(bbox) {
  if (!bbox) return null;
  // [[x,y] x4]
  if (Array.isArray(bbox) && bbox.length >= 4 && Array.isArray(bbox[0])) {
    const xs = bbox.map((p) => p[0]), ys = bbox.map((p) => p[1]);
    return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
  }
  // [x, y, w, h]
  if (Array.isArray(bbox) && bbox.length === 4 && typeof bbox[0] === "number") {
    return { x: bbox[0], y: bbox[1], w: bbox[2], h: bbox[3] };
  }
  // {x,y,w,h} or {x1,y1,x2,y2}
  if (typeof bbox === "object") {
    if ("w" in bbox && "h" in bbox) return { x: bbox.x, y: bbox.y, w: bbox.w, h: bbox.h };
    if ("x2" in bbox) return { x: bbox.x1, y: bbox.y1, w: bbox.x2 - bbox.x1, h: bbox.y2 - bbox.y1 };
  }
  return null;
}
