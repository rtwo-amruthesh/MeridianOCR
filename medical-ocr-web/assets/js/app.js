import { APP, STEPS, DEFAULT_API_BASE } from "./config.js";
import { store, session, settings } from "./store.js";
import { createApi, normaliseResult, ApiError } from "./api.js";
import { extractStructured, belowThreshold, toExportPayload } from "./extract.js";

/* ══════════════════════════ state ══════════════════════════ */

const state = {
  apiBase: DEFAULT_API_BASE,
  token: null,
  user: null,
  result: null,
  structured: null,
  threshold: APP.defaultThreshold,
  imageUrl: null,
  objectUrl: null,
  imgW: 0,
  imgH: 0,
  abort: null,
};

const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));

const liveApi = createApi(() => ({ apiBase: state.apiBase, token: state.token }));
const api = () => liveApi;

/* ══════════════════════════ boot ══════════════════════════ */

function boot() {
  const saved = settings.read();
  if (saved) state.apiBase = saved.apiBase ?? DEFAULT_API_BASE;

  const s = session.read();
  if (s?.token) {
    state.token = s.token;
    state.user = s.user;
    enterApp();
  } else {
    enterAuth();
  }

  $("#boot").hidden = true;
  wire();
}

function enterAuth() {
  $("#view-app").hidden = true;
  $("#view-auth").hidden = false;
  $("#auth-endpoint").textContent = state.apiBase
    ? `API · ${state.apiBase}`
    : "No API endpoint configured";
}

function enterApp() {
  $("#view-auth").hidden = true;
  $("#view-app").hidden = false;
  $("#who").textContent = state.user || "";
  $("#origin-hint").textContent = window.location.origin;
  paintConnection();
  showView("bench");
  resetBench();
}

function paintConnection() {
  const pill = $("#conn-pill"), text = $("#conn-text");
  pill.classList.remove("is-live");
  pill.classList.add("is-live");
  try { text.textContent = new URL(state.apiBase).host; }
  catch { text.textContent = state.apiBase ? "Connected" : "No server set"; }
}

/* ══════════════════════════ views ══════════════════════════ */

function showView(name) {
  $$(".rail__item").forEach((b) => b.classList.toggle("is-on", b.dataset.view === name));
  $("#pane-bench").hidden = name !== "bench";
  $("#pane-history").hidden = name !== "history";
  $("#pane-settings").hidden = name !== "settings";
  if (name === "history") loadHistory();
  if (name === "settings") fillSettings();
}

/* ══════════════════════════ auth ══════════════════════════ */

function wireAuth() {
  $$("[data-authtab]").forEach((tab) => {
    tab.addEventListener("click", () => {
      $$("[data-authtab]").forEach((t) => t.classList.toggle("is-on", t === tab));
      const login = tab.dataset.authtab === "login";
      $("#form-login").hidden = !login;
      $("#form-register").hidden = login;
    });
  });

  $("#form-login").addEventListener("submit", async (e) => {
    e.preventDefault();
    const note = $("#login-note");
    const fd = new FormData(e.target);
    const btn = e.target.querySelector("button[type=submit]");
    btn.disabled = true; note.className = "note"; note.textContent = "Signing in…";
    try {
      const res = await api().login(fd.get("username"), fd.get("password"));
      completeSignIn(res);
    } catch (err) {
      note.className = "note note--bad";
      note.textContent = err.message;
    } finally { btn.disabled = false; }
  });

  $("#form-register").addEventListener("submit", async (e) => {
    e.preventDefault();
    const note = $("#register-note");
    const fd = new FormData(e.target);
    const btn = e.target.querySelector("button[type=submit]");
    btn.disabled = true; note.className = "note"; note.textContent = "Creating account…";
    try {
      const res = await api().register({
        username: fd.get("username"), email: fd.get("email"), password: fd.get("password"),
        firstName: fd.get("firstName") || null, lastName: fd.get("lastName") || null,
      });
      completeSignIn(res);
    } catch (err) {
      note.className = "note note--bad";
      note.textContent = err.message;
    } finally { btn.disabled = false; }
  });

  $("#btn-auth-settings").addEventListener("click", () => {
    const next = window.prompt(
      "Address of your reader — it must end in /api.",
      state.apiBase || "https://your-api.onrender.com/api"
    );
    if (next === null) return;
    state.apiBase = next.trim();
    settings.write({ apiBase: state.apiBase });
    enterAuth();
    toast(state.apiBase ? "Server address saved" : "Server address cleared");
  });
}

function completeSignIn(res) {
  state.token = res.token;
  state.user = res.username;
  session.write({ token: res.token, user: res.username });
  enterApp();
  toast(`Signed in as ${res.username}`);
}

function signOut() {
  session.clear();
  state.token = null; state.user = null;
  releaseImage();
  enterAuth();
}

/* ══════════════════════════ intake ══════════════════════════ */

function resetBench() {
  $("#intake").hidden = false;
  $("#pipeline").hidden = true;
  $("#bench").hidden = true;
  $("#file").value = "";
}

function wireIntake() {
  const drop = $("#drop"), input = $("#file");

  drop.addEventListener("click", () => input.click());
  drop.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); input.click(); }
  });
  input.addEventListener("change", () => { if (input.files[0]) startRead(input.files[0]); });

  ["dragenter", "dragover"].forEach((ev) =>
    drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("is-over"); }));
  ["dragleave", "drop"].forEach((ev) =>
    drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("is-over"); }));
  drop.addEventListener("drop", (e) => {
    const f = e.dataTransfer?.files?.[0];
    if (f) startRead(f);
  });

  $("#btn-cancel").addEventListener("click", () => {
    state.abort?.abort();
    resetBench();
    toast("Cancelled");
  });
  $("#btn-new").addEventListener("click", () => { releaseImage(); resetBench(); });
}

function validate(file) {
  if (file.size > APP.maxFileBytes) return `That file is ${(file.size / 1048576).toFixed(1)} MB. The limit is 10 MB.`;
  if (!APP.acceptTypes.includes(file.type)) return `${file.type || "That file type"} isn't supported. Use PNG, JPG, WebP or PDF.`;
  return null;
}

/* ══════════════════════════ pipeline ══════════════════════════ */

async function startRead(file) {
  const problem = validate(file);
  if (problem) { toast(problem, true); return; }

  state.abort = new AbortController();
  const signal = state.abort.signal;

  $("#intake").hidden = true;
  $("#bench").hidden = true;
  $("#pipeline").hidden = false;
  $("#pipe-filename").textContent = file.name;
  paintProgress({ status: "UPLOADING", progress: 4, message: "Starting…" });

  try {
    setImageFromFile(file);
    const started = await liveApi.upload(file, signal);
    const id = started.id;
    paintProgress({ status: "PROCESSING", progress: 20, message: started.message || "Queued for reading…" });

    const final = await pollUntilDone(id, signal);
    if (final.status === "FAILED") throw new ApiError(final.message || "The service couldn't read that file.", 500);

    const raw = await liveApi.result(id, signal);
    openResult(normaliseResult(raw));
  } catch (err) {
    if (err.name === "AbortError") return;
    paintProgress({ status: "FAILED", progress: 100, message: err.message });
    $("#steps").querySelector("li.is-now")?.classList.add("is-fail");
    toast(err.message, true);
    setTimeout(() => { if (!$("#pipeline").hidden) resetBench(); }, 4200);
  }
}

async function pollUntilDone(id, signal) {
  const deadline = Date.now() + APP.pollTimeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    try {
      last = await liveApi.progress(id, signal);
      paintProgress(last);
      if (last.status === "COMPLETED" || last.status === "FAILED") return last;
    } catch (e) {
      if (e.name === "AbortError") throw e;
      // progress is best-effort; keep waiting for the result instead of failing
    }
    await new Promise((r) => setTimeout(r, APP.pollIntervalMs));
  }
  throw new ApiError("The service didn't finish in time. It may still be warming up — try again in a moment.", 504);
}

function paintProgress({ status, progress, message }) {
  $("#meter-fill").style.width = `${Math.max(0, Math.min(100, progress || 0))}%`;
  $("#pipe-msg").textContent = message || "";
  const at = STEPS.indexOf(status);
  $$("#steps li").forEach((li, i) => {
    li.classList.remove("is-done", "is-now", "is-fail");
    if (at === -1) return;
    if (i < at) li.classList.add("is-done");
    else if (i === at) li.classList.add(status === "COMPLETED" ? "is-done" : "is-now");
  });
}

/* ══════════════════════════ image ══════════════════════════ */

function releaseImage() {
  if (state.objectUrl) { URL.revokeObjectURL(state.objectUrl); state.objectUrl = null; }
}

function setImage(url, w, h) {
  state.imageUrl = url; state.imgW = w; state.imgH = h;
  const img = $("#scan-img");
  img.src = url;
  img.hidden = false;
}

function setImageFromFile(file) {
  releaseImage();
  if (file.type === "application/pdf") {
    state.imageUrl = null; state.imgW = 0; state.imgH = 0;
    return;
  }
  state.objectUrl = URL.createObjectURL(file);
  const img = $("#scan-img");
  img.onload = () => {
    state.imgW = img.naturalWidth; state.imgH = img.naturalHeight;
    drawOverlay();
  };
  setImage(state.objectUrl, 0, 0);
}

/* ══════════════════════════ bench ══════════════════════════ */

function openResult(result) {
  state.result = result;
  state.structured = extractStructured(result.lines);

  $("#pipeline").hidden = true;
  $("#intake").hidden = true;
  $("#bench").hidden = false;

  if (result.imageWidth && result.imageHeight) {
    state.imgW = result.imageWidth; state.imgH = result.imageHeight;
  }

  const img = $("#scan-img");
  if (!state.imageUrl) {
    img.hidden = true;
    $(".scan__hint").textContent = "PDFs can't be shown here yet, so the boxes can't be drawn over them. The text below was still read correctly. Upload a photo or PNG to see the boxes.";
  } else {
    img.hidden = false;
    $(".scan__hint").textContent = result.hasBoxes
      ? "Hover a region or a line to link the two."
      : "No bounding boxes in the response, so regions can't be drawn. Return per-line polygons from the OCR service to enable this.";
  }

  paintStats();
  drawOverlay();
  renderFields();
  renderLines();
  renderSummary();
  setZoom(1);
}

function paintStats() {
  const r = state.result;
  $("#stat-conf").textContent = `${r.accuracy.toFixed(1)}%`;
  $("#stat-lines").textContent = r.lines.length;
  const low = r.lines.filter((l) => l.confidence * 100 < state.threshold).length;
  $("#stat-review").textContent = low;
  $("#thresh-out").textContent = `${state.threshold}%`;
}

function drawOverlay() {
  const svg = $("#overlay");
  svg.innerHTML = "";
  const r = state.result;
  if (!r || !r.hasBoxes || !state.imgW || !state.imgH) { svg.style.display = "none"; return; }
  svg.style.display = "block";
  svg.setAttribute("viewBox", `0 0 ${state.imgW} ${state.imgH}`);

  const ns = "http://www.w3.org/2000/svg";
  r.lines.forEach((line) => {
    if (!line.bbox) return;
    const b = boxOf(line.bbox);
    const rect = document.createElementNS(ns, "rect");
    rect.setAttribute("x", b.x); rect.setAttribute("y", b.y);
    rect.setAttribute("width", b.w); rect.setAttribute("height", b.h);
    rect.setAttribute("rx", "2");
    rect.dataset.line = line.index;
    if (line.confidence * 100 < state.threshold) rect.classList.add("is-review");
    rect.addEventListener("mouseenter", () => link(line.index, true));
    rect.addEventListener("mouseleave", () => link(line.index, false));
    rect.addEventListener("click", () => focusLine(line.index));
    svg.appendChild(rect);
  });
}

function boxOf(bbox) {
  if (Array.isArray(bbox)) {
    const xs = bbox.map((p) => p[0]), ys = bbox.map((p) => p[1]);
    return { x: Math.min(...xs), y: Math.min(...ys), w: Math.max(...xs) - Math.min(...xs), h: Math.max(...ys) - Math.min(...ys) };
  }
  return bbox;
}

/* ---- linked highlighting: the point of the bench ---- */
function link(lineIndex, on) {
  $$(`[data-line="${lineIndex}"]`).forEach((el) => el.classList.toggle("is-linked", on));
}

function focusLine(lineIndex) {
  const tab = $("#tab-lines");
  if (tab.hidden) switchDataTab("lines");
  const row = $(`.lrow[data-line="${lineIndex}"]`);
  row?.scrollIntoView({ block: "center", behavior: "smooth" });
  row?.classList.add("is-linked");
  setTimeout(() => row?.classList.remove("is-linked"), 1400);
}

/* ---- Fields ---- */
function renderFields() {
  const el = $("#tab-fields");
  const { demographics, analytes } = state.structured;
  const t = state.threshold / 100;

  if (!demographics.length && !analytes.length) {
    el.innerHTML = `<div class="empty" style="border:0;box-shadow:none">
      <h3>Nothing recognisable as fields</h3>
      <p>The text came through, but none of it matched a known label or result row. Open the Lines tab to see the raw output.</p>
    </div>`;
    return;
  }

  const demographicRows = demographics.map((d) => `
    <div class="frow ${d.confidence < t ? "is-review" : ""}" data-line="${d.lineIndex}">
      <span class="frow__key">${esc(d.label)}</span>
      <span class="frow__val">${esc(d.value)}</span>
      <span class="frow__conf">${(d.confidence * 100).toFixed(1)}%</span>
    </div>`).join("");

  const analyteRows = analytes.map((a) => `
    <tr data-line="${a.lineIndex}">
      <td>${esc(a.analyte)}</td>
      <td class="num">${a.value ?? "—"}</td>
      <td>${esc(a.unit || "—")}</td>
      <td class="num">${a.range ? esc(a.range.text) : "—"}</td>
      <td>${flagChip(a.flag, a.derivedFlag)}</td>
      <td class="num" style="color:${a.confidence < t ? "var(--amber)" : "var(--ink-3)"}">${(a.confidence * 100).toFixed(0)}%</td>
    </tr>`).join("");

  el.innerHTML = `
    ${demographicRows}
    ${analytes.length ? `
      <table class="atable">
        <thead><tr><th>Analyte</th><th>Value</th><th>Unit</th><th>Reference</th><th>Flag</th><th>Conf.</th></tr></thead>
        <tbody>${analyteRows}</tbody>
      </table>` : ""}
  `;

  $$("[data-line]", el).forEach((row) => {
    const i = row.dataset.line;
    row.addEventListener("mouseenter", () => link(i, true));
    row.addEventListener("mouseleave", () => link(i, false));
  });
}

function flagChip(flag, derived) {
  if (!flag) return `<span class="flag flag--n">—</span>`;
  const cls = flag === "H" ? "flag--h" : flag === "L" ? "flag--l" : "flag--n";
  const title = derived ? ' title="Computed from the printed reference range, not read off the page"' : "";
  return `<span class="flag ${cls}"${title}>${flag}${derived ? "*" : ""}</span>`;
}

/* ---- Lines ---- */
function renderLines() {
  const el = $("#tab-lines");
  const t = state.threshold / 100;
  el.innerHTML = state.result.lines.map((l) => {
    const pct = Math.round(l.confidence * 100);
    return `<div class="lrow ${l.confidence < t ? "is-review" : ""}" data-line="${l.index}">
      <span class="lrow__i">${String(l.index + 1).padStart(2, "0")}</span>
      <span class="lrow__t">${esc(l.text)}</span>
      <span class="cbar"><span class="cbar__track"><span class="cbar__fill" style="width:${pct}%"></span></span><span class="cbar__n">${pct}</span></span>
    </div>`;
  }).join("");

  $$(".lrow", el).forEach((row) => {
    const i = row.dataset.line;
    row.addEventListener("mouseenter", () => link(i, true));
    row.addEventListener("mouseleave", () => link(i, false));
    row.addEventListener("click", () => {
      const rect = $(`#overlay rect[data-line="${i}"]`);
      rect?.scrollIntoView({ block: "center", behavior: "smooth" });
    });
  });
}

/* ---- Summary ---- */
function renderSummary() {
  const { comments } = state.structured;
  const parts = [];
  if (state.result.summary) parts.push(state.result.summary);
  if (comments.length) parts.push("\n\nComments read from the document:\n" + comments.map((c) => "· " + c).join("\n"));
  $("#tab-summary").innerHTML = `<div class="summary">${esc(parts.join("") || "No summary was returned.")}</div>`;
}

function switchDataTab(name) {
  $$("[data-datatab]").forEach((t) => t.classList.toggle("is-on", t.dataset.datatab === name));
  $("#tab-fields").hidden = name !== "fields";
  $("#tab-lines").hidden = name !== "lines";
  $("#tab-summary").hidden = name !== "summary";
}

/* ══════════════════════════ zoom ══════════════════════════ */

let zoom = 1;
function setZoom(z) {
  zoom = Math.max(1, Math.min(4, z));
  $("#stack").style.width = `${zoom * 100}%`;
  $("#zoom-label").textContent = zoom === 1 ? "Fit" : `${Math.round(zoom * 100)}%`;
}

/* ══════════════════════════ history ══════════════════════════ */

async function loadHistory() {
  const body = $("#history-body");
  body.innerHTML = `<p class="note">Loading…</p>`;
  try {
    const rows = await api().history();
    if (!rows || !rows.length) {
      body.innerHTML = `<div class="empty">
        <h3>Nothing read yet</h3>
        <p>Documents you send through the bench will be listed here.</p>
        <button class="btn btn--primary" data-goto="bench">Read a document</button>
      </div>`;
      $("[data-goto]", body)?.addEventListener("click", () => showView("bench"));
      return;
    }
    body.innerHTML = `<table class="htable">
      <thead><tr><th>File</th><th>Read</th><th>Mean confidence</th><th>Status</th></tr></thead>
      <tbody>${rows.map((r) => `
        <tr data-id="${esc(r.id)}">
          <td class="mono">${esc(r.fileName || "—")}</td>
          <td>${fmtDate(r.processedAt)}</td>
          <td class="mono">${typeof r.accuracy === "number" ? r.accuracy.toFixed(1) + "%" : "—"}</td>
          <td><span class="status status--${(r.status || "").toLowerCase()}">${esc(r.status || "—")}</span></td>
        </tr>`).join("")}</tbody></table>`;

    $$(".htable tbody tr", body).forEach((tr) => {
      tr.addEventListener("click", () => reopen(tr.dataset.id));
    });
  } catch (err) {
    body.innerHTML = `<div class="empty"><h3>Couldn't load the record</h3><p>${esc(err.message)}</p></div>`;
  }
}

async function reopen(id) {
  try {
    const raw = await api().result(id);
    releaseImage(); state.imageUrl = null; $("#scan-img").removeAttribute("src");
    showView("bench");
    openResult(normaliseResult(raw));
  } catch (err) {
    toast(err.message, true);
  }
}

/* ══════════════════════════ settings ══════════════════════════ */

function fillSettings() {
  $("#set-api").value = state.apiBase || "";
  $("#settings-note").textContent = store.persistent ? "" : "This browser blocks local storage, so settings last only for this tab.";
  $("#settings-note").className = "note";
}

function wireSettings() {
  $("#btn-save-settings").addEventListener("click", () => {
    const next = $("#set-api").value.trim();
    if (!next) { setNote("Enter the address of your reader.", true); return; }
    if (!/^https?:\/\//i.test(next)) {
      setNote("The address needs to start with https://", true); return;
    }
    state.apiBase = next;
    settings.write({ apiBase: next });
    paintConnection();
    setNote("Saved.");
  });

  $("#btn-test").addEventListener("click", async () => {
    setNote("Testing…");
    try {
      const target = $("#set-api").value.trim();
      const probe = createApi(() => ({ apiBase: target, token: null }));
      const r = await probe.ping();
      setNote(r.status === 401 || r.status === 200
        ? "Connected. Your reader is responding."
        : `Reached it, but it answered unexpectedly (${r.status}).`);
    } catch (err) {
      setNote(err.message, true);
    }
  });
}

function setNote(msg, bad = false) {
  const n = $("#settings-note");
  n.textContent = msg;
  n.className = bad ? "note note--bad" : "note note--good";
}

/* ══════════════════════════ export ══════════════════════════ */

function exportJson() {
  const payload = toExportPayload(state.result, state.structured, state.threshold);
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${(state.result.fileName || "extraction").replace(/\.[^.]+$/, "")}-extraction.json`;
  a.click();
  URL.revokeObjectURL(url);
  toast("Saved to your downloads");
}

async function copyText() {
  const text = state.result.lines.map((l) => l.text).join("\n");
  try {
    await navigator.clipboard.writeText(text);
    toast("Text copied");
  } catch {
    toast("This browser wouldn't let the page copy text. Use Download results instead.", true);
  }
}

/* ══════════════════════════ helpers ══════════════════════════ */

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function fmtDate(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (isNaN(d)) return esc(iso);
  return d.toLocaleString(undefined, { day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

let toastTimer;
function toast(msg, bad = false) {
  const el = $("#toast");
  el.textContent = msg;
  el.classList.toggle("is-bad", bad);
  el.classList.add("is-up");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("is-up"), bad ? 5200 : 2800);
}

/* ══════════════════════════ wiring ══════════════════════════ */

function wire() {
  wireAuth();
  wireIntake();
  wireSettings();

  $("#btn-signout").addEventListener("click", signOut);
  $$(".rail__item").forEach((b) => b.addEventListener("click", () => showView(b.dataset.view)));
  $$("[data-datatab]").forEach((t) => t.addEventListener("click", () => switchDataTab(t.dataset.datatab)));
  $("#btn-refresh").addEventListener("click", loadHistory);
  $("#btn-export").addEventListener("click", exportJson);
  $("#btn-copy").addEventListener("click", copyText);

  $("#zoom-in").addEventListener("click", () => setZoom(zoom + 0.5));
  $("#zoom-out").addEventListener("click", () => setZoom(zoom - 0.5));
  $("#zoom-fit").addEventListener("click", () => setZoom(1));

  $("#thresh").addEventListener("input", (e) => {
    state.threshold = Number(e.target.value);
    $("#thresh-out").textContent = `${state.threshold}%`;
    if (!state.result) return;
    paintStats();
    drawOverlay();
    renderFields();
    renderLines();
  });

  window.addEventListener("beforeunload", releaseImage);
}

boot();
