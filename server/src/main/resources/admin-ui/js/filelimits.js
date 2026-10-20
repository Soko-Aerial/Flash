// The administrator's file limits (ADR-215): conversion between what a person types (megabytes, days) and what the hub stores
// (bytes, days), and the same bounds the hub enforces. The hub's `FileSettings.validate` is the authority; this module repeats
// the bounds only so a mistake is explained before a round trip, and `filelimits.test.mjs` reads the real numbers out of the
// Kotlin source so the two cannot drift apart unnoticed.

export const MB = 1024 * 1024;

/** One entry per limit: the stored key, the unit the person types, and the inclusive bounds in that unit. */
export const FILE_LIMITS = Object.freeze([
  { key: 'maxFileBytes', field: 'maxFileBytes', unit: 'mb', min: 1, max: 2 * 1024 },
  { key: 'deviceStoredBytes', field: 'deviceStoredBytes', unit: 'mb', min: 1, max: 1024 * 1024 },
  { key: 'globalStoredBytes', field: 'globalStoredBytes', unit: 'mb', min: 1, max: 100 * 1024 * 1024 },
  { key: 'fileRetentionDays', field: 'fileRetentionDays', unit: 'days', min: 1, max: 365 },
]);

/** The limit definition for a stored key, or undefined. */
export function limitFor(key) {
  return FILE_LIMITS.find((l) => l.key === key);
}

/** What the form shows for a stored value: whole megabytes (rounded down) or days. */
export function toDisplay(key, stored) {
  const l = limitFor(key);
  if (!l || !Number.isFinite(stored)) return '';
  return String(l.unit === 'mb' ? Math.floor(stored / MB) : stored);
}

/**
 * Parses what was typed. Returns `{ok: true, stored}` (bytes or days) or `{ok: false, reason}` with a reason the view maps to
 * a sentence: `empty`, `notNumber`, `tooSmall`, `tooLarge`. Only plain unsigned whole numbers are accepted: no sign, no
 * decimal point, no exponent, no separators, so what is stored is exactly what was read.
 */
export function parseInput(key, text) {
  const l = limitFor(key);
  if (!l) return { ok: false, reason: 'notNumber' };
  const s = String(text ?? '').trim();
  if (!s) return { ok: false, reason: 'empty' };
  if (!/^[0-9]{1,12}$/.test(s)) return { ok: false, reason: 'notNumber' };
  const n = Number.parseInt(s, 10);
  if (n < l.min) return { ok: false, reason: 'tooSmall' };
  if (n > l.max) return { ok: false, reason: 'tooLarge' };
  return { ok: true, stored: l.unit === 'mb' ? n * MB : n };
}

/**
 * Parses the whole form. `values` maps stored key to typed text. Returns `{errors: {key: reason}, stored: {key: number}}`;
 * `errors` is empty exactly when every field parsed.
 */
export function parseForm(values) {
  const errors = {};
  const stored = {};
  for (const l of FILE_LIMITS) {
    const r = parseInput(l.key, values[l.key]);
    if (r.ok) stored[l.key] = r.stored;
    else errors[l.key] = r.reason;
  }
  return { errors, stored };
}

/**
 * Warnings about a combination the hub accepts but narrows: it clamps the largest file and the per-device allowance to the
 * hub's total (`FileSettings.limits`), so the advertised number would differ from the typed one. Returns warning ids.
 */
export function combinationWarnings(stored) {
  const out = [];
  if (Number.isFinite(stored.maxFileBytes) && Number.isFinite(stored.globalStoredBytes) && stored.maxFileBytes > stored.globalStoredBytes) out.push('maxAboveGlobal');
  if (Number.isFinite(stored.deviceStoredBytes) && Number.isFinite(stored.globalStoredBytes) && stored.deviceStoredBytes > stored.globalStoredBytes) out.push('deviceAboveGlobal');
  if (Number.isFinite(stored.maxFileBytes) && Number.isFinite(stored.deviceStoredBytes) && stored.maxFileBytes > stored.deviceStoredBytes) out.push('maxAboveDevice');
  return out;
}

/** The keys whose stored value differs from [current] (the settings the page loaded), in form order. */
export function changedKeys(stored, current) {
  return FILE_LIMITS.filter((l) => stored[l.key] !== current[l.field]).map((l) => l.key);
}

/** The most-used devices first, at most [limit] rows, with a stable order for equal sizes. Rows are `{deviceId, files, bytes}`. */
export function topUsage(devices, limit = 5) {
  const rows = Array.isArray(devices) ? devices.filter((d) => d && typeof d.deviceId === 'string') : [];
  return rows
    .slice()
    .sort((a, b) => (b.bytes - a.bytes) || (a.deviceId < b.deviceId ? -1 : a.deviceId > b.deviceId ? 1 : 0))
    .slice(0, Math.max(0, limit));
}
