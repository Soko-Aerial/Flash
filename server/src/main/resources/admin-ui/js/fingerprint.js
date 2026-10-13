// A visual fingerprint: the same hex string always draws the same small symmetric picture, so two administrators can compare the
// root fingerprint by looking, not only by reading 64 hex digits. It is an aid; the text comparison stays authoritative.

import { svg } from './dom.js';

/** Deterministic 32-bit mixing (FNV-1a then xorshift) so each cell depends on the whole string, not only on one character. */
function hash32(text, seed) {
  let x = (0x811c9dc5 ^ seed) >>> 0;
  for (let i = 0; i < text.length; i += 1) {
    x ^= text.charCodeAt(i);
    x = Math.imul(x, 0x01000193) >>> 0;
  }
  x ^= x >>> 15;
  x = Math.imul(x, 0x2c1b3c6d) >>> 0;
  x ^= x >>> 12;
  x = Math.imul(x, 0x297a2d39) >>> 0;
  x ^= x >>> 15;
  return x >>> 0;
}

/** @returns {{size:number, cells:{x:number,y:number,tone:number}[], hue:number}} a 7x7 left-right mirrored grid and a base hue 0..359 */
export function fingerprintModel(text) {
  const clean = String(text ?? '').replace(/[^0-9a-fA-F]/g, '').toLowerCase();
  const size = 7;
  const half = 4; // columns 0..3, mirrored to 6..3
  const cells = [];
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < half; x += 1) {
      const v = hash32(clean, y * 16 + x);
      if (v % 100 < 52) {
        const tone = (v >>> 8) % 3;
        cells.push({ x, y, tone });
        if (x !== size - 1 - x) cells.push({ x: size - 1 - x, y, tone });
      }
    }
  }
  return { size, cells, hue: hash32(clean, 999) % 360 };
}

/** Renders the model as an inline SVG. Colours are CSS classes (`fp-0..2`), the hue is a custom property set through the CSSOM. */
export function renderFingerprint(text, { label = 'Visual fingerprint', pixel = 12 } = {}) {
  const m = fingerprintModel(text);
  const side = m.size * pixel;
  const root = svg('svg', { viewBox: '0 0 ' + side + ' ' + side, class: 'fingerprint', role: 'img', 'aria-label': label, focusable: 'false' });
  root.style.setProperty('--fp-hue', String(m.hue));
  for (const c of m.cells) {
    root.appendChild(svg('rect', { x: c.x * pixel, y: c.y * pixel, width: pixel, height: pixel, class: 'fp-' + c.tone }));
  }
  return root;
}
