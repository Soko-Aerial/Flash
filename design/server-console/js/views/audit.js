import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { state, markAuditVerified } from '../store.js';
import { n, clock, dateTime } from '../format.js';
import { pageHead, rovingRows, srHeading } from './common.js';
import { openDrawer, toast } from '../ui.js';

function actionIcon(action) {
  if (/device/.test(action)) return 'devices';
  if (/join|invite/.test(action)) return 'requests';
  if (/delegation|stepup|admin/.test(action)) return 'key';
  if (/announcement/.test(action)) return 'announcements';
  if (/settings|directory/.test(action)) return 'settings';
  return 'audit';
}

function detailDrawer(entry) {
  const drawer = openDrawer({
    title: entry.label,
    body: [
      h('dl', { class: 'definition-list' },
        h('dt', { text: 'Action' }),
        h('dd', {}, h('span', { class: 'mono', text: entry.action })),
        h('dt', { text: 'Actor' }),
        h('dd', { text: entry.actor }),
        h('dt', { text: 'Detail' }),
        h('dd', { text: entry.detail }),
        h('dt', { text: 'Recorded' }),
        h('dd', { text: dateTime(entry.at) }),
        h('dt', { text: 'Entry' }),
        h('dd', {}, h('span', { class: 'mono', text: `#${n(entry.index)}` })),
      ),
      h('div', { class: 'stack tight' },
        h('h3', { text: 'Chain' }),
        h('p', { class: 'hint', text: 'This entry is part of the append-only audit chain. Its position and the hash of the entry before it are checked when the chain is verified.' }),
        h('div', { class: 'code-block', text: `entry ${entry.index}\nprevious ${entry.index - 1}\nrecorded ${entry.at.toISOString()}` }),
      ),
    ],
    footer: [h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => drawer.close() } }, 'Close')],
  });
  return drawer;
}

export function mountAudit(container, ctx) {
  let text = '';
  let actionFilter = '';
  let range = 'today';

  const tableHost = h('div');
  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });

  const verifyButton = h('button', { class: 'btn btn-primary', type: 'button' }, icon('check', { size: 15 }), h('span', { class: 'btn-label', text: 'Verify chain' }));
  const stripIcon = h('span', { class: 'audit-flag', style: { color: 'var(--ok)' } }, icon('shield', { size: 20 }));
  const stripTitle = h('div', { class: 'audit-strip-title' });
  const stripText = h('div', { class: 'audit-strip-text' });

  function refreshStrip() {
    const at = state.auditVerifiedAt;
    stripTitle.textContent = 'Chain intact';
    stripText.textContent = `${n(state.auditEntries)} entries · last verified ${dateTime(at)} · no gaps or rewrites found`;
  }

  verifyButton.addEventListener('click', () => {
    if (verifyButton.disabled) return;
    verifyButton.disabled = true;
    verifyButton.classList.add('is-busy');
    const original = verifyButton.querySelector('.btn-label');
    if (original) original.textContent = 'Checking…';
    setTimeout(() => {
      markAuditVerified();
      container.replaceChildren();
      mountAudit(container, ctx);
    }, 850);
  });

  refreshStrip();

  const search = h('input', {
    class: 'input input-search', type: 'search', placeholder: 'Actor, action or detail', 'aria-label': 'Search the audit log',
    on: { input: (event) => { text = event.target.value; render(); } },
  });
  const actionSelect = h('select', { class: 'select select-sm', 'aria-label': 'Action', on: { change: (event) => { actionFilter = event.target.value; render(); } } },
    h('option', { value: '', text: 'All actions' }),
    [...new Set(data.audit.entries.map((e) => e.label))].map((label) => h('option', { value: label, text: label })),
  );
  const rangeSelect = h('select', { class: 'select select-sm audit-filter-date', 'aria-label': 'Time range', on: { change: (event) => { range = event.target.value; render(); } } },
    h('option', { value: 'today', text: 'Last 24 hours' }),
    h('option', { value: 'week', text: 'Last 7 days' }),
    h('option', { value: 'all', text: 'All time' }),
  );

  const head = pageHead({
    title: 'Audit log',
    lede: `${n(state.auditEntries)} entries. Append-only and signed by the hub; every administrative action is recorded.`,
    actions: [
      h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => exportCsv() } }, icon('download', { size: 15 }), 'Export CSV'),
      verifyButton,
    ],
  });

  const strip = h('div', { class: 'audit-strip', role: 'status' },
    stripIcon,
    h('div', { class: 'audit-strip-main' }, stripTitle, stripText),
  );

  const toolbar = h('div', { class: 'toolbar', role: 'search' },
    h('div', { class: 'search-wrap' }, icon('search', { size: 16, className: 'search-glyph' }), search),
    actionSelect,
    rangeSelect,
    h('div', { class: 'toolbar-end' }, h('span', { text: 'Newest first' })),
  );

  function filtered() {
    const needle = text.trim().toLowerCase();
    const limit = range === 'today' ? 1440 : range === 'week' ? 10080 : Infinity;
    return data.audit.entries.filter((entry) => {
      if (entry.minutesAgo > limit) return false;
      if (actionFilter && entry.label !== actionFilter) return false;
      if (!needle) return true;
      return [entry.label, entry.action, entry.actor, entry.detail].some((v) => String(v).toLowerCase().includes(needle));
    });
  }

  function render() {
    const rows = filtered();
    resultLine.textContent = `Showing ${n(rows.length)} of ${n(state.auditEntries)} entries`;
    mount(tableHost);
    tableHost.append(h('div', { class: 'table-wrap' },
      h('div', { class: 'table-scroll' },
        h('table', { class: 'table audit-table', 'aria-label': 'Audit log' },
          h('thead', {}, h('tr', {},
            h('th', { scope: 'col', text: 'Time' }),
            h('th', { scope: 'col', text: 'Action' }),
            h('th', { scope: 'col', class: 'hide-sm', text: 'Actor' }),
            h('th', { scope: 'col', class: 'hide-sm', text: 'Detail' }),
            h('th', { scope: 'col', class: 'num', text: 'Entry' }),
          )),
          h('tbody', {}, rows.map((entry) => h('tr', {
            class: 'row', tabindex: '0', 'aria-label': `Open ${entry.label}`,
            on: { click: () => detailDrawer(entry), keydown: (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); detailDrawer(entry); } } },
          },
            h('td', {}, h('span', { class: 'mono', text: clock(entry.at) })),
            h('td', {}, h('span', { class: 'audit-action' }, icon(actionIcon(entry.action), { size: 15 }), entry.label)),
            h('td', { class: 'hide-sm' }, entry.actor),
            h('td', { class: 'hide-sm detail-cell' }, h('span', { class: 'ellipsis muted', text: entry.detail })),
            h('td', { class: 'num' }, h('span', { class: 'mono muted', text: `#${n(entry.index)}` })),
          )))),
      ),
      h('div', { class: 'table-foot' },
        h('span', { text: `Showing ${n(rows.length)} of ${n(state.auditEntries)} · newest first` }),
        h('span', { text: 'Export keeps the same order' }),
      ),
    ));
    rovingRows(tableHost);
  }

  function exportCsv() {
    const rows = filtered();
    const header = ['Time', 'Action', 'Actor', 'Detail', 'Entry'];
    const lines = rows.map((e) => [e.at.toISOString(), e.action, e.actor, e.detail, e.index].map((v) => `"${String(v).replaceAll('"', '""')}"`).join(','));
    const blob = new Blob([[header.join(','), ...lines].join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: 'flash-audit.csv' });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('Audit log exported as CSV', 'ok');
  }

  container.append(head, srHeading('Audit entries'), strip, toolbar, resultLine, tableHost);
  render();
}
