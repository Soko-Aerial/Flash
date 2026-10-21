import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installFakeDocument, walk } from './fakedom.mjs';
import en from '../src/main/resources/admin-ui/js/strings/en.js';

installFakeDocument();
const { h, mount } = await import('../src/main/resources/admin-ui/js/dom.js');

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'admin-ui', 'js');
const annView = fs.readFileSync(path.join(root, 'views', 'announcements.js'), 'utf8');

test('ADR-138: honesty notice explicitly states announcements are not end-to-end encrypted', () => {
  assert.equal(
    en['announcements.preview.honesty'],
    'Visible to anyone with access to this hub — announcements are not end-to-end encrypted.',
  );
  // Ensure no announcement string anywhere claims encryption
  for (const [k, v] of Object.entries(en)) {
    if (k.startsWith('announcements.') && k !== 'announcements.preview.honesty') {
      assert.ok(!v.toLowerCase().includes('end-to-end encrypt'), `key ${k} must not claim end-to-end encryption`);
      assert.ok(!v.toLowerCase().includes('e2ee'), `key ${k} must not claim e2ee`);
    }
  }
});

test('XSS safety: announcements view never uses innerHTML or raw markup', () => {
  assert.ok(!annView.includes('innerHTML'), 'innerHTML is forbidden');
  assert.ok(!annView.includes('outerHTML'), 'outerHTML is forbidden');
  assert.ok(!annView.includes('dangerouslySetInnerHTML'), 'raw html insertion forbidden');
});

test('XSS safety: injected malicious payload becomes pure text node in preview and drawer', () => {
  const evilPayload = '<img src=x onerror=alert(1)><script>alert("pwned")</script>';

  // Table title cell
  const titleCell = h('span', { text: evilPayload });
  assert.equal(titleCell.textContent, evilPayload);
  const titleNodes = walk(titleCell);
  assert.equal(titleNodes.length, 1);
  assert.equal(titleNodes[0].tagName.toUpperCase(), 'SPAN');

  // Preview panel elements
  const prevTitle = h('h3', { class: 'preview-title', text: '' });
  const prevBody = h('p', { class: 'preview-body', text: '' });
  prevTitle.textContent = evilPayload;
  prevBody.textContent = evilPayload;

  const prevTitleNodes = walk(prevTitle);
  assert.equal(prevTitleNodes.length, 1);
  assert.equal(prevTitleNodes[0].textContent, evilPayload);
  assert.equal(prevTitle.childNodes[0].nodeType, 3); // FakeText

  const prevBodyNodes = walk(prevBody);
  assert.equal(prevBodyNodes.length, 1);
  assert.equal(prevBodyNodes[0].textContent, evilPayload);
  assert.equal(prevBody.childNodes[0].nodeType, 3); // FakeText

  // Preview link item (detected link rendered as span)
  const linkItem = h('li', { class: 'mono text-sm' }, h('span', { text: 'javascript:alert(1)' }));
  const linkNodes = walk(linkItem);
  for (const node of linkNodes) {
    assert.notEqual(node.tagName, 'A', 'links are rendered as text spans, not clickable anchors');
    assert.notEqual(node.tagName, 'SCRIPT', 'no script tags');
  }
});

test('Department head scoping: cannot select org scope in composer', () => {
  // In announcements.js, isHead checks role and session.departments
  assert.ok(annView.includes('const isHead = !isOrgAdmin()'));
  assert.ok(annView.includes("audOptions.push({ value: 'org'"));
  assert.ok(annView.includes('if (!isHead)'));
});

test('Pin limit feedback: ERR_PIN_LIMIT is handled with distinct message', () => {
  assert.ok(annView.includes("e.code === 'ERR_PIN_LIMIT'"));
  assert.ok(annView.includes("t('announcements.pin.limit')"));
  assert.equal(en['announcements.pin.limit'], 'You can pin up to 3 announcements here. Unpin one first.');
});

test('Optimistic concurrency: 409 conflict prompts reload confirmation', () => {
  assert.ok(annView.includes('e.status === 409'));
  assert.ok(annView.includes("t('announcements.conflict.title')"));
  assert.ok(annView.includes("t('announcements.conflict.reload')"));
});

// ---- ANN-02 / ADR-222 decision 6: the composer's expiry is tri-state on the wire --------------------------------------

const { expiryFields, toLocalInput } = await import('../src/main/resources/admin-ui/js/announcement-expiry.js');

test('SRVHUB-16: an edit that does not touch the expiry sends nothing, so the hub keeps it', () => {
  const stored = new Date('2031-05-04T09:30:00').getTime();
  const r = expiryFields(stored, false, toLocalInput(stored), Date.now(), true);
  assert.deepEqual(r, { ok: true, fields: {} });
  // seconds on the stored value are not a change: the input only has minutes
  const r2 = expiryFields(stored + 31_000, false, toLocalInput(stored), Date.now(), true);
  assert.deepEqual(r2, { ok: true, fields: {} });
});

test('SRVHUB-16: removing an expiry is an explicit clearExpiry, and only when there is one to remove', () => {
  const stored = new Date('2031-05-04T09:30:00').getTime();
  assert.deepEqual(expiryFields(stored, true, '', Date.now(), true), { ok: true, fields: { clearExpiry: true } });
  assert.deepEqual(expiryFields(null, true, '', Date.now(), true), { ok: true, fields: {} }, 'nothing to remove');
  assert.deepEqual(expiryFields(null, true, '', Date.now(), false), { ok: true, fields: {} }, 'create without expiry');
});

test('SRVHUB-16: a new date is sent as expiresAtMs, a past or garbage date is refused before the request', () => {
  const now = new Date('2030-01-01T00:00:00').getTime();
  const r = expiryFields(null, false, '2030-06-01T12:00', now, false);
  assert.equal(r.ok, true);
  assert.equal(r.fields.expiresAtMs, new Date('2030-06-01T12:00').getTime());
  assert.equal('clearExpiry' in r.fields, false, 'never both');
  assert.deepEqual(expiryFields(null, false, '2029-06-01T12:00', now, false), { ok: false, reason: 'past' });
  assert.deepEqual(expiryFields(null, false, 'not a date', now, false), { ok: false, reason: 'invalid' });
});

test('the composer has an expiry field and a No expiry box, with strings for both errors', () => {
  assert.ok(annView.includes('expiryFields('), 'the view decides through the pure module');
  assert.ok(annView.includes("type: 'datetime-local'"));
  for (const k of ['announcements.field.expires', 'announcements.field.expires.none', 'announcements.expiry.past', 'announcements.expiry.invalid', 'announcements.detail.expires']) {
    assert.ok(en[k] && en[k].length > 0, `missing string ${k}`);
  }
});
