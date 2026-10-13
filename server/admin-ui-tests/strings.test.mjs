import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { t, hasKey, setLocale } from '../src/main/resources/admin-ui/js/i18n.js';
import en from '../src/main/resources/admin-ui/js/strings/en.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'admin-ui', 'js');

function sources() {
  const out = [];
  (function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) {
        if (e.name !== 'strings') walk(p);
      } else if (p.endsWith('.js')) out.push(p);
    }
  })(root);
  return out;
}

test('plain, parameterised and plural lookups', () => {
  setLocale('en', {
    'a.plain': 'Hello',
    'a.param': 'Hello {name}, {name}!',
    'a.plural': { one: '{n} device', other: '{n} devices' },
  });
  assert.equal(t('a.plain'), 'Hello');
  assert.equal(t('a.param', { name: 'Lena' }), 'Hello Lena, Lena!');
  assert.equal(t('a.param'), 'Hello {name}, {name}!', 'a missing parameter stays visible');
  assert.equal(t('a.plural', { n: 1 }), '1 device');
  assert.equal(t('a.plural', { n: 1500 }), '1,500 devices');
  assert.equal(t('nothing.here'), 'nothing.here', 'a missing key returns the key');
  assert.equal(hasKey('a.plain'), true);
  setLocale('en', en);
});

test('the English table has no duplicate keys', () => {
  const src = fs.readFileSync(path.join(root, 'strings', 'en.js'), 'utf8');
  const seen = new Map();
  for (const m of src.matchAll(/^\s*'([^']+)'\s*:/gm)) seen.set(m[1], (seen.get(m[1]) ?? 0) + 1);
  const dupes = [...seen].filter(([, n]) => n > 1).map(([k]) => k);
  assert.deepEqual(dupes, []);
  assert.ok(seen.size > 300, 'the scan found the table');
});

test('every string the views ask for exists in en.js', () => {
  const used = new Map();
  for (const f of sources()) {
    if (path.basename(f) === 'i18n.js') continue; // its doc comment shows t('x') as an example
    const s = fs.readFileSync(f, 'utf8');
    for (const m of s.matchAll(/\bt\(\s*'([^']+)'(\s*\+)?/g)) if (!m[2] && !used.has(m[1])) used.set(m[1], f);
    for (const m of s.matchAll(/key:\s*'([a-z]+\.[A-Za-z0-9.]+)'/g)) used.set(m[1], f);
    for (const m of s.matchAll(/'(invites\.bulk\.col\.[a-z]+|settings\.about\.doc\.[a-z]+)'/g)) used.set(m[1], f);
    for (const m of s.matchAll(/t\(\w+ \? '([^']+)' : '([^']+)'/g)) {
      used.set(m[1], f);
      used.set(m[2], f);
    }
  }
  const missing = [...used].filter(([k]) => !(k in en)).map(([k, f]) => k + '  (' + path.relative(root, f) + ')');
  assert.deepEqual(missing, []);
  assert.ok(used.size > 150, 'the scan found the views: ' + used.size);
});

test('plural entries always have an "other" form', () => {
  for (const [k, v] of Object.entries(en)) if (typeof v === 'object') assert.equal(typeof v.other, 'string', k);
});
