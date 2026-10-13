// Audit log: filter by who, what and when (done by the hub, so it works on a log of any size), follow it live, verify the hash chain, and
// export what you see. Entries are shown newest first; "Load older" pages backwards.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { formatNumber, formatDateTime, shortId, groupText } from '../format.js';
import { pageHeader, button, badge, banner, emptyState, textInput, selectInput, field, withBusy, timeEl, dl, mono, copyButton, skeleton, errorState, switchInput } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDrawer } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { setActiveTable } from '../shortcuts.js';
import { downloadText } from './setup.js';
import { icon } from '../icons.js';

const PAGE = 200;
const TAIL_MS = 5000;
const RANGES = { '1h': 3_600_000, '24h': 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000 };
const PREFIXES = ['admin.', 'admin.login', 'device.', 'invite.', 'group.', 'department.', 'join.', 'delegation.', 'settings.', 'audit.', 'serial.', 'stepup.'];

function toLocalInput(ms) {
  const d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000);
  return d.toISOString().slice(0, 16);
}

export function mountAudit(container, ctx) {
  let entries = [];
  let total = 0;
  let stopped = false;
  let tailTimer = 0;
  const f = { actor: ctx.query.actor || '', action: ctx.query.action || '', text: ctx.query.text || '', range: ctx.query.range || '', from: '', to: '' };

  const actor = textInput({ value: f.actor, maxlength: 128, placeholder: t('audit.filter.actor.placeholder') });
  const action = textInput({ value: f.action, maxlength: 128, placeholder: t('audit.filter.action.placeholder') });
  action.setAttribute('list', 'audit-prefixes');
  const datalist = h('datalist', { id: 'audit-prefixes' }, PREFIXES.map((p) => h('option', { value: p })));
  const text = textInput({ type: 'search', value: f.text, maxlength: 128, placeholder: t('audit.filter.text.placeholder') });
  text.setAttribute('data-search', '');
  const range = selectInput(
    [{ value: '', label: t('audit.range.all') }, { value: '1h', label: t('audit.range.1h') }, { value: '24h', label: t('audit.range.24h') }, { value: '7d', label: t('audit.range.7d') }, { value: '30d', label: t('audit.range.30d') }, { value: 'custom', label: t('audit.range.custom') }],
    f.range,
    { ariaLabel: t('audit.filter.range') },
  );
  const from = textInput({ type: 'datetime-local' });
  const to = textInput({ type: 'datetime-local' });
  const customWrap = h('div', { class: 'inline-fields', hidden: range.value !== 'custom' }, field({ label: t('audit.filter.from'), control: from }).el, field({ label: t('audit.filter.to'), control: to }).el);
  const af = field({ label: t('audit.filter.actor'), control: actor });
  const ac = field({ label: t('audit.filter.action'), control: action });
  const tf = field({ label: t('audit.filter.text'), control: text });
  const rf = field({ label: t('audit.filter.range'), control: range });
  const filters = h('div', { class: 'filter-grid', role: 'search', 'aria-label': t('audit.filters') }, af.el, ac.el, tf.el, rf.el, customWrap, datalist);

  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
  const verifyHost = h('div', { 'aria-live': 'polite' });
  const tableHost = h('div', { class: 'table-host' });
  const more = button({ label: t('audit.more'), icon: 'chevronDown', on: { click: () => loadMore() } });
  const moreWrap = h('div', { class: 'form-actions center', hidden: true }, more);

  const live = switchInput({ label: t('audit.live'), checked: false, on: { change: (ev) => setTail(ev.target.checked) } });
  const verifyBtn = button({ label: t('audit.verify'), icon: 'check', on: { click: () => verify() } });
  const exportBtn = button({ label: t('audit.export'), icon: 'download', on: { click: () => exportCsv() } });
  const refreshBtn = button({ label: t('common.refresh'), icon: 'refresh', on: { click: () => reload() } });

  const table = createTable({
    ariaLabel: t('audit.table'),
    rowId: (e) => String(e.index),
    rowLabel: (e) => e.action,
    onOpen: (e) => showEntry(e),
    columns: [
      { key: 'time', label: t('audit.col.time'), render: (e) => h('time', { datetime: new Date(e.timestampMs).toISOString(), title: formatDateTime(e.timestampMs), text: formatDateTime(e.timestampMs) }) },
      { key: 'actor', label: t('audit.col.actor'), render: (e) => e.actor },
      { key: 'action', label: t('audit.col.action'), primary: true, render: (e) => h('span', { class: 'mono', text: e.action }) },
      { key: 'details', label: t('audit.col.details'), className: 'hide-sm cell-ellipsis', render: (e) => h('span', { class: 'ellipsis', title: e.details, text: e.details }) },
      { key: 'signed', label: t('audit.col.signed'), className: 'hide-md', render: (e) => (e.signed ? badge(t('audit.signed'), 'ok') : h('span', { class: 'muted', text: '–' })) },
    ],
  });

  mount(container, pageHeader({ title: t('audit.title'), subtitle: t('audit.sub'), actions: [refreshBtn, verifyBtn, exportBtn] }), verifyHost, filters, h('div', { class: 'toolbar-row' }, resultLine, live), tableHost, moreWrap);
  setActiveTable(table);

  function query(extra) {
    const q = { actor: f.actor.trim(), action: f.action.trim(), text: f.text.trim(), ...extra };
    if (f.range && f.range !== 'custom') q.from = Date.now() - RANGES[f.range];
    if (f.range === 'custom') {
      if (f.from) q.from = new Date(f.from).getTime();
      if (f.to) q.to = new Date(f.to).getTime();
    }
    return q;
  }

  function draw() {
    resultLine.textContent = total ? t('audit.result', { shown: entries.length, total: formatNumber(total) }) : '';
    mount(tableHost);
    if (!entries.length) {
      tableHost.appendChild(emptyState({ title: t('audit.empty.title'), text: t('audit.empty.text'), glyph: 'audit' }));
    } else {
      table.update({ rows: entries });
      tableHost.appendChild(table.el);
    }
    moreWrap.hidden = entries.length >= total;
    ctx.replaceQuery({ actor: f.actor, action: f.action, text: f.text, range: f.range });
  }

  async function reload() {
    mount(tableHost, skeleton('table', 8));
    try {
      const page = await api.get('/audit', { query: query({ order: 'desc', limit: PAGE, offset: 0 }) });
      if (stopped) return;
      entries = page.entries;
      total = page.total;
      draw();
    } catch (e) {
      if (!stopped) mount(tableHost, errorState(e, reload));
    }
  }

  async function loadMore() {
    await withBusy(more, async () => {
      try {
        const page = await api.get('/audit', { query: query({ order: 'desc', limit: PAGE, offset: entries.length }) });
        entries = entries.concat(page.entries);
        total = page.total;
        draw();
      } catch (e) {
        toastError(e);
      }
    });
  }

  async function tail() {
    if (stopped || globalThis.document.hidden) return;
    try {
      const page = await api.get('/audit', { query: query({ order: 'desc', limit: 50, offset: 0 }) });
      const newest = entries.length ? entries[0].index : -1;
      const fresh = page.entries.filter((e) => e.index > newest);
      if (fresh.length) {
        entries = fresh.concat(entries).slice(0, 5000);
        total = page.total;
        draw();
      }
    } catch (e) {
      /* a failed poll is silent; the next one retries */
    }
  }

  function setTail(on) {
    clearInterval(tailTimer);
    if (on) {
      tailTimer = setInterval(tail, TAIL_MS);
      toast(t('audit.live.on'), { kind: 'info', timeoutMs: 2500 });
    }
  }

  async function verify() {
    await withBusy(verifyBtn, async () => {
      mount(verifyHost, skeleton('lines', 2));
      try {
        const [v, head] = await Promise.all([api.get('/audit/verify'), api.get('/audit/head')]);
        const anchor = head.hash ? h('div', { class: 'anchor' }, h('span', { class: 'muted', text: t('audit.verify.head') }), mono(groupText(head.hash, 8), 'wrap'), copyButton({ text: head.hash, label: t('audit.verify.copyHead') })) : null;
        mount(
          verifyHost,
          v.ok
            ? banner({ tone: 'ok', title: t('audit.verify.ok.title'), text: t('audit.verify.ok', { n: formatNumber(v.entries), anchors: v.anchors }), actions: null })
            : banner({ tone: 'bad', title: t('audit.verify.bad.title'), text: t('audit.verify.bad', { index: v.firstBadIndex ?? '?', reason: v.reason || t('audit.verify.noReason') }) }),
          anchor,
        );
      } catch (e) {
        mount(verifyHost, errorState(e, verify));
      }
    });
  }

  async function exportCsv() {
    await withBusy(exportBtn, async () => {
      try {
        const q = query({});
        const csv = await api.get('/audit/export', { query: { actor: q.actor, action: q.action, text: q.text, from: q.from, to: q.to }, raw: true });
        downloadText('flash-hub-audit.csv', csv);
        toast(t('audit.exported'), { kind: 'success' });
      } catch (e) {
        toastError(e);
      }
    });
  }

  function showEntry(e) {
    const dlg = openDrawer({
      title: t('audit.entry', { n: e.index }),
      body: [
        dl([
          [t('audit.col.time'), h('span', {}, formatDateTime(e.timestampMs), ' ', h('span', { class: 'muted' }, '(', timeEl(e.timestampMs), ')'))],
          [t('audit.col.actor'), e.actor],
          [t('audit.col.action'), mono(e.action)],
          [t('audit.col.signed'), e.signed ? badge(t('audit.signed'), 'ok') : t('audit.unsigned')],
        ]),
        h('h3', { class: 'subhead', text: t('audit.col.details') }),
        h('pre', { class: 'code wrap', tabindex: '0' }, h('code', { text: e.details || t('audit.noDetails') })),
        h('details', { class: 'tech' }, h('summary', { text: t('devices.tech') }), dl([[t('audit.hash'), mono(groupText(e.hash, 8), 'wrap')]])),
      ],
    });
    return dlg;
  }

  // ------------------------------------------------------------------ events
  let timer = 0;
  const changed = () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      f.actor = actor.value;
      f.action = action.value;
      f.text = text.value;
      f.range = range.value;
      f.from = from.value;
      f.to = to.value;
      customWrap.hidden = f.range !== 'custom';
      reload();
    }, 300);
  };
  for (const el of [actor, action, text, from, to]) el.addEventListener('input', changed);
  range.addEventListener('change', () => {
    if (range.value === 'custom' && !from.value) {
      from.value = toLocalInput(Date.now() - 86_400_000);
      to.value = toLocalInput(Date.now());
    }
    changed();
  });

  reload();
  return {
    destroy() {
      stopped = true;
      clearInterval(tailTimer);
      clearTimeout(timer);
      setActiveTable(null);
    },
    onRoute(next) {
      ctx = { ...ctx, ...next };
    },
  };
}
