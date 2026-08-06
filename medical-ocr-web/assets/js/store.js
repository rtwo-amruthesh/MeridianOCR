/* localStorage is unavailable in Safari private mode, some embeds and
   sandboxed iframes — every access there throws. Fall back to memory so the
   session still works for as long as the tab is open. */

const memory = new Map();
let backend = null;

function probe() {
  if (backend) return backend;
  try {
    const k = "__probe__";
    window.localStorage.setItem(k, "1");
    window.localStorage.removeItem(k);
    backend = "local";
  } catch {
    backend = "memory";
  }
  return backend;
}

const PREFIX = "ocrbench:";

export const store = {
  get(key, fallback = null) {
    try {
      const raw = probe() === "local"
        ? window.localStorage.getItem(PREFIX + key)
        : memory.get(key) ?? null;
      return raw === null || raw === undefined ? fallback : JSON.parse(raw);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    const raw = JSON.stringify(value);
    try {
      if (probe() === "local") window.localStorage.setItem(PREFIX + key, raw);
      else memory.set(key, raw);
    } catch {
      memory.set(key, raw);
    }
  },
  remove(key) {
    try {
      if (probe() === "local") window.localStorage.removeItem(PREFIX + key);
    } catch { /* ignore */ }
    memory.delete(key);
  },
  get persistent() { return probe() === "local"; },
};

/* ---- session shape ---- */
export const session = {
  read: () => store.get("session", null),
  write: (s) => store.set("session", s),
  clear: () => store.remove("session"),
};

export const settings = {
  read: () => store.get("settings", null),
  write: (s) => store.set("settings", s),
};
