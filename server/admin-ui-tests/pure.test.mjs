import test from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto, createHash } from 'node:crypto';
import { formatBytes, formatDuration, formatClock, relativeTime, shortId, groupText, slugify, formatNumber } from '../src/main/resources/admin-ui/js/format.js';
import { parseCsv, validateBulk, csvCell, toCsv, MAX_BULK_ROWS, toBulkCsv } from '../src/main/resources/admin-ui/js/csv.js';
import { matchesText, filterRows, sortRows, nextSort, ariaSort, windowRange, Selection } from '../src/main/resources/admin-ui/js/tablemodel.js';
import { parseHash, buildHash, createRouter } from '../src/main/resources/admin-ui/js/router.js';
import { fingerprintModel } from '../src/main/resources/admin-ui/js/identicon.js';
import { bytesToBase64, base64ToBytes, proofMessage, consoleKeyId, PROOF_TAG, toHex } from '../src/main/resources/admin-ui/js/codec.js';

// ------------------------------------------------------------------------------------------------ format

test('byte sizes', () => {
  assert.equal(formatBytes(0, 'en'), '0 B');
  assert.equal(formatBytes(1023, 'en'), '1,023 B');
  assert.equal(formatBytes(1536, 'en'), '1.5 KB');
  assert.equal(formatBytes(5 * 1024 * 1024, 'en'), '5 MB');
  assert.equal(formatBytes(null), '–');
});

test('durations and clocks', () => {
  assert.equal(formatDuration(45_000), '45 s');
  assert.equal(formatDuration(125_000), '2 min 05 s');
  assert.equal(formatDuration(3 * 3600_000 + 5 * 60_000), '3 h 05 min');
  assert.equal(formatDuration(3 * 86400_000 + 4 * 3600_000), '3 d 4 h');
  assert.equal(formatDuration(-1), '–');
  assert.equal(formatClock(59 * 1000), '00:59');
  assert.equal(formatClock(3725 * 1000), '1:02:05');
  assert.equal(formatClock(-5), '00:00');
});

test('relative times read as people say them', () => {
  const now = Date.UTC(2026, 9, 13, 12, 0, 0);
  assert.equal(relativeTime(now - 2 * 60_000, now, 'en'), '2 minutes ago');
  assert.equal(relativeTime(now + 3 * 3600_000, now, 'en'), 'in 3 hours');
  assert.equal(relativeTime(now - 10_000, now, 'en'), 'now');
  assert.equal(relativeTime(now - 2 * 86400_000, now, 'en'), '2 days ago');
  assert.equal(relativeTime(undefined, now, 'en'), '–');
});

test('identifiers and slugs', () => {
  assert.equal(shortId('abcdefghijklmnopqrstuvwxyz'), 'abcdefgh…wxyz');
  assert.equal(shortId('short'), 'short');
  assert.equal(groupText('ABCDEFGHIJ', 4), 'ABCD EFGH IJ');
  assert.equal(slugify('Sales & Marketing'), 'sales-marketing');
  assert.equal(slugify('Zürich Büro'), 'zurich-buro');
  assert.equal(slugify('***'), 'item');
  assert.equal(formatNumber(1234567, 'en'), '1,234,567');
});

// ------------------------------------------------------------------------------------------------ csv

test('CSV reading handles quotes, doubled quotes, CRLF and a BOM', () => {
  assert.deepEqual(parseCsv('a,b\r\n"x, y","say ""hi"""\r\n'), [['a', 'b'], ['x, y', 'say "hi"']]);
  assert.deepEqual(parseCsv('﻿name\nLena\n\n'), [['name'], ['Lena']]);
  assert.deepEqual(parseCsv('one,,three'), [['one', '', 'three']]);
  assert.deepEqual(parseCsv('"multi\nline",x'), [['multi\nline', 'x']]);
  assert.deepEqual(parseCsv(''), []);
});

test('bulk invite validation mirrors the hub limits', () => {
  const depts = [{ id: 'dept-gh', name: 'Ghana' }];
  const r = validateBulk('name,department\nLena,ghana\nLena,Ghana\n,Ghana\nBen,Nowhere\n' + 'x'.repeat(65) + '\nCleo', depts);
  const byName = Object.fromEntries(r.rows.map((x) => [x.name || '(empty)', x]));
  assert.equal(r.rows[0].department, 'dept-gh');
  assert.deepEqual(r.rows[0].errors, []);
  assert.deepEqual(r.rows[1].errors, ['duplicate']);
  assert.deepEqual(byName['(empty)'].errors, ['name-missing']);
  assert.deepEqual(byName.Ben.errors, ['department-unknown']);
  assert.deepEqual(r.rows[4].errors, ['name-too-long']);
  assert.deepEqual(byName.Cleo.errors, []);
  assert.equal(r.tooMany, false);
  const many = validateBulk(Array.from({ length: MAX_BULK_ROWS + 1 }, (_, i) => 'n' + i).join('\n'));
  assert.equal(many.tooMany, true);
  assert.equal(toBulkCsv([{ name: 'A, B', department: 'd' }]), '"A, B",d');
});

test('CSV writing neutralises spreadsheet formulas and quotes what needs it', () => {
  for (const evil of ['=HYPERLINK("http://x")', '+1+1', '-2+3', '@SUM(A1)', '\tcmd', '\rcmd']) {
    assert.ok(csvCell(evil).replace(/^"/, '').startsWith("'"), JSON.stringify(evil));
  }
  assert.equal(csvCell(-5), '-5', 'a real number is not a formula');
  assert.equal(csvCell('a,b'), '"a,b"');
  assert.equal(csvCell('say "x"'), '"say ""x"""');
  assert.equal(csvCell(null), '');
  assert.equal(toCsv(['a', 'b'], [[1, 'x']]), 'a,b\r\n1,x\r\n');
});

// ------------------------------------------------------------------------------------------------ table model

test('filtering needs every token somewhere in the row', () => {
  const rows = [{ n: 'Lena Front', d: 'Accra' }, { n: 'Ben', d: 'Accra Front' }, { n: 'Cleo', d: 'Tema' }];
  assert.equal(matchesText(rows[0], 'lena accra', ['n', 'd']), true);
  assert.equal(matchesText(rows[2], 'lena', ['n', 'd']), false);
  assert.deepEqual(filterRows(rows, { text: 'front', fields: ['n', 'd'] }).map((r) => r.n), ['Lena Front', 'Ben']);
  assert.deepEqual(filterRows(rows, { predicates: [(r) => r.d === 'Tema'] }).map((r) => r.n), ['Cleo']);
  assert.equal(filterRows(rows, {}).length, 3);
});

test('sorting is stable, numeric-aware, and keeps empty values last in both directions', () => {
  const rows = [{ id: 'dev10', v: 2 }, { id: 'dev2', v: null }, { id: 'dev1', v: 2 }, { id: 'dev3', v: 1 }];
  const getters = { id: (r) => r.id, v: (r) => r.v };
  assert.deepEqual(sortRows(rows, { key: 'id', dir: 'asc' }, getters).map((r) => r.id), ['dev1', 'dev2', 'dev3', 'dev10']);
  assert.deepEqual(sortRows(rows, { key: 'v', dir: 'asc' }, getters).map((r) => r.id), ['dev3', 'dev10', 'dev1', 'dev2']);
  assert.deepEqual(sortRows(rows, { key: 'v', dir: 'desc' }, getters).map((r) => r.id), ['dev10', 'dev1', 'dev3', 'dev2']);
  assert.deepEqual(sortRows(rows, null, getters), rows);
  assert.equal(nextSort(null, 'a').dir, 'asc');
  assert.equal(nextSort({ key: 'a', dir: 'asc' }, 'a').dir, 'desc');
  assert.equal(nextSort({ key: 'a', dir: 'desc' }, 'b').dir, 'asc');
  assert.equal(ariaSort({ key: 'a', dir: 'desc' }, 'a'), 'descending');
  assert.equal(ariaSort({ key: 'a', dir: 'desc' }, 'b'), 'none');
});

test('windowed rendering only ever renders what is near the viewport', () => {
  const w = windowRange({ scrollTop: 0, viewportHeight: 400, rowHeight: 40, total: 5000, overscan: 5 });
  assert.deepEqual(w, { start: 0, end: 15, padTop: 0, padBottom: (5000 - 15) * 40 });
  const mid = windowRange({ scrollTop: 40_000, viewportHeight: 400, rowHeight: 40, total: 5000, overscan: 5 });
  assert.equal(mid.start, 995);
  assert.equal(mid.end, 1015);
  assert.equal(mid.padTop + (mid.end - mid.start) * 40 + mid.padBottom, 5000 * 40, 'spacers keep the scroll height exact');
  const end = windowRange({ scrollTop: 10_000_000, viewportHeight: 400, rowHeight: 40, total: 100 });
  assert.equal(end.end, 100);
  assert.ok(end.start < end.end);
  assert.deepEqual(windowRange({ scrollTop: 0, viewportHeight: 400, rowHeight: 40, total: 0 }), { start: 0, end: 0, padTop: 0, padBottom: 0 });
});

test('selection supports shift ranges and survives a refresh', () => {
  const order = ['a', 'b', 'c', 'd', 'e'];
  const s = new Selection();
  s.toggle('b');
  s.range('d', order);
  assert.deepEqual([...s.ids].sort(), ['b', 'c', 'd']);
  assert.equal(s.state(order), 'some');
  s.retain(['a', 'b']);
  assert.deepEqual([...s.ids], ['b']);
  s.setAll(order);
  assert.equal(s.state(order), 'all');
  s.clear();
  assert.equal(s.state(order), 'none');
  assert.equal(s.size, 0);
});

// ------------------------------------------------------------------------------------------------ router

test('hash parsing and building round-trip, including awkward characters', () => {
  const p = parseHash('#/devices/abc%2F1?status=ACTIVE&q=a%20b%26c');
  assert.deepEqual(p.segments, ['devices', 'abc/1']);
  assert.deepEqual(p.query, { status: 'ACTIVE', q: 'a b&c' });
  assert.equal(p.route, 'devices');
  assert.deepEqual(parseHash(buildHash(p.segments, p.query)), p);
  assert.equal(buildHash(['audit'], { a: '', b: undefined }), '#/audit');
  assert.equal(parseHash('').route, '');
  assert.deepEqual(parseHash('#/%E0%A4%A').segments, ['%E0%A4%A'], 'a broken escape does not throw');
});

test('the router navigates, replaces without history, and notifies', () => {
  const listeners = new Map();
  const replaced = [];
  const win = {
    location: { hash: '#/overview' },
    addEventListener: (t, f) => listeners.set(t, f),
    removeEventListener: (t) => listeners.delete(t),
    history: { replaceState: (a, b, url) => { replaced.push(url); win.location.hash = url; } },
  };
  const seen = [];
  const router = createRouter(win, (r) => seen.push(r.route));
  router.start();
  router.go(['devices'], { q: 'x' });
  assert.equal(win.location.hash, '#/devices?q=x');
  listeners.get('hashchange')();
  assert.deepEqual(seen, ['overview', 'devices']);
  router.replace(['devices', 'abc'], {});
  assert.deepEqual(replaced, ['#/devices/abc']);
  assert.deepEqual(router.current.segments, ['devices', 'abc']);
  router.replaceQuery({ status: 'REVOKED' });
  assert.equal(replaced.at(-1), '#/devices/abc?status=REVOKED');
  router.stop();
  assert.equal(listeners.size, 0);
});

// ------------------------------------------------------------------------------------------------ fingerprint

test('the visual fingerprint is deterministic, mirrored, and different for different input', () => {
  const a = fingerprintModel('ab12cd34'.repeat(8));
  assert.deepEqual(a, fingerprintModel('AB12 CD34 '.replace(/ /g, '') + 'ab12cd34'.repeat(7)), 'case and spacing do not matter');
  const cells = new Set(a.cells.map((c) => c.x + ',' + c.y));
  for (const c of a.cells) assert.ok(cells.has(a.size - 1 - c.x + ',' + c.y), 'mirrored');
  const b = fingerprintModel('ab12cd35'.repeat(8));
  assert.notDeepEqual(a.cells, b.cells);
  assert.ok(a.hue >= 0 && a.hue < 360);
});

// ------------------------------------------------------------------------------------------------ codec and the console key

test('base64 round-trips every byte value', () => {
  const all = Uint8Array.from({ length: 256 }, (_, i) => i);
  assert.deepEqual(base64ToBytes(bytesToBase64(all)), all);
  assert.equal(bytesToBase64(new Uint8Array(70_000)).length, Math.ceil(70_000 / 3) * 4);
  assert.equal(toHex(Uint8Array.from([0, 15, 255])), '000fff');
});

test('the proof message and the key id match an independent computation', async () => {
  const spki = 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEexample';
  assert.equal(new TextDecoder().decode(proofMessage('boss', spki)), PROOF_TAG + '\nboss\n' + spki);
  assert.equal(PROOF_TAG, 'flash-console-key-v1');
  const expected = 'console-' + createHash('sha256').update(spki, 'utf8').digest('hex').slice(0, 16);
  assert.equal(await consoleKeyId(spki, webcrypto.subtle), expected);
});

test('a WebCrypto ECDSA signature is 64 bytes (P1363) and verifies over the proof message', async () => {
  const key = await webcrypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign', 'verify']);
  const msg = proofMessage('boss', 'spki');
  const sig = new Uint8Array(await webcrypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key.privateKey, msg));
  assert.equal(sig.length, 64, 'the hub converts exactly this form (ConsoleKeys.p1363ToDer)');
  assert.equal(await webcrypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key.publicKey, sig, msg), true);
  const spki = new Uint8Array(await webcrypto.subtle.exportKey('spki', key.publicKey));
  assert.ok(spki.length >= 80 && bytesToBase64(spki).length <= 200, 'within the sizes the hub accepts');
  await assert.rejects(webcrypto.subtle.exportKey('pkcs8', key.privateKey), 'a non-extractable private key can not be read out');
});
