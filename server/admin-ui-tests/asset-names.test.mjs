// ERROR-746: a content blocker (uBlock, EasyPrivacy, Brave shields...) blocks any URL whose file name looks like a tracker, and ONE blocked ES
// module leaves the whole console blank. Keep these words out of the console's own file names.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '../src/main/resources/admin-ui');
const SUSPECT = /fingerprint|analytics|tracker|tracking|telemetry|beacon|advert|adsense|doubleclick/i;

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

test('no console file name looks like a tracker to a content blocker', () => {
  const bad = walk(root).filter((p) => SUSPECT.test(p.slice(root.length)));
  assert.deepEqual(bad, []);
});
