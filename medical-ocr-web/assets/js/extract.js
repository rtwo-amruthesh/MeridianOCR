/* ------------------------------------------------------------------
   Structured extraction.

   Raw OCR gives you a bag of lines. Clinicians need typed fields they can
   check. This module does that translation in the browser so the Fields tab
   works even against a backend that only returns text — and it keeps the
   confidence attached to each line so low-certainty values can be flagged
   instead of silently trusted.

   Heuristic by design: everything it produces is presented for review, not
   asserted as fact.
   ------------------------------------------------------------------ */

const DEMOGRAPHIC_KEYS = [
  { key: "patientName", label: "Patient name", match: /^(patient\s*name|patient|name)\b/i },
  { key: "mrn",         label: "MRN",          match: /^(mrn|m\.?r\.?n|medical\s*record\s*(no|number)|uhid|hospital\s*no)\b/i },
  { key: "dob",         label: "Date of birth",match: /^(dob|d\.?o\.?b|date\s*of\s*birth|birth\s*date)\b/i },
  { key: "sex",         label: "Sex",          match: /^(sex|gender)\b/i },
  { key: "age",         label: "Age",          match: /^age\b/i },
  { key: "collectedAt", label: "Collected",    match: /^(collected|collection\s*(date|time)?|drawn|sample\s*(date|collected))\b/i },
  { key: "reportedAt",  label: "Reported",     match: /^(reported|report\s*(date|time)?|released)\b/i },
  { key: "physician",   label: "Ordering physician", match: /^(ordering\s*physician|referred\s*by|referring\s*(doctor|physician)|consultant|physician|doctor|dr\.?\s*name)\b/i },
  { key: "specimen",    label: "Specimen",     match: /^(specimen|sample\s*type|sample)\b/i },
  { key: "accession",   label: "Accession",    match: /^(accession|lab\s*(no|number)|report\s*(no|id))\b/i },
  { key: "verifiedBy",  label: "Verified by",  match: /^(verified\s*by|authorised\s*by|authorized\s*by|signed\s*by|pathologist)\b/i },
];

const NOISE = /^(page\s*\d+(\s*(of|\/)\s*\d+)?|_+|-+|\u2014+|\s*)$/i;

/* value | unit | range | flag  — tolerant of missing unit / range / flag */
const ANALYTE = new RegExp(
  "^(?<name>[A-Za-z][A-Za-z()./%\\s-]{2,44}?)\\s{1,}" +
  "(?<value>-?\\d+(?:[.,]\\d+)?)\\s*" +
  "(?<unit>(?:10\\^?\\d+\\s*/\\s*[a-zA-Zµu]+|[a-zA-Zµ%][a-zA-Zµ/%\\^\\d.]{0,10}))?\\s*" +
  "(?<range>-?\\d+(?:[.,]\\d+)?\\s*(?:-|–|—|to)\\s*-?\\d+(?:[.,]\\d+)?)?\\s*" +
  "(?<flag>H(?:IGH)?|L(?:OW)?|N|A)?\\s*$",
  "i"
);

const num = (s) => (s == null ? null : Number(String(s).replace(",", ".")));

function splitLabel(text) {
  const i = text.indexOf(":");
  if (i > 0 && i < 42) {
    return { label: text.slice(0, i).trim(), value: text.slice(i + 1).trim() };
  }
  return null;
}

function deriveFlag(value, range) {
  if (value == null || !range) return null;
  if (value < range.low) return "L";
  if (value > range.high) return "H";
  return "N";
}

function parseRange(raw) {
  if (!raw) return null;
  const m = raw.match(/(-?\d+(?:[.,]\d+)?)\s*(?:-|–|—|to)\s*(-?\d+(?:[.,]\d+)?)/i);
  if (!m) return null;
  const low = num(m[1]), high = num(m[2]);
  if (low === null || high === null || low > high) return null;
  // Keep the printed numerals ("12.0 – 15.5"), not the parsed floats ("12 – 15.5").
  return { low, high, text: `${m[1]} – ${m[2]}` };
}

/**
 * @param {Array<{text:string, confidence:number, bbox?:number[][], index:number}>} lines
 * @returns {{demographics:Array, analytes:Array, comments:string[]}}
 */
export function extractStructured(lines) {
  const demographics = [];
  const analytes = [];
  const comments = [];
  const seen = new Set();
  let inComments = false;

  lines.forEach((line) => {
    const text = (line.text || "").trim();
    if (!text || NOISE.test(text)) return;

    if (/^(comments?|impression|interpretation|remarks?|notes?)\b\s*:?\s*$/i.test(text)) {
      inComments = true;
      return;
    }
    if (/^(verified|authorised|authorized|signed|electronically)/i.test(text)) inComments = false;

    /* label: value */
    const pair = splitLabel(text);
    if (pair && pair.value) {
      const hit = DEMOGRAPHIC_KEYS.find((d) => d.match.test(pair.label));
      if (hit && !seen.has(hit.key)) {
        seen.add(hit.key);
        demographics.push({
          key: hit.key,
          label: hit.label,
          value: pair.value,
          confidence: line.confidence,
          lineIndex: line.index,
        });
        return;
      }
    }

    /* analyte row */
    const m = text.match(ANALYTE);
    if (m && m.groups) {
      const g = m.groups;
      const name = g.name.trim().replace(/\s{2,}/g, " ");
      const value = num(g.value);
      const range = parseRange(g.range);
      const looksLikeAnalyte = name.length >= 3 && /[a-z]{3}/i.test(name) && value !== null && (range || g.unit);
      if (looksLikeAnalyte) {
        let flag = g.flag ? g.flag[0].toUpperCase() : null;
        if (!flag) flag = deriveFlag(value, range);
        analytes.push({
          analyte: name,
          value,
          unit: g.unit ? g.unit.trim() : null,
          range,
          flag,
          derivedFlag: !g.flag && !!flag,
          confidence: line.confidence,
          lineIndex: line.index,
        });
        return;
      }
    }

    if (inComments) comments.push(text);
  });

  return { demographics, analytes, comments };
}

/** Fields whose confidence sits under the review threshold. */
export function belowThreshold(structured, threshold) {
  const t = threshold / 100;
  const out = [];
  structured.demographics.forEach((d) => { if (d.confidence < t) out.push(d.label); });
  structured.analytes.forEach((a) => { if (a.confidence < t) out.push(a.analyte); });
  return out;
}

/** The export payload — what a downstream HIS/LIS would actually consume. */
export function toExportPayload(result, structured, threshold) {
  return {
    document: {
      id: result.id,
      fileName: result.fileName,
      processedAt: result.processedAt,
      meanConfidence: result.accuracy,
      lineCount: result.lines.length,
    },
    review: {
      thresholdPercent: threshold,
      fieldsNeedingReview: belowThreshold(structured, threshold),
    },
    patient: Object.fromEntries(structured.demographics.map((d) => [d.key, { value: d.value, confidence: d.confidence }])),
    results: structured.analytes.map((a) => ({
      analyte: a.analyte,
      value: a.value,
      unit: a.unit,
      referenceRange: a.range ? { low: a.range.low, high: a.range.high } : null,
      flag: a.flag,
      flagDerived: a.derivedFlag,
      confidence: a.confidence,
    })),
    comments: structured.comments,
    lines: result.lines.map((l) => ({ text: l.text, confidence: l.confidence, bbox: l.bbox || null })),
    disclaimer: "Machine-extracted and unverified. Requires review by a qualified person before clinical use.",
  };
}
