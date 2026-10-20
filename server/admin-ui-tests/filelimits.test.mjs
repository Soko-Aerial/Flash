import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { FILE_LIMITS, MB, limitFor, toDisplay, parseInput, parseForm, combinationWarnings, changedKeys, topUsage } from '../src/main/resources/admin-ui/js/filelimits.js';

const here = dirname(fileURLToPath(import.meta.url));
const kotlin = readFileSync(resolve(here, '../src/main/kotlin/com/transfer/flash/server/files/FileSettings.kt'), 'utf8');
const contract = readFileSync(resolve(here, '../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt'), 'utf8');

/** A Kotlin `const val NAME: Type = <expr>` evaluated for the three shapes the sources use (plain, `N * 1024 ...`, `X.toLong()`). */
function constant(src, name) {
  const m = new RegExp('const val ' + name + ':[^=]*=\s*([^\n]+)').exec(src);
  assert.ok(m, 'constant ' + name + ' not found');
  let expr = m[1].trim();
  const ref = /^(HubContract\.)?([A-Z_]+)(\.toLong\(\))?$/.exec(expr);
  if (ref) return constant(ref[1] ? contract : src, ref[2]);
  expr = expr.replace(/L\b/g, '').replace(/\.toLong\(\)/g, '');
  assert.match(expr, /^[0-9 *]+$/, 'unexpected expression for ' + name + ': ' + expr);
  return expr.split('*').reduce((a, b) => a * Number(b.trim()), 1);
}

test('the page repeats exactly the bounds the hub enforces', () => {
  assert.equal(limitFor('maxFileBytes').min * MB, constant(contract, 'MIN_FILE_BYTES_FLOOR'));
  assert.equal(limitFor('maxFileBytes').max * MB, constant(contract, 'MAX_FILE_BYTES_CEILING'));
  assert.equal(limitFor('deviceStoredBytes').min * MB, constant(kotlin, 'MIN_DEVICE_STORED_BYTES'));
  assert.equal(limitFor('deviceStoredBytes').max * MB, constant(kotlin, 'MAX_DEVICE_STORED_BYTES'));
  assert.equal(limitFor('globalStoredBytes').min * MB, constant(kotlin, 'MIN_GLOBAL_STORED_BYTES'));
  assert.equal(limitFor('globalStoredBytes').max * MB, constant(kotlin, 'MAX_GLOBAL_STORED_BYTES'));
  assert.equal(limitFor('fileRetentionDays').min, constant(kotlin, 'MIN_RETENTION_DAYS'));
  assert.equal(limitFor('fileRetentionDays').max, constant(kotlin, 'MAX_RETENTION_DAYS'));
});

test('the keys are the four the server owns', () => {
  const server = [...kotlin.matchAll(/const val [A-Z_]+: String = "([A-Za-z]+)"/g)].map((m) => m[1]).sort();
  assert.deepEqual(FILE_LIMITS.map((l) => l.key).sort(), server);
});

test('megabytes are read as whole numbers and stored as bytes', () => {
  assert.deepEqual(parseInput('maxFileBytes', '50'), { ok: true, stored: 50 * MB });
  assert.deepEqual(parseInput('maxFileBytes', ' 2048 '), { ok: true, stored: 2048 * MB });
  assert.deepEqual(parseInput('fileRetentionDays', '7'), { ok: true, stored: 7 });
  assert.equal(toDisplay('maxFileBytes', 50 * MB), '50');
  assert.equal(toDisplay('maxFileBytes', 50 * MB + 5), '50');
  assert.equal(toDisplay('fileRetentionDays', 7), '7');
  assert.equal(toDisplay('maxFileBytes', undefined), '');
  assert.equal(toDisplay('nope', 5), '');
});

test('every bound is inclusive and the next value out is refused with the right reason', () => {
  for (const l of FILE_LIMITS) {
    assert.equal(parseInput(l.key, String(l.min)).ok, true, l.key + ' min');
    assert.equal(parseInput(l.key, String(l.max)).ok, true, l.key + ' max');
    assert.deepEqual(parseInput(l.key, String(l.min - 1)), { ok: false, reason: l.min - 1 < 0 ? 'notNumber' : 'tooSmall' }, l.key + ' below');
    assert.deepEqual(parseInput(l.key, String(l.max + 1)), { ok: false, reason: 'tooLarge' }, l.key + ' above');
  }
});

test('anything that is not plain digits is refused', () => {
  for (const bad of ['1e3', '0x10', '5.5', '-5', '+5', '5 MB', '1,000', '٣', '', '   ', null, undefined, '９', '1_000', 'NaN', 'Infinity']) {
    const r = parseInput('maxFileBytes', bad);
    assert.equal(r.ok, false, JSON.stringify(bad));
    assert.ok(r.reason === 'empty' || r.reason === 'notNumber', JSON.stringify(bad));
  }
  assert.equal(parseInput('maxFileBytes', '9'.repeat(40)).reason, 'notNumber', 'a value too long to be exact is not a number here');
  assert.equal(parseInput('unknown', '5').ok, false);
});

test('the whole form reports every bad field at once and stores only the good ones', () => {
  const { errors, stored } = parseForm({ maxFileBytes: '50', deviceStoredBytes: 'x', globalStoredBytes: '0', fileRetentionDays: '7' });
  assert.deepEqual(errors, { deviceStoredBytes: 'notNumber', globalStoredBytes: 'tooSmall' });
  assert.deepEqual(stored, { maxFileBytes: 50 * MB, fileRetentionDays: 7 });
  assert.deepEqual(parseForm({ maxFileBytes: '50', deviceStoredBytes: '500', globalStoredBytes: '5120', fileRetentionDays: '7' }).errors, {});
});

test('a combination the hub would narrow is warned about', () => {
  assert.deepEqual(combinationWarnings({ maxFileBytes: 10, deviceStoredBytes: 10, globalStoredBytes: 10 }), []);
  assert.deepEqual(combinationWarnings({ maxFileBytes: 20, deviceStoredBytes: 30, globalStoredBytes: 10 }), ['maxAboveGlobal', 'deviceAboveGlobal']);
  assert.deepEqual(combinationWarnings({ maxFileBytes: 20, deviceStoredBytes: 10, globalStoredBytes: 100 }), ['maxAboveDevice']);
  assert.deepEqual(combinationWarnings({ maxFileBytes: 20 }), [], 'a missing field is not a warning');
});

test('only a changed value is sent', () => {
  const current = { maxFileBytes: 50 * MB, deviceStoredBytes: 500 * MB, globalStoredBytes: 5120 * MB, fileRetentionDays: 7 };
  assert.deepEqual(changedKeys({ ...current }, current), []);
  assert.deepEqual(changedKeys({ ...current, fileRetentionDays: 14, maxFileBytes: 100 * MB }, current), ['maxFileBytes', 'fileRetentionDays']);
});

test('usage rows are the biggest first, ties by id, and hostile rows are dropped', () => {
  const rows = [
    { deviceId: 'b', files: 1, bytes: 10 },
    { deviceId: 'a', files: 2, bytes: 10 },
    { deviceId: 'c', files: 1, bytes: 99 },
    null,
    { deviceId: 5, files: 1, bytes: 1000 },
    { files: 1, bytes: 1000 },
  ];
  assert.deepEqual(topUsage(rows, 2).map((r) => r.deviceId), ['c', 'a']);
  assert.deepEqual(topUsage(rows, 0), []);
  assert.deepEqual(topUsage(undefined), []);
  assert.deepEqual(topUsage('x'), []);
});
