// Number, size, date and relative-time formatting in the administrator's locale. Pure functions: [now] and [locale] are injectable.

function loc(locale) {
  if (locale) return locale;
  try {
    return globalThis.navigator && globalThis.navigator.language ? globalThis.navigator.language : 'en';
  } catch (e) {
    return 'en';
  }
}

export function formatNumber(n, locale) {
  if (n === null || n === undefined || Number.isNaN(n)) return '–';
  return new Intl.NumberFormat(loc(locale)).format(n);
}

export function formatBytes(bytes, locale) {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '–';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let v = Math.abs(bytes);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  const digits = i === 0 || v >= 100 ? 0 : v >= 10 ? 1 : 2;
  const num = new Intl.NumberFormat(loc(locale), { maximumFractionDigits: digits, minimumFractionDigits: 0 }).format(bytes < 0 ? -v : v);
  return num + ' ' + units[i];
}

export function formatDateTime(ms, locale, timeZone) {
  if (ms === null || ms === undefined) return '–';
  const opts = { dateStyle: 'medium', timeStyle: 'medium' };
  if (timeZone) opts.timeZone = timeZone;
  return new Intl.DateTimeFormat(loc(locale), opts).format(new Date(ms));
}

export function formatDate(ms, locale, timeZone) {
  if (ms === null || ms === undefined) return '–';
  const opts = { dateStyle: 'medium' };
  if (timeZone) opts.timeZone = timeZone;
  return new Intl.DateTimeFormat(loc(locale), opts).format(new Date(ms));
}

/** "3 minutes ago" / "in 2 days". Falls back to the date after [maxDays] days. */
export function relativeTime(ms, now = Date.now(), locale) {
  if (ms === null || ms === undefined) return '–';
  const diff = ms - now;
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat(loc(locale), { numeric: 'auto' });
  if (abs < 45_000) return rtf.format(0, 'second');
  const table = [
    [60_000, 'minute', 3_600_000],
    [3_600_000, 'hour', 86_400_000],
    [86_400_000, 'day', 30 * 86_400_000],
    [30 * 86_400_000, 'month', 365 * 86_400_000],
    [365 * 86_400_000, 'year', Infinity],
  ];
  for (const [unitMs, unit, limit] of table) {
    if (abs < limit) return rtf.format(Math.round(diff / unitMs), unit);
  }
  return formatDate(ms, locale);
}

/** "2 h 05 min", "3 d 4 h", "45 s": a compact duration for countdowns and ages. */
export function formatDuration(ms) {
  if (ms === null || ms === undefined || ms < 0) return '–';
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60);
  if (m < 60) return m + ' min ' + String(s % 60).padStart(2, '0') + ' s';
  const hh = Math.floor(m / 60);
  if (hh < 24) return hh + ' h ' + String(m % 60).padStart(2, '0') + ' min';
  const d = Math.floor(hh / 24);
  return d + ' d ' + (hh % 24) + ' h';
}

/** mm:ss or h:mm:ss for a session countdown. */
export function formatClock(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(sec).padStart(2, '0');
  return h > 0 ? h + ':' + mm + ':' + ss : mm + ':' + ss;
}

/** Shortens a long identifier for a table cell: first 8 and last 4 characters. */
export function shortId(id, head = 8, tail = 4) {
  const s = String(id ?? '');
  return s.length <= head + tail + 1 ? s : s.slice(0, head) + '…' + s.slice(-tail);
}

/** Groups a hex or base32 string for reading: "ABCD EFGH IJKL". */
export function groupText(text, size = 4) {
  const s = String(text ?? '').replace(/\s+/g, '');
  const parts = [];
  for (let i = 0; i < s.length; i += size) parts.push(s.slice(i, i + size));
  return parts.join(' ');
}

/** A URL- and id-safe lowercase slug of a display name ("Sales & Marketing" becomes "sales-marketing"). */
export function slugify(text) {
  const s = String(text ?? '')
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48)
    .replace(/-+$/g, '');
  return s || 'item';
}
