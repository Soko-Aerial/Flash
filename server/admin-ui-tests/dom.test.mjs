import test from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDocument, walk } from './fakedom.mjs';

installFakeDocument();
const { h, svg, mount, isSafeHref, setVars, frag } = await import('../src/main/resources/admin-ui/js/dom.js');

test('a string child becomes a text node, never markup', () => {
  const evil = '<img src=x onerror=alert(1)><script>alert(2)</script>';
  const el = h('p', { class: 'x' }, evil);
  assert.equal(el.childNodes.length, 1);
  assert.equal(el.childNodes[0].nodeType, 3);
  assert.equal(el.textContent, evil);
  assert.equal(walk(el).length, 1, 'no element was created from the text');
});

test('the text prop is textContent as well', () => {
  const el = h('span', { text: '<b>bold</b>' });
  assert.equal(el.textContent, '<b>bold</b>');
  assert.equal(walk(el).length, 1);
});

test('style attributes and on* attributes are refused', () => {
  assert.throws(() => h('div', { style: 'color:red' }), /not allowed/);
  assert.throws(() => h('div', { onclick: 'alert(1)' }), /not allowed/);
  assert.throws(() => h('div', { onerror: 'alert(1)' }), /not allowed/);
  assert.throws(() => h('div', { OnLoad: 'x' }), /not allowed/);
});

test('an attribute outside the allow-list is refused', () => {
  assert.throws(() => h('div', { srcdoc: '<script>1</script>' }), /allow-list/);
  assert.throws(() => h('iframe', { src: 'https://evil.example' }), /allow-list/);
  assert.throws(() => h('a', { target: '_blank' }), /allow-list/);
});

test('event handlers must be functions', () => {
  assert.throws(() => h('button', { on: { click: 'alert(1)' } }), /function/);
  let clicks = 0;
  const b = h('button', { on: { click: () => clicks++ } });
  b.listeners.get('click')[0]();
  assert.equal(clicks, 1);
});

test('href accepts in-page, same-origin and blob links only', () => {
  for (const ok of ['#/devices', '/admin/ui/x', 'blob:http://localhost/abc']) assert.equal(isSafeHref(ok), true, ok);
  const bad = ['javascript:alert(1)', 'JaVaScRiPt:alert(1)', 'data:text/html,<script>1</script>', 'https://evil.example', '//evil.example', '/\\evil.example', 'vbscript:x', ' javascript:1', ''];
  for (const value of bad) assert.equal(isSafeHref(value), false, JSON.stringify(value));
  assert.throws(() => h('a', { href: 'javascript:alert(1)' }), /Unsafe href/);
  assert.equal(h('a', { href: '#/overview' }).getAttribute('href'), '#/overview');
});

test('boolean and empty props', () => {
  const i = h('input', { disabled: true, readonly: false, value: null, placeholder: undefined, type: 'text' });
  assert.equal(i.getAttribute('disabled'), '');
  assert.equal(i.getAttribute('readonly'), null);
  assert.equal(i.getAttribute('placeholder'), null);
  assert.equal(i.getAttribute('type'), 'text');
});

test('classes may be an array; falsy entries are dropped', () => {
  assert.equal(h('div', { class: ['a', false, 'b', null] }).className, 'a b');
});

test('custom properties go through the CSSOM, not an attribute', () => {
  const el = h('div', { vars: { pct: 40, '--tone': 'x' } });
  assert.equal(el.style.props.get('--pct'), '40');
  assert.equal(el.style.props.get('--tone'), 'x');
  assert.equal(el.getAttribute('style'), null);
  const direct = h('div');
  setVars(direct, { a: 1 });
  assert.equal(direct.style.props.get('--a'), '1');
});

test('mount replaces children; nested arrays and null children are handled', () => {
  const root = h('div', {}, 'old');
  mount(root, 'new', [h('b', {}, 'x'), null, [h('i')]], false, 7);
  assert.equal(root.textContent, 'newx7');
  assert.equal(root.childNodes.length, 4);
});

test('svg elements are created in the SVG namespace with class as an attribute', () => {
  const s = svg('svg', { viewBox: '0 0 10 10', class: 'qr', role: 'img' }, svg('path', { d: 'M0 0h1v1z' }));
  assert.equal(s.namespaceURI, 'http://www.w3.org/2000/svg');
  assert.equal(s.getAttribute('class'), 'qr');
  assert.equal(s.childNodes[0].namespaceURI, 'http://www.w3.org/2000/svg');
});

test('a fragment collects children', () => {
  const f = frag('a', h('b'));
  assert.equal(f.childNodes.length, 2);
});
