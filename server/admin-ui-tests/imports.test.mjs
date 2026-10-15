// Every named import of the console's modules must be exported by the module it names. A wrong import is a link-time
// SyntaxError in the browser that blanks the whole console (ERROR-408), and no behaviour test notices because Node tests
// import single modules, not the whole app.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../src/main/resources/admin-ui/js');

function walk(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    return statSync(p).isDirectory() ? walk(p) : p.endsWith('.js') ? [p] : [];
  });
}

function exportsOf(src) {
  const names = new Set();
  for (const m of src.matchAll(/^export\s+(?:async\s+)?(?:function\*?|class|const|let|var)\s+([A-Za-z0-9_$]+)/gm)) names.add(m[1]);
  for (const m of src.matchAll(/^export\s*\{([^}]*)\}/gm)) {
    for (const part of m[1].split(',')) {
      const name = part.trim().split(/\s+as\s+/).pop();
      if (name) names.add(name);
    }
  }
  if (/^export\s+default\b/m.test(src)) names.add('default');
  return names;
}

test('every named import resolves to a real export', () => {
  const files = walk(root);
  const sources = new Map(files.map((f) => [f, readFileSync(f, 'utf8')]));
  const problems = [];
  for (const [file, src] of sources) {
    for (const m of src.matchAll(/^import\s*\{([^}]*)\}\s*from\s*['"](\.[^'"]+)['"]/gm)) {
      const target = resolve(dirname(file), m[2]);
      const targetSrc = sources.get(target);
      if (!targetSrc) { problems.push(`${file}: cannot find ${m[2]}`); continue; }
      if (/^export\s*\*\s*from/m.test(targetSrc)) continue; // re-exports: not checked
      const have = exportsOf(targetSrc);
      for (const part of m[1].split(',')) {
        const name = part.trim().split(/\s+as\s+/)[0];
        if (name && !have.has(name)) problems.push(`${file.replace(root, '')}: '${name}' is not exported by ${m[2]}`);
      }
    }
  }
  assert.deepEqual(problems, []);
});
