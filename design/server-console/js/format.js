export const nf = new Intl.NumberFormat('en-GB');
export const nf1 = new Intl.NumberFormat('en-GB', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

export function n(value) { return nf.format(value); }
export function n1(value) { return nf1.format(value); }

export function bytes(value) {
  if (value >= 1024 ** 3) return `${n1(value / 1024 ** 3)} GB`;
  if (value >= 1024 ** 2) return `${Math.round(value / 1024 ** 2)} MB`;
  if (value >= 1024) return `${Math.round(value / 1024)} KB`;
  return `${value} B`;
}

export function initials(name) {
  const parts = String(name).trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function clock(date) {
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
}

export function clockSeconds(date) {
  return new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format(date);
}

export function dayMonth(date) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' }).format(date);
}

export function fullDate(date) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'long', year: 'numeric' }).format(date);
}

export function dateTime(date) {
  return new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(date);
}

export function relative(minutes) {
  if (minutes < 1) return 'now';
  if (minutes < 60) return `${minutes} min`;
  if (minutes < 1440) return `${Math.round(minutes / 60)} h`;
  const days = Math.round(minutes / 1440);
  return days === 1 ? 'Yesterday' : `${days} days`;
}

export function ago(date, now = new Date('2026-11-05T09:45:00+01:00')) {
  const minutes = Math.max(0, Math.round((now - date) / 60000));
  return relative(minutes);
}

export function plural(count, one, many) {
  return `${n(count)} ${count === 1 ? one : many}`;
}

export function roleLabel(key) {
  return { member: 'Member', dept_admin: 'Department admin', org_admin: 'Organization admin' }[key] || key;
}

export function shortId(id, head = 8, tail = 4) {
  if (!id || id.length <= head + tail + 1) return id;
  return `${id.slice(0, head)}…${id.slice(-tail)}`;
}
