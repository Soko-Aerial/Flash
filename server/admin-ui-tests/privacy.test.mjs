import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import en from '../src/main/resources/admin-ui/js/strings/en.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'src', 'main', 'resources', 'admin-ui', 'js');
const orgView = fs.readFileSync(path.join(root, 'views', 'organization.js'), 'utf8');

// Plan A2 U08: the hidden toggle, its confirmation dialog and its exact help text.
test('the department dialog has a hidden toggle with confirmation', () => {
  assert.ok(orgView.includes("t('org.dept.hidden')"), 'toggle label');
  assert.ok(orgView.includes("t('org.dept.hidden.help')"), 'help text under the toggle');
  assert.ok(orgView.includes("t('org.dept.hidden.confirm.title')"), 'confirmation dialog');
  assert.ok(orgView.includes('hidden: wantHidden'), 'the flag is sent on create and edit');
});

test('the hidden help text says exactly what hidden means', () => {
  assert.equal(
    en['org.dept.hidden'],
    'Hidden from the company directory',
  );
  assert.equal(
    en['org.dept.hidden.help'],
    'People in other departments will not see this department, its people or its groups in the directory. They can still message each other if they already know each other. Devices on the same Wi-Fi may still see each other\'s device names. Administrators and the department\'s own members still see it.',
  );
  assert.equal(en['org.dept.hidden.note'], 'Applies to everyone\'s directory at their next update.');
});
