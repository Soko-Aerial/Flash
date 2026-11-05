// Design-review helper: serves the mock so a state can be opened as index.html?state=off.
// Node built-ins only; not part of the app build.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const root = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const port = Number(process.argv[2] ?? 5182);
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript' };

createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${port}`);
  const file = normalize(join(root, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname)));
  if (!file.startsWith(normalize(root))) {
    res.writeHead(403).end();
    return;
  }
  try {
    const body = await readFile(file);
    res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404).end('not found');
  }
}).listen(port, '127.0.0.1', () => console.log(`radio-v3 mock on http://127.0.0.1:${port}/`));
