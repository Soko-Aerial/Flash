// The one fetch wrapper. Same origin only, CSRF header on every state-changing call, a timeout on every call, and one place that decides
// what a 401, 403, 419, 429 or a lost network means. It never logs request or response bodies and never puts a secret in a URL.

export class ApiError extends Error {
  constructor({ status = 0, code = 'ERR_NETWORK', message = '', correlationId = '', retryAfter = null, kind = 'http' }) {
    super(message || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.retryAfter = retryAfter; // seconds, or null
    this.kind = kind; // 'http' | 'network' | 'timeout' | 'aborted'
  }
}

/** `Retry-After` as whole seconds (delta-seconds form only; the hub sends that), clamped to 1 s .. 1 day. */
export function parseRetryAfter(value) {
  if (value === null || value === undefined) return null;
  const n = Number.parseInt(String(value).trim(), 10);
  if (!Number.isFinite(n) || n < 0) return null;
  return Math.min(Math.max(n, 1), 86400);
}

function buildQuery(query) {
  if (!query) return '';
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue;
    p.append(k, String(v));
  }
  const s = p.toString();
  return s ? '?' + s : '';
}

const SAFE_METHODS = new Set(['GET', 'HEAD']);

/**
 * @param {object} o
 * @param {typeof fetch} [o.fetchImpl]
 * @param {string} [o.base]
 * @param {() => string} [o.getCsrf] the session's CSRF token
 * @param {() => Promise<void>|void} [o.refreshCsrf] re-reads the session once after ERR_CSRF
 * @param {(e: ApiError) => void} [o.onSessionExpired] called on 401/419 of a signed-in call
 * @param {(e: ApiError) => void} [o.onRateLimited]
 * @param {number} [o.timeoutMs]
 */
export function createApi({ fetchImpl, base = '/admin/api/v1', getCsrf = () => '', refreshCsrf, onSessionExpired, onRateLimited, timeoutMs = 15000 } = {}) {
  const doFetch = (...args) => (fetchImpl || globalThis.fetch)(...args);

  async function once(method, path, opts) {
    const { body, query, stepUp, signal, anonymous = false, raw = false, timeout = timeoutMs } = opts;
    const headers = { Accept: raw ? 'text/csv, application/json' : 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (!SAFE_METHODS.has(method)) headers['X-CSRF-Token'] = getCsrf() || '';
    if (stepUp) headers['X-Step-Up'] = stepUp;

    const ctl = new AbortController();
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      ctl.abort();
    }, timeout);
    const onAbort = () => ctl.abort();
    if (signal) {
      if (signal.aborted) ctl.abort();
      else signal.addEventListener('abort', onAbort, { once: true });
    }
    let res;
    try {
      res = await doFetch(base + path + buildQuery(query), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: 'same-origin',
        cache: 'no-store',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
        signal: ctl.signal,
      });
    } catch (e) {
      if (timedOut) throw new ApiError({ code: 'ERR_TIMEOUT', kind: 'timeout', message: 'The hub did not answer in time.' });
      if (signal && signal.aborted) throw new ApiError({ code: 'ERR_ABORTED', kind: 'aborted', message: 'Cancelled.' });
      throw new ApiError({ code: 'ERR_NETWORK', kind: 'network', message: 'The hub could not be reached.' });
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', onAbort);
    }

    const correlationHeader = res.headers.get('X-Correlation-Id') || '';
    if (res.ok) {
      if (res.status === 204) return null;
      if (raw) return res.text();
      const text = await res.text();
      return text ? JSON.parse(text) : null;
    }
    let parsed = null;
    try {
      parsed = JSON.parse(await res.text());
    } catch (e) {
      parsed = null;
    }
    const err = new ApiError({
      status: res.status,
      code: (parsed && parsed.code) || 'ERR_HTTP_' + res.status,
      message: (parsed && parsed.message) || '',
      correlationId: (parsed && parsed.correlationId) || correlationHeader,
      retryAfter: parseRetryAfter(res.headers.get('Retry-After')),
    });
    err.anonymous = anonymous;
    throw err;
  }

  async function request(method, path, opts = {}) {
    try {
      return await once(method, path, opts);
    } catch (e) {
      if (!(e instanceof ApiError)) throw e;
      if (e.status === 403 && e.code === 'ERR_CSRF' && refreshCsrf && !opts.__retried) {
        await refreshCsrf();
        return request(method, path, { ...opts, __retried: true });
      }
      if ((e.status === 401 || e.status === 419) && !opts.anonymous && onSessionExpired) onSessionExpired(e);
      if (e.status === 429 && onRateLimited) onRateLimited(e);
      throw e;
    }
  }

  return {
    request,
    get: (path, opts) => request('GET', path, opts),
    post: (path, body, opts) => request('POST', path, { ...opts, body: body === undefined ? {} : body }),
    put: (path, body, opts) => request('PUT', path, { ...opts, body: body === undefined ? {} : body }),
    del: (path, opts) => request('DELETE', path, opts),
  };
}
