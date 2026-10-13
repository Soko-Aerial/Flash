import test from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDocument, walk } from './fakedom.mjs';

installFakeDocument();
const { parseQrSvg, renderQr } = await import('../src/main/resources/admin-ui/js/qr.js');

const GOOD = '<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 25 25" shape-rendering="crispEdges"><rect width="25" height="25" fill="#fff"/><path d="M0 0h7v1H0zM8 0h1v1H8z" fill="#000"/></svg>';

test('a plain QR svg is reduced to its viewBox and path data', () => {
  const p = parseQrSvg(GOOD);
  assert.equal(p.viewBox, '0 0 25 25');
  assert.equal(p.d, 'M0 0h7v1H0zM8 0h1v1H8z');
});

test('the rebuilt picture contains only a rect and a path', () => {
  const el = renderQr(GOOD, { label: 'Code' });
  assert.deepEqual(walk(el).map((n) => n.tagName), ['svg', 'rect', 'path']);
  assert.equal(el.getAttribute('aria-label'), 'Code');
});

test('markup smuggled into the svg never reaches the page', () => {
  const evil = GOOD.replace('</svg>', '<script>alert(1)</script><image href="https://evil.example/x.png"/></svg>');
  assert.deepEqual(walk(renderQr(evil)).map((n) => n.tagName), ['svg', 'rect', 'path']);
  assert.equal(parseQrSvg(GOOD.replace('M0 0h7v1H0z', 'M0 0<script>')), null, 'path data must be numbers and path commands only');
  assert.equal(parseQrSvg(GOOD.replace('d="M0 0h7v1H0zM8 0h1v1H8z"', 'd="javascript:alert(1)"')), null);
});

test('unusable input gives null', () => {
  const bad = ['', null, undefined, 42, '<svg></svg>', GOOD.replace('viewBox="0 0 25 25"', 'viewBox="0 0 99999 99999"'), 'x'.repeat(2_100_000)];
  for (const value of bad) {
    assert.equal(parseQrSvg(value), null);
    assert.equal(renderQr(value), null);
  }
});
