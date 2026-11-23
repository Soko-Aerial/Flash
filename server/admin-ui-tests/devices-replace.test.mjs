import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { installFakeDocument } from './fakedom.mjs';

installFakeDocument();
globalThis.window = globalThis.window || { addEventListener() {}, removeEventListener() {}, location: { hash: '' } };
globalThis.localStorage = globalThis.localStorage || { getItem() { return null; }, setItem() {}, removeItem() {} };

const { buildReplacePayload, filterReplaceGroups } = await import('../src/main/resources/admin-ui/js/views/devices.js');
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'admin-ui', 'js');
const devicesSource = fs.readFileSync(path.join(root, 'views', 'devices.js'), 'utf8');

test('filterReplaceGroups restricts choices to the target department when set', () => {
  const groups = [
    { groupId: 'g-sales', departmentId: 'sales', name: 'Sales' },
    { groupId: 'g-eng', departmentId: 'eng', name: 'Engineering' },
  ];
  assert.deepEqual(filterReplaceGroups(groups, 'sales').map((g) => g.groupId), ['g-sales']);
  assert.deepEqual(filterReplaceGroups(groups, '').map((g) => g.groupId), ['g-sales', 'g-eng']);
});

test('buildReplacePayload carries department, groups, and auto-joins always include allowedGroups', () => {
  const payload = buildReplacePayload({
    orgAdmin: true,
    selectedDepartment: 'sales',
    currentDepartment: 'eng',
    reason: '  Phone lost on transit  ',
    note: '  Issued spare phone ',
    allowedGroups: ['g-sales-1'],
    autoJoinGroups: ['g-sales-2'],
    defaultReason: 'Device replaced',
  });
  assert.deepEqual(payload, {
    department: 'sales',
    reason: 'Phone lost on transit',
    note: 'Issued spare phone',
    allowedGroups: ['g-sales-1', 'g-sales-2'],
    autoJoinGroups: ['g-sales-2'],
  });
});

test('buildReplacePayload locks department head to current department and applies localized fallback reason', () => {
  const payload = buildReplacePayload({
    orgAdmin: false,
    selectedDepartment: 'other',
    currentDepartment: 'sales',
    reason: '   ',
    note: '',
    allowedGroups: ['g-sales-1'],
    autoJoinGroups: [],
    defaultReason: 'Device replaced',
  });
  assert.equal(payload.department, 'sales');
  assert.equal(payload.reason, 'Device replaced');
  assert.equal(payload.note, null);
});

test('replacement result dialog Close button calls resDlg.close(true)', () => {
  assert.ok(devicesSource.includes('resDlg.close(true)'), 'Close button must close the result dialog');
  assert.ok(!devicesSource.includes("on: { click: () => {} }"), 'No empty no-op click handler may remain');
});
