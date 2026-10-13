import test from 'node:test';
import assert from 'node:assert/strict';
import { createApi, ApiError, parseRetryAfter } from '../src/main/resources/admin-ui/js/api.js';

function response(status, body, headers = {}) {
  const h = new Headers(headers);
  const text = body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body);
  return { ok: status >= 200 && status < 300, status, headers: h, text: async () => text };
}

function recorder(handler) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return handler(url, init, calls.length);
  };
  return { calls, fetchImpl };
}

test('GET sends no CSRF token, POST/PUT/DELETE always do', async () => {
  const { calls, fetchImpl } = recorder(() => response(200, { ok: true }));
  const api = createApi({ fetchImpl, getCsrf: () => 'tok123' });
  await api.get('/overview');
  await api.post('/invites', { a: 1 });
  await api.put('/settings', { key: 'k', value: 'v' });
  await api.del('/groups/x');
  assert.equal(calls[0].init.headers['X-CSRF-Token'], undefined);
  for (const c of calls.slice(1)) assert.equal(c.init.headers['X-CSRF-Token'], 'tok123', c.init.method);
  assert.equal(calls[1].init.headers['Content-Type'], 'application/json');
  assert.equal(calls[1].init.body, '{"a":1}');
});

test('every call is same-origin, uncached, without referrer, and never follows a redirect', async () => {
  const { calls, fetchImpl } = recorder(() => response(200, {}));
  await createApi({ fetchImpl }).get('/x');
  const init = calls[0].init;
  assert.equal(init.credentials, 'same-origin');
  assert.equal(init.cache, 'no-store');
  assert.equal(init.redirect, 'error');
  assert.equal(init.referrerPolicy, 'no-referrer');
  assert.ok(calls[0].url.startsWith('/admin/api/v1/x'), 'relative URL only');
});

test('the step-up id goes into X-Step-Up and a secret never into the URL', async () => {
  const { calls, fetchImpl } = recorder(() => response(200, {}));
  const api = createApi({ fetchImpl, getCsrf: () => 'c' });
  await api.post('/revocations/serial', { serial: 1 }, { stepUp: 'chal-1' });
  assert.equal(calls[0].init.headers['X-Step-Up'], 'chal-1');
  assert.ok(!calls[0].url.includes('chal-1'));
});

test('query values are encoded and empty ones are dropped', async () => {
  const { calls, fetchImpl } = recorder(() => response(200, {}));
  await createApi({ fetchImpl }).get('/audit', { query: { action: 'a b&c', text: '', order: 'desc', none: undefined } });
  assert.equal(calls[0].url, '/admin/api/v1/audit?action=a+b%26c&order=desc');
});

test('an error answer becomes an ApiError with code, message and correlation id', async () => {
  const { fetchImpl } = recorder(() => response(403, { code: 'ERR_STEP_UP', message: 'Confirm first.', correlationId: 'abc123' }, { 'X-Correlation-Id': 'header-id' }));
  await assert.rejects(createApi({ fetchImpl }).post('/x', {}), (e) => {
    assert.ok(e instanceof ApiError);
    assert.equal(e.status, 403);
    assert.equal(e.code, 'ERR_STEP_UP');
    assert.equal(e.message, 'Confirm first.');
    assert.equal(e.correlationId, 'abc123');
    return true;
  });
});

test('a non-JSON error body still yields an error with the header correlation id', async () => {
  const { fetchImpl } = recorder(() => response(502, '<html>Bad gateway</html>', { 'X-Correlation-Id': 'hdr9' }));
  await assert.rejects(createApi({ fetchImpl }).get('/x'), (e) => e.status === 502 && e.code === 'ERR_HTTP_502' && e.correlationId === 'hdr9');
});

test('a 401 of a signed-in call signals expiry, an anonymous one does not', async () => {
  const { fetchImpl } = recorder(() => response(401, { code: 'ERR_AUTH', message: 'No.' }));
  const seen = [];
  const api = createApi({ fetchImpl, onSessionExpired: (e) => seen.push(e.status) });
  await assert.rejects(api.get('/overview'));
  await assert.rejects(api.get('/session', { anonymous: true }));
  assert.deepEqual(seen, [401]);
});

test('429 reports the Retry-After seconds and notifies', async () => {
  const { fetchImpl } = recorder(() => response(429, { code: 'ERR_RATE_LIMIT', message: 'Slow down.' }, { 'Retry-After': '17' }));
  let limited = null;
  const api = createApi({ fetchImpl, onRateLimited: (e) => (limited = e.retryAfter) });
  await assert.rejects(api.post('/login', {}), (e) => e.retryAfter === 17);
  assert.equal(limited, 17);
  assert.equal(parseRetryAfter('0'), 1);
  assert.equal(parseRetryAfter('999999'), 86400);
  assert.equal(parseRetryAfter('soon'), null);
  assert.equal(parseRetryAfter(null), null);
  assert.equal(parseRetryAfter('Wed, 21 Oct 2026 07:28:00 GMT'), null, 'only the delta-seconds form is read');
});

test('ERR_CSRF refreshes the token once and retries once', async () => {
  let token = 'old';
  const { calls, fetchImpl } = recorder((url, init, n) => (n === 1 ? response(403, { code: 'ERR_CSRF', message: 'x' }) : response(200, { ok: true })));
  const api = createApi({ fetchImpl, getCsrf: () => token, refreshCsrf: async () => { token = 'new'; } });
  assert.deepEqual(await api.post('/invites', {}), { ok: true });
  assert.equal(calls.length, 2);
  assert.equal(calls[0].init.headers['X-CSRF-Token'], 'old');
  assert.equal(calls[1].init.headers['X-CSRF-Token'], 'new');
});

test('a second ERR_CSRF is not retried again', async () => {
  const { calls, fetchImpl } = recorder(() => response(403, { code: 'ERR_CSRF', message: 'x' }));
  const api = createApi({ fetchImpl, refreshCsrf: async () => {} });
  await assert.rejects(api.post('/x', {}), (e) => e.code === 'ERR_CSRF');
  assert.equal(calls.length, 2);
});

test('a network failure is a network ApiError, not a raw exception', async () => {
  const api = createApi({ fetchImpl: async () => { throw new TypeError('Failed to fetch'); } });
  await assert.rejects(api.get('/x'), (e) => e instanceof ApiError && e.kind === 'network' && e.code === 'ERR_NETWORK');
});

test('a slow answer times out', async () => {
  const fetchImpl = (url, init) => new Promise((resolve, reject) => init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
  const api = createApi({ fetchImpl, timeoutMs: 30 });
  await assert.rejects(api.get('/x'), (e) => e.kind === 'timeout' && e.code === 'ERR_TIMEOUT');
});

test('the caller can cancel a request', async () => {
  const fetchImpl = (url, init) => new Promise((resolve, reject) => init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError'))));
  const ctl = new AbortController();
  const api = createApi({ fetchImpl });
  const pending = api.get('/x', { signal: ctl.signal });
  ctl.abort();
  await assert.rejects(pending, (e) => e.kind === 'aborted');
});

test('204 and raw text answers', async () => {
  const none = createApi({ fetchImpl: async () => response(204) });
  assert.equal(await none.get('/x'), null);
  const csv = createApi({ fetchImpl: async () => response(200, 'a,b\r\n1,2\r\n') });
  assert.equal(await csv.get('/audit/export', { raw: true }), 'a,b\r\n1,2\r\n');
});
