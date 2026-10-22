// Devices: every enrolled device of the company. All pages are loaded once (500 per request) and then searched, filtered and sorted in
// the browser, so typing in the search box is instant even with thousands of rows; the table renders only the rows in view.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api, guarded, events } from '../app.js';
import { formatNumber, formatDateTime, relativeTime, shortId, groupText } from '../format.js';
import { toCsv } from '../csv.js';
import { filterRows, sortRows } from '../tablemodel.js';
import { renderFingerprint } from '../identicon.js';
import { bytesToBase64, base64ToBytes, toHex } from '../codec.js';
import { pageHeader, button, badge, banner, emptyState, textInput, selectInput, field, withBusy, timeEl, dl, mono, copyButton, skeleton, errorState } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDrawer, openDialog } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { setActiveTable } from '../shortcuts.js';
import { downloadText } from './setup.js';
import { icon } from '../icons.js';

const PAGE = 500;
const MAX_ROWS = 20000;
const DAY = 86_400_000;

export function statusBadge(status) {
  if (status === 'ACTIVE') return badge(t('status.active'), 'ok');
  if (status === 'REVOKED') return badge(t('status.revoked'), 'bad');
  return badge(t('status.unenrolled'), 'warn');
}

async function sha256Hex(b64) {
  try {
    const d = await globalThis.crypto.subtle.digest('SHA-256', base64ToBytes(b64));
    return toHex(new Uint8Array(d)).toUpperCase();
  } catch (e) {
    return '';
  }
}

export function mountDevices(container, ctx) {
  let all = [];
  let departments = [];
  let loaded = false;
  let stopped = false;
  let drawer = null;
  const state = { text: ctx.query.q || '', status: ctx.query.status || '', department: ctx.query.department || '', role: ctx.query.role || '', online: ctx.query.online || '', sort: { key: ctx.query.sort || 'name', dir: ctx.query.dir === 'desc' ? 'desc' : 'asc' } };

  // ------------------------------------------------------------------ toolbar
  const search = textInput({ type: 'search', placeholder: t('devices.search.placeholder'), value: state.text, className: 'input-search' });
  search.setAttribute('data-search', '');
  search.setAttribute('aria-label', t('devices.search'));
  const statusSel = selectInput(
    [{ value: '', label: t('devices.filter.status.all') }, { value: 'ACTIVE', label: t('status.active') }, { value: 'REVOKED', label: t('status.revoked') }, { value: 'UNENROLLED', label: t('status.unenrolled') }],
    state.status,
    { ariaLabel: t('devices.filter.status') },
  );
  const deptSel = selectInput([{ value: '', label: t('devices.filter.dept.all') }], state.department, { ariaLabel: t('devices.filter.dept') });
  const roleSel = selectInput(
    [{ value: '', label: t('devices.filter.role.all') }, { value: 'member', label: t('role.member') }, { value: 'dept_admin', label: t('role.dept_admin') }, { value: 'admin', label: t('role.admin') }],
    state.role,
    { ariaLabel: t('devices.filter.role') },
  );
  const onlineSel = selectInput([{ value: '', label: t('devices.filter.online.all') }, { value: '1', label: t('devices.filter.online.on') }], state.online, { ariaLabel: t('devices.filter.online') });
  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
  const exportBtn = button({ label: t('devices.export'), icon: 'download', on: { click: () => exportCsv(false) } });
  const refreshBtn = button({ label: t('common.refresh'), icon: 'refresh', on: { click: () => load() } });
  const toolbar = h('div', { class: 'toolbar', role: 'search', 'aria-label': t('devices.toolbar') }, h('div', { class: 'toolbar-search' }, icon('search', { size: 18, className: 'search-glyph' }), search), statusSel, deptSel, roleSel, onlineSel);

  // ------------------------------------------------------------------ bulk bar
  const bulkCount = h('span', { class: 'bulk-count num' });
  const bulkRevoke = button({ label: t('devices.bulk.revoke'), icon: 'lock', variant: 'danger', size: 'sm', on: { click: () => bulkRevokeFlow() } });
  const bulkMove = button({ label: t('devices.bulk.move'), icon: 'org', size: 'sm', on: { click: () => bulkMoveFlow() } });
  const bulkExport = button({ label: t('devices.bulk.export'), icon: 'download', size: 'sm', on: { click: () => exportCsv(true) } });
  const bulkClear = button({ label: t('devices.bulk.clear'), variant: 'ghost', size: 'sm', on: { click: () => { table.selection.clear(); table.update({ rows: view }); onSelection(); } } });
  const bulkBar = h('div', { class: 'bulk-bar', role: 'region', 'aria-label': t('devices.bulk.region'), hidden: true }, bulkCount, bulkRevoke, bulkMove, bulkExport, bulkClear);

  // ------------------------------------------------------------------ table
  const columns = [
    { key: 'name', label: t('devices.col.name'), sortable: true, primary: true, render: (d) => h('span', { class: 'cell-stack' }, h('span', { class: 'cell-title', text: d.name || t('devices.unnamed') }), h('span', { class: 'cell-sub mono', text: shortId(d.deviceId, 10, 4) })) },
    { key: 'status', label: t('devices.col.status'), sortable: true, render: (d) => statusBadge(d.status) },
    { key: 'department', label: t('devices.col.department'), sortable: true, className: 'hide-sm', render: (d) => d.department || h('span', { class: 'muted', text: t('devices.noDepartment') }) },
    { key: 'role', label: t('devices.col.role'), sortable: true, className: 'hide-md', render: (d) => t('role.' + d.role) },
    { key: 'lastSeen', label: t('devices.col.lastSeen'), sortable: true, className: 'hide-sm', render: (d) => (d.online ? h('span', { class: 'online' }, h('span', { class: 'dot', 'aria-hidden': 'true' }), t('devices.online')) : d.lastSeenAt ? timeEl(d.lastSeenAt) : h('span', { class: 'muted', text: t('devices.never') })) },
    { key: 'certExpiry', label: t('devices.col.cert'), sortable: true, className: 'hide-md', render: (d) => certCell(d) },
  ];
  const table = createTable({
    columns,
    rowId: (d) => d.deviceId,
    ariaLabel: t('devices.table'),
    selectable: true,
    rowLabel: (d) => d.name || shortId(d.deviceId),
    rowClass: (d) => (d.status === 'REVOKED' ? 'row-muted' : ''),
    onOpen: (d) => ctx.go(['devices', d.deviceId]),
    onSort: (s) => {
      state.sort = s;
      apply();
    },
    onSelectionChange: () => onSelection(),
  });
  table.setSort(state.sort);

  function certCell(d) {
    if (!d.certExpiry) return h('span', { class: 'muted', text: '–' });
    const left = d.certExpiry - Date.now();
    if (left < 0) return badge(t('devices.cert.expired'), 'bad', { title: formatDateTime(d.certExpiry) });
    if (left < 14 * DAY) return badge(t('devices.cert.soon', { n: Math.max(1, Math.ceil(left / DAY)) }), 'warn', { title: formatDateTime(d.certExpiry) });
    return timeEl(d.certExpiry);
  }

  const tableHost = h('div', { class: 'table-host' });
  mount(container, pageHeader({ title: t('devices.title'), subtitle: t('devices.sub'), actions: [refreshBtn, exportBtn] }), toolbar, resultLine, bulkBar, tableHost);
  mount(tableHost, skeleton('table', 8));

  // ------------------------------------------------------------------ data
  const getters = {
    name: (d) => (d.name || '').toLowerCase(),
    status: (d) => d.status,
    department: (d) => d.department || null,
    role: (d) => d.role,
    lastSeen: (d) => (d.online ? Number.MAX_SAFE_INTEGER : d.lastSeenAt || null),
    certExpiry: (d) => d.certExpiry || null,
  };
  let view = [];

  function apply() {
    const predicates = [];
    if (state.status) predicates.push((d) => d.status === state.status);
    if (state.department) predicates.push((d) => (state.department === '__none' ? !d.department : d.department === state.department));
    if (state.role) predicates.push((d) => d.role === state.role);
    if (state.online) predicates.push((d) => d.online);
    const filtered = filterRows(all, { text: state.text, fields: ['name', 'deviceId', 'department', 'role', 'status', (d) => String(d.serial)], predicates });
    const dir = state.sort && state.sort.dir;
    view = sortRows(filtered, state.sort, getters);
    if (state.sort.key === 'lastSeen' || state.sort.key === 'certExpiry') view = dir === 'desc' ? view : view;
    table.update({ rows: view, sort: state.sort });
    ctx.replaceQuery({ q: state.text, status: state.status, department: state.department, role: state.role, online: state.online, sort: state.sort.key === 'name' && state.sort.dir === 'asc' ? '' : state.sort.key, dir: state.sort.dir === 'desc' ? 'desc' : '' });
    resultLine.textContent = all.length === 0 ? '' : view.length === all.length ? t('devices.result.all', { n: all.length }) : t('devices.result.filtered', { shown: view.length, total: all.length });
    mount(tableHost);
    if (all.length === 0) {
      tableHost.appendChild(emptyState({ title: t('devices.empty.title'), text: t('devices.empty.text'), glyph: 'devices', action: h('a', { class: 'btn btn-primary', href: '#/invites' }, t('devices.empty.action')) }));
    } else if (view.length === 0) {
      tableHost.appendChild(emptyState({ title: t('devices.nomatch.title'), text: t('devices.nomatch.text'), glyph: 'search', action: button({ label: t('devices.nomatch.clear'), on: { click: clearFilters } }) }));
    } else tableHost.appendChild(table.el);
    onSelection();
  }

  function clearFilters() {
    state.text = '';
    state.status = state.department = state.role = state.online = '';
    search.value = '';
    statusSel.value = deptSel.value = roleSel.value = onlineSel.value = '';
    apply();
    search.focus();
  }

  function fillDepartments() {
    const names = new Set(all.map((d) => d.department).filter(Boolean));
    const cur = deptSel.value;
    mount(deptSel, h('option', { value: '', text: t('devices.filter.dept.all') }), h('option', { value: '__none', text: t('devices.noDepartment') }), Array.from(names).sort().map((n) => h('option', { value: n, text: n })));
    deptSel.value = Array.from(deptSel.options).some((o) => o.value === cur) ? cur : '';
    state.department = deptSel.value;
  }

  async function load() {
    mount(tableHost, skeleton('table', 8));
    const progress = h('p', { class: 'muted', role: 'status', 'aria-live': 'polite' });
    tableHost.appendChild(progress);
    try {
      const rows = [];
      for (let offset = 0; offset < MAX_ROWS; offset += PAGE) {
        const page = await api.get('/devices', { query: { limit: PAGE, offset } });
        rows.push(...page);
        progress.textContent = t('devices.loading', { n: rows.length });
        if (stopped) return;
        if (page.length < PAGE) break;
      }
      all = rows;
      loaded = true;
      departments = await api.get('/departments').catch(() => departments);
      fillDepartments();
      apply();
      if (rows.length >= MAX_ROWS) toast(t('devices.truncated', { n: MAX_ROWS }), { kind: 'warning' });
      if (ctx.segments[1]) openDevice(ctx.segments[1]);
    } catch (e) {
      if (stopped) return;
      mount(tableHost, errorState(e, load));
    }
  }

  function onSelection() {
    const n = table.selection.size;
    bulkBar.hidden = n === 0;
    bulkCount.textContent = t('devices.bulk.count', { n });
    if (n) resultLine.textContent = t('devices.result.selected', { shown: view.length, selected: n });
  }

  // ------------------------------------------------------------------ events
  let timer = 0;
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => {
      state.text = search.value;
      apply();
    }, 120);
  });
  for (const [sel, key] of [[statusSel, 'status'], [deptSel, 'department'], [roleSel, 'role'], [onlineSel, 'online']]) {
    sel.addEventListener('change', () => {
      state[key] = sel.value;
      apply();
    });
  }
  container.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && !ev.ctrlKey && !ev.metaKey && ev.target.closest && ev.target.closest('tr[data-row-id]') && ev.target.tagName !== 'BUTTON' && ev.target.tagName !== 'INPUT') {
      const row = table.focusedRow();
      if (row) ctx.go(['devices', row.deviceId]);
    }
  });
  setActiveTable(table);

  // ------------------------------------------------------------------ export
  function exportCsv(selectedOnly) {
    const rows = (selectedOnly ? view.filter((d) => table.selection.has(d.deviceId)) : view).map((d) => [
      d.deviceId, d.name, d.department || '', d.role, d.status, d.serial, d.enrolledAt ? new Date(d.enrolledAt).toISOString() : '', d.certExpiry ? new Date(d.certExpiry).toISOString() : '', d.lastSeenAt ? new Date(d.lastSeenAt).toISOString() : '', d.online ? 'yes' : 'no',
    ]);
    const csv = toCsv(['deviceId', 'name', 'department', 'role', 'status', 'serial', 'enrolledAt', 'certExpiry', 'lastSeenAt', 'online'], rows);
    downloadText('flash-hub-devices.csv', csv);
    toast(t('devices.exported', { n: rows.length }), { kind: 'success' });
  }

  // ------------------------------------------------------------------ revoke
  /** Asks for confirmation; resolves to `{reason}` or null. */
  function askRevoke(devices) {
    return new Promise((resolve) => {
      const single = devices.length === 1;
      const phrase = single ? devices[0].name || 'revoke' : 'revoke';
      const reason = textInput({ maxlength: 200, value: '', placeholder: t('devices.revoke.reason.placeholder') });
      const rf = field({ label: t('devices.revoke.reason'), control: reason, hint: t('devices.revoke.reason.hint') });
      const typed = textInput({ autocomplete: 'off' });
      typed.setAttribute('data-autofocus', '');
      const tf = field({ label: t('confirm.type', { text: phrase }), control: typed });
      const ok = button({ label: single ? t('devices.revoke.confirm') : t('devices.revoke.confirmMany', { n: devices.length }), variant: 'danger', disabled: true });
      typed.addEventListener('input', () => {
        ok.disabled = typed.value.trim() !== phrase;
      });
      const list = single
        ? null
        : h('ul', { class: 'compact-list' }, devices.slice(0, 8).map((d) => h('li', { text: d.name || shortId(d.deviceId) })), devices.length > 8 ? h('li', { class: 'muted', text: t('devices.revoke.more', { n: devices.length - 8 }) }) : null);
      const dlg = openDialog({
        title: single ? t('devices.revoke.title', { name: devices[0].name || shortId(devices[0].deviceId) }) : t('devices.revoke.titleMany', { n: devices.length }),
        size: 'sm',
        body: [
          banner({ tone: 'warn', text: single ? t('devices.revoke.effect') : t('devices.revoke.effectMany', { n: devices.length }) }),
          list,
          h('p', { class: 'muted', text: t('devices.revoke.stepup') }),
          rf.el,
          tf.el,
        ],
        actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close(null) } }), ok],
        onClose: (r) => resolve(r || null),
      });
      ok.addEventListener('click', () => dlg.close({ reason: reason.value.trim() || t('devices.revoke.reason.default') }));
      typed.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter' && !ok.disabled) ok.click();
      });
    });
  }

  async function revokeOne(device, reason) {
    return guarded('device.revoke', device.deviceId, (id) => api.post('/devices/' + encodeURIComponent(device.deviceId) + '/revoke', { reason }, { stepUp: id }));
  }

  function replaceDevice(updated) {
    const i = all.findIndex((d) => d.deviceId === updated.deviceId);
    if (i >= 0) all[i] = updated;
    apply();
  }

  async function revokeFlow(device) {
    const answer = await askRevoke([device]);
    if (!answer) return null;
    const updated = await revokeOne(device, answer.reason);
    if (updated) {
      replaceDevice(updated);
      toast(t('devices.revoke.done', { name: device.name || shortId(device.deviceId) }), { kind: 'success' });
      events.emit('devices-changed');
    }
    return updated || null;
  }

  async function unrevokeFlow(device) {
    const updated = await guarded('device.unrevoke', device.deviceId, (id) => api.post('/devices/' + encodeURIComponent(device.deviceId) + '/unrevoke', {}, { stepUp: id }));
    if (updated) {
      replaceDevice(updated);
      toast(t('devices.unrevoke.done', { name: device.name || shortId(device.deviceId) }), { kind: 'success' });
    }
    return updated || null;
  }

  async function bulkRevokeFlow() {
    const targets = view.filter((d) => table.selection.has(d.deviceId) && d.status === 'ACTIVE');
    if (targets.length === 0) {
      toast(t('devices.bulk.noneActive'), { kind: 'warning' });
      return;
    }
    const answer = await askRevoke(targets);
    if (!answer) return;
    const bar = h('progress', { class: 'progress', max: String(targets.length), value: '0', 'aria-label': t('devices.bulk.progress') });
    const status = h('p', { role: 'status', 'aria-live': 'polite', text: t('devices.bulk.running', { done: 0, n: targets.length }) });
    const failures = h('ul', { class: 'compact-list bad-list' });
    const stop = { requested: false };
    const stopBtn = button({ label: t('common.stop'), variant: 'ghost', on: { click: () => { stop.requested = true; } } });
    const dlg = openDialog({ title: t('devices.bulk.title'), size: 'sm', dismissible: false, body: [status, bar, failures], actions: [stopBtn] });
    let done = 0;
    let ok = 0;
    for (const d of targets) {
      if (stop.requested) break;
      let updated;
      try {
        updated = await guarded('device.revoke', d.deviceId, (id) => api.post('/devices/' + encodeURIComponent(d.deviceId) + '/revoke', { reason: answer.reason }, { stepUp: id }), { rethrow: true });
        if (!updated) break; // the confirmation was cancelled or refused: stop here, nothing else is revoked
      } catch (e) {
        failures.appendChild(h('li', { text: (d.name || shortId(d.deviceId)) + ': ' + ((e && e.message) || t('error.generic')) }));
      }
      done += 1;
      if (updated) {
        ok += 1;
        const i = all.findIndex((x) => x.deviceId === updated.deviceId);
        if (i >= 0) all[i] = updated;
      }
      bar.value = done;
      status.textContent = t('devices.bulk.running', { done, n: targets.length });
    }
    mount(dlg.el.querySelector('.dialog-foot'), button({ label: t('common.close'), variant: 'primary', on: { click: () => dlg.close() } }));
    status.textContent = t('devices.bulk.finished', { ok, failed: failures.childNodes.length, skipped: targets.length - done });
    table.selection.clear();
    apply();
    events.emit('devices-changed');
  }

  function bulkMoveFlow() {
    const targets = view.filter((d) => table.selection.has(d.deviceId));
    if (!targets.length) return;
    const sel = selectInput([{ value: '', label: t('devices.move.none') }, ...departments.map((d) => ({ value: d.id, label: d.name }))], '', { ariaLabel: t('devices.move.label') });
    const f = field({ label: t('devices.move.label'), control: sel });
    const go = button({ label: t('devices.move.submit', { n: targets.length }), variant: 'primary' });
    const dlg = openDialog({ title: t('devices.bulk.move'), size: 'sm', body: [h('p', { class: 'muted', text: t('devices.move.text', { n: targets.length }) }), f.el], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go] });
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        let ok = 0;
        for (const d of targets) {
          try {
            const updated = await api.put('/devices/' + encodeURIComponent(d.deviceId), { department: sel.value || null, moveToDepartment: true });
            const i = all.findIndex((x) => x.deviceId === d.deviceId);
            if (i >= 0) all[i] = updated;
            ok += 1;
          } catch (e) {
            toastError(e);
            break;
          }
        }
        dlg.close();
        toast(t('devices.move.done', { n: ok }), { kind: ok ? 'success' : 'error' });
        table.selection.clear();
        fillDepartments();
        apply();
      }),
    );
  }

  // ------------------------------------------------------------------ drawer
  let quiet = false;
  function closeDrawerQuiet() {
    if (!drawer) return;
    const d = drawer;
    drawer = null;
    quiet = true;
    d.close();
    quiet = false;
  }

  async function openDevice(id) {
    closeDrawerQuiet();
    const device = all.find((d) => d.deviceId === id) || (await api.get('/devices/' + encodeURIComponent(id)).catch(() => null));
    if (!device) {
      toast(t('devices.notFound'), { kind: 'warning' });
      ctx.replaceHash(['devices']);
      return;
    }
    const body = h('div', { class: 'stack' });
    drawer = openDrawer({
      title: device.name || shortId(device.deviceId),
      body,
      onClose: () => {
        drawer = null;
        if (!stopped && !quiet && ctx.segments[1]) ctx.replaceHash(['devices']);
      },
    });
    renderDrawer(body, device);
  }

  function renderDrawer(body, device) {
    const fingerprintHost = h('div', { class: 'fp-row' });
    sha256Hex(device.subjectPublicKey).then((hex) => {
      if (!hex) return;
      mount(fingerprintHost, renderFingerprint(hex, { label: t('devices.fingerprint.visual'), pixel: 10 }), h('div', { class: 'stack tight' }, mono(groupText(hex, 8), 'wrap'), copyButton({ text: hex, label: t('devices.fingerprint.copy') })));
    });
    const name = textInput({ value: device.name, maxlength: 64 });
    const nf = field({ label: t('devices.rename'), control: name });
    const saveName = button({ label: t('common.save'), size: 'sm', variant: 'secondary' });
    saveName.addEventListener('click', () =>
      withBusy(saveName, async () => {
        nf.clearError();
        if (!name.value.trim()) return nf.setError(t('devices.rename.required'));
        try {
          const updated = await api.put('/devices/' + encodeURIComponent(device.deviceId), { name: name.value.trim() });
          replaceDevice(updated);
          toast(t('devices.rename.done'), { kind: 'success' });
          renderDrawer(body, updated);
        } catch (e) {
          nf.setError(e.message || t('error.generic'));
        }
        return undefined;
      }),
    );
    const dept = selectInput([{ value: '', label: t('devices.move.none') }, ...departments.map((d) => ({ value: d.id, label: d.name }))], device.department || '', { ariaLabel: t('devices.move.label') });
    const df = field({ label: t('devices.move.label'), control: dept, hint: t('devices.move.hint') });
    const saveDept = button({ label: t('common.save'), size: 'sm', variant: 'secondary' });
    saveDept.addEventListener('click', () =>
      withBusy(saveDept, async () => {
        try {
          const updated = await api.put('/devices/' + encodeURIComponent(device.deviceId), { department: dept.value || null, moveToDepartment: true });
          replaceDevice(updated);
          toast(t('devices.move.done', { n: 1 }), { kind: 'success' });
          renderDrawer(body, updated);
        } catch (e) {
          df.setError(e.message || t('error.generic'));
        }
      }),
    );
    const history = h('div', { class: 'stack tight' }, skeleton('lines', 3));
    api
      .get('/audit', { query: { text: device.deviceId, order: 'desc', limit: 20 } })
      .then((page) => {
        mount(history, page.entries.length ? h('ul', { class: 'event-list' }, page.entries.map((e) => h('li', {}, h('span', { class: 'event-action', text: e.action }), h('span', { class: 'event-detail', text: e.actor }), h('span', { class: 'event-time' }, timeEl(e.timestampMs))))) : h('p', { class: 'muted', text: t('devices.history.empty') }));
      })
      .catch((e) => mount(history, errorState(e)));

    const actions = [];
    if (device.status === 'ACTIVE') {
      const revoke = button({ label: t('devices.revoke.button'), icon: 'lock', variant: 'danger' });
      revoke.addEventListener('click', async () => {
        const res = await revokeFlow(device);
        if (res) renderDrawer(body, res);
      });
      actions.push(revoke);
    } else if (device.status === 'REVOKED') {
      const un = button({ label: t('devices.unrevoke.button'), icon: 'refresh', variant: 'secondary' });
      un.addEventListener('click', async () => {
        const res = await unrevokeFlow(device);
        if (res) renderDrawer(body, res);
      });
      actions.push(un);
    }
    mount(
      body,
      h('div', { class: 'drawer-status' }, statusBadge(device.status), device.online ? badge(t('devices.online'), 'ok') : null),
      device.status === 'REVOKED' ? banner({ tone: 'bad', text: t('devices.revoked.note') }) : null,
      dl([
        [t('devices.col.role'), t('role.' + device.role)],
        [t('devices.col.department'), device.department || h('span', { class: 'muted', text: t('devices.noDepartment') })],
        [t('devices.detail.enrolled'), timeEl(device.enrolledAt)],
        [t('devices.col.lastSeen'), device.online ? t('devices.online') : device.lastSeenAt ? timeEl(device.lastSeenAt) : t('devices.never')],
        [t('devices.col.cert'), device.certExpiry ? h('span', {}, formatDateTime(device.certExpiry)) : null],
      ]),
      h('div', { class: 'form-actions start' }, actions),
      h('div', { class: 'inline-form' }, nf.el, saveName),
      h('div', { class: 'inline-form' }, df.el, saveDept),
      h('h3', { class: 'subhead', text: t('devices.history') }),
      history,
      h(
        'details',
        { class: 'tech' },
        h('summary', { text: t('devices.tech') }),
        dl([
          [t('devices.tech.id'), mono(device.deviceId, 'wrap')],
          [t('devices.tech.serial'), mono(String(device.serial))],
          [t('devices.tech.invite'), device.inviteId ? mono(device.inviteId, 'wrap') : null],
          [t('devices.tech.fingerprint'), fingerprintHost],
        ]),
      ),
    );
  }

  const off = events.on('devices-changed', () => {});
  load();
  return {
    destroy() {
      stopped = true;
      clearTimeout(timer);
      setActiveTable(null);
      closeDrawerQuiet();
      off();
    },
    onRoute(next) {
      ctx = { ...ctx, ...next };
      if (!loaded) return;
      if (next.segments[1]) openDevice(next.segments[1]);
      else closeDrawerQuiet();
    },
  };
}
