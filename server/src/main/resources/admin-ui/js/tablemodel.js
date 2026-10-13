// Pure table logic: text filtering, sorting, selection and the arithmetic of windowed rendering. No DOM here, so it is unit-tested in node.

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/** Every whitespace-separated token of [text] must occur (case-insensitive) in at least one of the [fields] of the row. */
export function matchesText(row, text, fields) {
  const tokens = String(text ?? '').toLowerCase().split(/\s+/).filter(Boolean);
  if (tokens.length === 0) return true;
  const hay = fields.map((f) => String(typeof f === 'function' ? f(row) : row[f] ?? '').toLowerCase()).join('\n');
  return tokens.every((tok) => hay.includes(tok));
}

/** filters: {text, fields, predicates: [row => boolean]} */
export function filterRows(rows, { text = '', fields = [], predicates = [] } = {}) {
  return rows.filter((r) => predicates.every((p) => p(r)) && matchesText(r, text, fields));
}

/** Stable sort by `key` using `getters[key](row)`; null/undefined always sort last whatever the direction. */
export function sortRows(rows, sort, getters) {
  if (!sort || !sort.key || !getters[sort.key]) return rows.slice();
  const get = getters[sort.key];
  const dir = sort.dir === 'desc' ? -1 : 1;
  return rows
    .map((row, i) => ({ row, i, v: get(row) }))
    .sort((a, b) => {
      const an = a.v === null || a.v === undefined;
      const bn = b.v === null || b.v === undefined;
      if (an || bn) return an && bn ? a.i - b.i : an ? 1 : -1;
      const c = typeof a.v === 'number' && typeof b.v === 'number' ? a.v - b.v : collator.compare(String(a.v), String(b.v));
      return c !== 0 ? c * dir : a.i - b.i;
    })
    .map((x) => x.row);
}

/** Click on a column header: none -> asc -> desc -> asc ... (a new column starts ascending). */
export function nextSort(current, key) {
  if (!current || current.key !== key) return { key, dir: 'asc' };
  return { key, dir: current.dir === 'asc' ? 'desc' : 'asc' };
}

export function ariaSort(current, key) {
  if (!current || current.key !== key) return 'none';
  return current.dir === 'asc' ? 'ascending' : 'descending';
}

/**
 * Which rows to render for a scrolled viewport of fixed-height rows.
 * @returns {{start:number,end:number,padTop:number,padBottom:number}} rows [start, end) are rendered; the pads are the heights (px) of the
 * spacer rows above and below.
 */
export function windowRange({ scrollTop, viewportHeight, rowHeight, total, overscan = 8 }) {
  if (total <= 0 || rowHeight <= 0) return { start: 0, end: 0, padTop: 0, padBottom: 0 };
  const first = Math.floor(Math.max(0, scrollTop) / rowHeight);
  const visible = Math.ceil(Math.max(0, viewportHeight) / rowHeight);
  const start = Math.max(0, Math.min(total - 1, first - overscan));
  const end = Math.min(total, first + visible + overscan);
  return { start, end, padTop: start * rowHeight, padBottom: (total - end) * rowHeight };
}

/** A set of selected ids with shift-range support over the currently visible (filtered, sorted) id order. */
export class Selection {
  constructor() {
    this.ids = new Set();
    this.anchor = null;
  }

  has(id) {
    return this.ids.has(id);
  }

  get size() {
    return this.ids.size;
  }

  toggle(id) {
    if (this.ids.has(id)) this.ids.delete(id);
    else this.ids.add(id);
    this.anchor = id;
  }

  /** Selects every id between the anchor and [id] (inclusive) of [order]; with no anchor it selects just [id]. */
  range(id, order) {
    const a = this.anchor === null ? -1 : order.indexOf(this.anchor);
    const b = order.indexOf(id);
    if (a < 0 || b < 0) {
      this.ids.add(id);
    } else {
      const [lo, hi] = a < b ? [a, b] : [b, a];
      for (let i = lo; i <= hi; i += 1) this.ids.add(order[i]);
    }
    this.anchor = id;
  }

  setAll(order) {
    for (const id of order) this.ids.add(id);
  }

  clear() {
    this.ids.clear();
    this.anchor = null;
  }

  /** Drops ids that are no longer in [order] (after a refresh). */
  retain(order) {
    const keep = new Set(order);
    for (const id of Array.from(this.ids)) if (!keep.has(id)) this.ids.delete(id);
  }

  /** 'none' | 'some' | 'all' relative to [order]. */
  state(order) {
    if (this.ids.size === 0 || order.length === 0) return 'none';
    let n = 0;
    for (const id of order) if (this.ids.has(id)) n += 1;
    return n === 0 ? 'none' : n === order.length ? 'all' : 'some';
  }
}
