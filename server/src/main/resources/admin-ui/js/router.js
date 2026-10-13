// Hash router: `#/devices/abc123?status=ACTIVE&q=lena`. parseHash/buildHash are pure; createRouter wires them to a window-like object.

export function parseHash(hash) {
  let h = String(hash ?? '');
  if (h.startsWith('#')) h = h.slice(1);
  const qi = h.indexOf('?');
  const pathPart = qi >= 0 ? h.slice(0, qi) : h;
  const queryPart = qi >= 0 ? h.slice(qi + 1) : '';
  const segments = pathPart
    .split('/')
    .filter(Boolean)
    .map((s) => {
      try {
        return decodeURIComponent(s);
      } catch (e) {
        return s;
      }
    });
  const query = {};
  for (const [k, v] of new URLSearchParams(queryPart)) query[k] = v;
  return { segments, query, route: segments[0] || '' };
}

export function buildHash(segments, query) {
  const path = '/' + (segments || []).map((s) => encodeURIComponent(s)).join('/');
  const entries = Object.entries(query || {}).filter(([, v]) => v !== undefined && v !== null && v !== '');
  if (entries.length === 0) return '#' + path;
  const p = new URLSearchParams();
  for (const [k, v] of entries) p.append(k, String(v));
  return '#' + path + '?' + p.toString();
}

/**
 * @param {{location: {hash: string}, addEventListener: Function, removeEventListener: Function, history?: {replaceState: Function}}} win
 */
export function createRouter(win, onChange) {
  let current = parseHash(win.location.hash);
  const handler = () => {
    current = parseHash(win.location.hash);
    onChange(current);
  };
  win.addEventListener('hashchange', handler);
  return {
    get current() {
      return current;
    },
    start() {
      onChange(current);
    },
    go(segments, query) {
      const next = buildHash(segments, query);
      if (win.location.hash === next) handler();
      else win.location.hash = next;
    },
    /** Replaces the whole location without adding a history entry (a drawer closing, a filter changing). */
    replace(segments, query) {
      const next = buildHash(segments, query);
      if (win.history && win.history.replaceState) win.history.replaceState(null, '', next);
      current = parseHash(next);
    },
    /** Updates the query part without adding a history entry (filters, sort). */
    replaceQuery(query) {
      this.replace(current.segments, query);
    },
    stop() {
      win.removeEventListener('hashchange', handler);
    },
  };
}
