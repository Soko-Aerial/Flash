// The hub renders invite QR codes as an SVG string. That string is never inserted as markup: this module extracts only the two things a QR
// code is made of (the viewBox and the path data) with strict patterns, and rebuilds the picture element by element.

import { svg } from './dom.js';

const VIEWBOX = /viewBox="(\d+(?:\.\d+)? \d+(?:\.\d+)? (\d+(?:\.\d+)?) (\d+(?:\.\d+)?))"/;
const PATH_D = /<path[^>]*?\sd="([MmHhVvZzLl0-9 .,\-]+)"/;
const MAX_PATH = 400_000;

/** @returns {{viewBox:string, width:number, height:number, d:string} | null} null when the string is not a plain QR svg */
export function parseQrSvg(source) {
  if (typeof source !== 'string' || source.length > 2_000_000) return null;
  const vb = VIEWBOX.exec(source);
  const d = PATH_D.exec(source);
  if (!vb || !d || d[1].length > MAX_PATH) return null;
  const width = Number.parseFloat(vb[2]);
  const height = Number.parseFloat(vb[3]);
  if (!(width > 0 && width <= 1000 && height > 0 && height <= 1000)) return null;
  return { viewBox: vb[1], width, height, d: d[1] };
}

/** Builds the QR picture. [label] names it for screen readers; [className] sizes it through CSS. Returns null when the source is unusable. */
export function renderQr(source, { label = 'QR code', className = 'qr' } = {}) {
  const parsed = parseQrSvg(source);
  if (!parsed) return null;
  const root = svg('svg', { viewBox: parsed.viewBox, class: className, role: 'img', 'aria-label': label, focusable: 'false' });
  root.appendChild(svg('rect', { width: parsed.width, height: parsed.height, class: 'qr-bg' }));
  root.appendChild(svg('path', { d: parsed.d, class: 'qr-fg' }));
  return root;
}
