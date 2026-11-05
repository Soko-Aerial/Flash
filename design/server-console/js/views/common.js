import { h } from '../dom.js';

export function pageHead({ title, lede = null, actions = [], freshness = null }) {
  return h('div', { class: 'page-head' },
    h('div', { class: 'page-head-text' },
      h('h1', { class: 'page-title', text: title }),
      lede ? h('p', { class: 'page-lede' }, lede) : null,
    ),
    h('div', { class: 'page-actions' }, actions, freshness ? h('span', { class: 'freshness' }, h('span', { class: 'dot' }), freshness) : null),
  );
}

export function dotLede({ tone = 'ok', lead = null, rest = [] }) {
  const nodes = [];
  nodes.push(h('span', { class: 'inline nowrap' }, h('span', { class: `dot dot-${tone}` }), typeof lead === 'string' ? h('span', { text: lead }) : lead));
  for (const part of rest) {
    nodes.push(h('span', { class: 'faint', 'aria-hidden': 'true', text: '·' }));
    nodes.push(part);
  }
  return nodes;
}

export function tabBar({ items, active, onSelect, label = 'Views' }) {
  return h('div', { class: 'tabs', role: 'group', 'aria-label': label },
    items.map((item) => h('button', {
      class: 'tab',
      type: 'button',
      'data-tab': item.id,
      'aria-pressed': item.id === active ? 'true' : 'false',
      'aria-label': item.count !== undefined ? `${item.label}, ${item.count}` : item.label,
      on: { click: () => onSelect(item.id) },
    }, h('span', { text: item.label }), item.count !== undefined ? h('span', { class: 'tab-count', text: String(item.count) }) : null)),
  );
}

export function dataTable({ columns, rows, label, onRow, rowClass = () => '', rowLabel = null, footer = null, empty = null }) {
  const head = h('tr', {}, columns.map((col) => h('th', {
    scope: 'col',
    class: [col.num ? 'num' : '', col.className || ''].filter(Boolean).join(' '),
    text: col.label,
  })));

  const body = h('tbody', {}, rows.map((row) => {
    const tr = h('tr', {
      class: ['row', rowClass(row)].filter(Boolean).join(' '),
      tabindex: onRow ? '0' : null,
      'aria-label': onRow && rowLabel ? rowLabel(row) : null,
      on: onRow ? {
        click: (event) => {
          if (event.target.closest('button, a')) return;
          onRow(row);
        },
        keydown: (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onRow(row);
          }
        },
      } : {},
    }, columns.map((col) => h('td', {
      class: [col.num ? 'num' : '', col.className || ''].filter(Boolean).join(' '),
    }, col.render ? col.render(row) : row[col.key])));
    return tr;
  }));

  const table = h('table', { class: 'table', 'aria-label': label },
    h('thead', {}, head),
    body,
  );

  return h('div', { class: 'table-wrap' },
    h('div', { class: 'table-scroll' }, rows.length ? table : (empty || h('div', { class: 'table-empty muted', text: 'Nothing to show.' }))),
    footer,
  );
}

export function personCell(name, sub, initials) {
  return h('span', { class: 'identity' },
    h('span', { class: 'avatar', text: initials }),
    h('span', { class: 'cell-stack' },
      h('span', { class: 'cell-title', text: name }),
      sub ? h('span', { class: 'cell-sub', text: sub }) : null,
    ),
  );
}

export function countLine(parts) {
  return h('div', { class: 'device-summary' },
    parts.map((part) => h('span', {}, typeof part === 'string' ? part : h('span', {}, h('strong', { text: String(part.value) }), ` ${part.label}`))),
  );
}

/** One tab stop for a table, arrow/j-k navigation for the rest (not 138 stops). */
export function rovingRows(host) {
  const rows = [...host.querySelectorAll('tr.row')];
  rows.forEach((row, index) => { row.tabIndex = index === 0 ? 0 : -1; });
}

export function srHeading(text) {
  return h('h2', { class: 'sr-only', text });
}
