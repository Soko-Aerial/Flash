// A data table with real table semantics, sortable headers, optional selection column and windowed rendering: above WINDOW_AT rows only
// the rows in view (plus an overscan) are in the DOM and two spacer rows keep the scrollbar honest. Row height is fixed, so the maths
// is exact (tablemodel.windowRange). Sorting and filtering happen on the caller's side; this component only displays the rows it is given.

import { h } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { windowRange, Selection, ariaSort, nextSort } from '../tablemodel.js';

const WINDOW_AT = 120;

/**
 * @param {object} o
 * @param {{key:string,label:string,sortable?:boolean,primary?:boolean,className?:string,render:(row:any)=>Node|string|null}[]} o.columns
 * @param {(row:any)=>string} o.rowId
 * @param {string} o.ariaLabel
 * @param {number} [o.rowHeight]
 * @param {boolean} [o.selectable]
 * @param {(row:any)=>void} [o.onOpen]
 * @param {(sort:{key:string,dir:string})=>void} [o.onSort]
 * @param {(row:any)=>string} [o.rowClass]
 * @param {(row:any)=>string} [o.rowLabel] accessible name of a row (selection checkbox, open button)
 * @param {(selection:Selection)=>void} [o.onSelectionChange]
 */
export function createTable({ columns, rowId, ariaLabel, rowHeight = 52, selectable = false, onOpen, onSort, rowClass, rowLabel, onSelectionChange }) {
  const selection = new Selection();
  let rows = [];
  let sort = null;
  let order = [];
  let lastRange = { start: -1, end: -1 };

  const colCount = columns.length + (selectable ? 1 : 0);
  const selectAll = selectable
    ? h('input', { type: 'checkbox', class: 'check', 'aria-label': t('table.selectAll'), on: { change: () => toggleAll() } })
    : null;

  const headRow = h('tr', { 'aria-rowindex': '1' });
  if (selectable) headRow.appendChild(h('th', { scope: 'col', class: 'col-select' }, selectAll));
  for (const col of columns) {
    const th = h('th', { scope: 'col', class: ['col-' + col.key, col.className || ''], 'aria-sort': col.sortable ? 'none' : null });
    if (col.sortable) {
      th.appendChild(
        h(
          'button',
          {
            class: 'th-btn',
            type: 'button',
            on: {
              click: () => {
                sort = nextSort(sort, col.key);
                refreshHeaders();
                if (onSort) onSort(sort);
              },
            },
          },
          h('span', { text: col.label }),
          h('span', { class: 'th-sort', 'aria-hidden': 'true' }),
        ),
      );
    } else th.appendChild(h('span', { text: col.label }));
    col.th = th;
    headRow.appendChild(th);
  }
  const thead = h('thead', {}, headRow);
  const tbody = h('tbody');
  const table = h('table', { class: 'table', 'aria-label': ariaLabel, 'aria-rowcount': '1' }, thead, tbody);
  const scroller = h('div', { class: 'table-scroll', tabindex: '-1' }, table);
  const el = h('div', { class: 'table-wrap' }, scroller);
  el.style.setProperty('--row-h', rowHeight + 'px');

  function refreshHeaders() {
    for (const col of columns) {
      if (!col.sortable) continue;
      col.th.setAttribute('aria-sort', ariaSort(sort, col.key));
      const slot = col.th.querySelector('.th-sort');
      while (slot.firstChild) slot.removeChild(slot.firstChild);
      if (sort && sort.key === col.key) slot.appendChild(icon(sort.dir === 'asc' ? 'sortAsc' : 'sortDesc', { size: 14 }));
    }
  }

  function updateSelectAll() {
    if (!selectAll) return;
    const st = selection.state(order);
    selectAll.checked = st === 'all';
    selectAll.indeterminate = st === 'some';
  }

  function emitSelection() {
    updateSelectAll();
    if (onSelectionChange) onSelectionChange(selection);
  }

  function toggleAll() {
    if (selection.state(order) === 'all') selection.clear();
    else selection.setAll(order);
    renderRows(true);
    emitSelection();
  }

  function buildRow(row, index) {
    const id = rowId(row);
    const selected = selection.has(id);
    const tr = h('tr', { 'aria-rowindex': String(index + 2), 'data-row-id': id, class: ['row', rowClass ? rowClass(row) : '', selected ? 'is-selected' : ''], 'aria-selected': selectable ? String(selected) : null });
    if (selectable) {
      const cb = h('input', {
        type: 'checkbox',
        class: 'check',
        'aria-label': t('table.selectRow', { name: rowLabel ? rowLabel(row) : id }),
        checked: selected,
        'data-focus-key': id + ':check',
        on: {
          click: (ev) => {
            if (ev.shiftKey) selection.range(id, order);
            else selection.toggle(id);
            if (ev.shiftKey) renderRows(true);
            else tr.classList.toggle('is-selected', selection.has(id));
            if (!ev.shiftKey) tr.setAttribute('aria-selected', String(selection.has(id)));
            emitSelection();
          },
        },
      });
      tr.appendChild(h('td', { class: 'col-select' }, cb));
    }
    for (const col of columns) {
      const content = col.render(row);
      const td = h('td', { class: ['col-' + col.key, col.className || ''] });
      if (col.primary && onOpen) {
        td.appendChild(
          h('button', { class: 'link-btn', type: 'button', 'data-focus-key': id + ':open', 'aria-label': rowLabel ? t('table.open', { name: rowLabel(row) }) : null, on: { click: () => onOpen(row) } }, content),
        );
      } else if (content !== null && content !== undefined) td.appendChild(typeof content === 'string' ? globalThis.document.createTextNode(content) : content);
      tr.appendChild(td);
    }
    return tr;
  }

  function spacer(height) {
    const tr = h('tr', { class: 'spacer', 'aria-hidden': 'true' }, h('td', { colspan: String(colCount) }));
    tr.style.setProperty('height', height + 'px');
    return tr;
  }

  function currentFocusKey() {
    const a = globalThis.document.activeElement;
    return a && tbody.contains(a) ? a.getAttribute('data-focus-key') : null;
  }

  function renderRows(force = false) {
    const total = rows.length;
    table.setAttribute('aria-rowcount', String(total + 1));
    let range;
    if (total <= WINDOW_AT) range = { start: 0, end: total, padTop: 0, padBottom: 0 };
    else range = windowRange({ scrollTop: scroller.scrollTop, viewportHeight: scroller.clientHeight || 640, rowHeight, total, overscan: 10 });
    if (!force && range.start === lastRange.start && range.end === lastRange.end) return;
    lastRange = { start: range.start, end: range.end };
    const focusKey = currentFocusKey();
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
    if (range.padTop > 0) tbody.appendChild(spacer(range.padTop));
    for (let i = range.start; i < range.end; i += 1) tbody.appendChild(buildRow(rows[i], i));
    if (range.padBottom > 0) tbody.appendChild(spacer(range.padBottom));
    if (focusKey) {
      const again = tbody.querySelector('[data-focus-key="' + CSS.escape(focusKey) + '"]');
      if (again) again.focus({ preventScroll: true });
    }
  }

  let raf = 0;
  scroller.addEventListener('scroll', () => {
    if (rows.length <= WINDOW_AT || raf) return;
    raf = globalThis.requestAnimationFrame(() => {
      raf = 0;
      renderRows();
    });
  });

  function indexOfRow(id) {
    return order.indexOf(id);
  }

  function scrollToIndex(i) {
    if (rows.length <= WINDOW_AT) return;
    const top = i * rowHeight;
    const view = scroller.clientHeight || 640;
    const head = thead.offsetHeight || 0;
    if (top < scroller.scrollTop) scroller.scrollTop = top;
    else if (top + rowHeight > scroller.scrollTop + view - head) scroller.scrollTop = top + rowHeight - view + head;
    renderRows();
  }

  return {
    el,
    selection,
    get rows() {
      return rows;
    },
    /** Replaces the displayed rows (already filtered and sorted by the caller). */
    update({ rows: next, sort: nextSortState }) {
      rows = next;
      if (nextSortState !== undefined) sort = nextSortState;
      order = rows.map(rowId);
      selection.retain(order);
      refreshHeaders();
      lastRange = { start: -1, end: -1 };
      renderRows(true);
      updateSelectAll();
    },
    setSort(s) {
      sort = s;
      refreshHeaders();
    },
    /** Moves keyboard focus by [delta] rows from the focused row (or to the first row). */
    moveFocus(delta) {
      if (rows.length === 0) return;
      const a = globalThis.document.activeElement;
      const tr = a && a.closest ? a.closest('tr[data-row-id]') : null;
      let i = tr ? indexOfRow(tr.getAttribute('data-row-id')) + delta : delta > 0 ? 0 : rows.length - 1;
      i = Math.max(0, Math.min(rows.length - 1, i));
      scrollToIndex(i);
      const id = order[i];
      renderRows(true);
      const btn = tbody.querySelector('[data-focus-key="' + CSS.escape(id + ':open') + '"]');
      if (btn) btn.focus();
    },
    /** Toggles selection of the focused row (shortcut x). */
    toggleFocused() {
      const a = globalThis.document.activeElement;
      const tr = a && a.closest ? a.closest('tr[data-row-id]') : null;
      if (!tr || !selectable) return;
      const cb = tr.querySelector('input.check');
      if (cb) cb.click();
    },
    focusedRow() {
      const a = globalThis.document.activeElement;
      const tr = a && a.closest ? a.closest('tr[data-row-id]') : null;
      return tr ? rows.find((r) => rowId(r) === tr.getAttribute('data-row-id')) || null : null;
    },
    scrollToTop() {
      scroller.scrollTop = 0;
    },
  };
}
