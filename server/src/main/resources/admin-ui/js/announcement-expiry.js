// Pure expiry rules for the announcement composer (ANN-02, ADR-222 decision 6). No DOM and no imports, so node can test it.
//
// The wire is tri-state: a value sets the expiry, an absent field KEEPS the stored one, `clearExpiry: true` removes it.
// An edit that touches nothing must therefore send nothing, and removing an expiry must be deliberate.

/** `YYYY-MM-DDTHH:mm` in local time, the value format of an <input type="datetime-local">. */
export function toLocalInput(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/**
 * What the composer sends for the expiry.
 * @param {number|null} existingExpiresAt the stored expiry of the announcement being edited (null on create)
 * @param {boolean} noExpiry the "No expiry" box
 * @param {string} localValue the datetime-local value ('' when empty)
 * @param {number} nowMs
 * @param {boolean} isEdit
 * @returns {{ok: true, fields: object} | {ok: false, reason: 'invalid'|'past'}}
 */
export function expiryFields(existingExpiresAt, noExpiry, localValue, nowMs, isEdit) {
  if (noExpiry || !localValue) {
    return isEdit && existingExpiresAt ? { ok: true, fields: { clearExpiry: true } } : { ok: true, fields: {} };
  }
  const ms = new Date(localValue).getTime();
  if (!Number.isFinite(ms)) return { ok: false, reason: 'invalid' };
  // The input has minute precision: an untouched field must not look like a change.
  if (isEdit && existingExpiresAt && Math.floor(existingExpiresAt / 60000) === Math.floor(ms / 60000)) {
    return { ok: true, fields: {} };
  }
  if (ms <= nowMs) return { ok: false, reason: 'past' };
  return { ok: true, fields: { expiresAtMs: ms } };
}
