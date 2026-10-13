// Invites: create one (with scope: department, role, groups, expiry, uses, note), create many from pasted or uploaded CSV with a
// validated preview, see their state, print an onboarding card with the QR, and revoke. The invite secret (link and QR) exists only in
// the response that creates it, so the result view says so and offers copy, download and print right there.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api, guarded } from '../app.js';
import { formatNumber, formatDateTime, shortId } from '../format.js';
import { validateBulk, toBulkCsv, toCsv, MAX_BULK_ROWS } from '../csv.js';
import { filterRows, sortRows } from '../tablemodel.js';
import { renderQr } from '../qr.js';
import { pageHeader, button, badge, banner, emptyState, textInput, selectInput, field, withBusy, timeEl, copyButton, skeleton, errorState, mono } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDialog, confirmDialog } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { setActiveTable } from '../shortcuts.js';
import { downloadText } from './setup.js';
import { icon } from '../icons.js';

const PRESETS = [
  { minutes: 60, key: 'invites.ttl.1h' },
  { minutes: 1440, key: 'invites.ttl.1d' },
  { minutes: 4320, key: 'invites.ttl.3d' },
  { minutes: 10080, key: 'invites.ttl.7d' },
  { minutes: 43200, key: 'invites.ttl.30d' },
];

export function inviteStateBadge(state) {
  const tone = { PENDING: 'accent', USED: 'ok', EXPIRED: 'warn', REVOKED: 'bad' }[state] || 'neutral';
  return badge(t('invite.state.' + state.toLowerCase()), tone);
}

/** Prints onboarding cards: they are built into #print-root, the print stylesheet hides everything else. */
export function printCards(items, hubName) {
  const root = globalThis.document.getElementById('print-root');
  if (!root) return;
  mount(
    root,
    items.map((it) =>
      h(
        'article',
        { class: 'print-card' },
        h('header', { class: 'print-head' }, icon('bolt', { size: 28 }), h('div', {}, h('div', { class: 'print-hub', text: hubName || t('app.name') }), h('div', { class: 'print-title', text: t('print.title') }))),
        it.name ? h('p', { class: 'print-name', text: it.name }) : null,
        h('div', { class: 'print-qr' }, renderQr(it.qrSvg, { label: t('print.qr'), className: 'qr qr-print' })),
        h('ol', { class: 'print-steps' }, [1, 2, 3].map((n) => h('li', { text: t('print.step' + n) }))),
        h('p', { class: 'print-link-label', text: t('print.link') }),
        h('p', { class: 'print-link', text: it.link }),
        h('p', { class: 'print-foot', text: t('print.expires', { when: formatDateTime(it.expiresAt), uses: it.maxUses }) }),
      ),
    ),
  );
  const doc = globalThis.document;
  doc.body.classList.add('is-printing');
  const done = () => {
    doc.body.classList.remove('is-printing');
    mount(root);
    globalThis.removeEventListener('afterprint', done);
  };
  globalThis.addEventListener('afterprint', done);
  globalThis.print();
}

export function mountInvites(container, ctx) {
  let invites = [];
  let departments = [];
  let groups = [];
  let defaults = { ttl: 1440, uses: 1 };
  let stopped = false;
  const state = { text: ctx.query.q || '', state: ctx.query.state || '' };

  const search = textInput({ type: 'search', placeholder: t('invites.search.placeholder'), value: state.text, className: 'input-search' });
  search.setAttribute('data-search', '');
  search.setAttribute('aria-label', t('invites.search'));
  const stateSel = selectInput(
    [{ value: '', label: t('invites.filter.all') }, ...['PENDING', 'USED', 'EXPIRED', 'REVOKED'].map((s) => ({ value: s, label: t('invite.state.' + s.toLowerCase()) }))],
    state.state,
    { ariaLabel: t('invites.filter.state') },
  );
  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
  const tableHost = h('div', { class: 'table-host' });
  const toolbar = h('div', { class: 'toolbar', role: 'search' }, h('div', { class: 'toolbar-search' }, icon('search', { size: 18, className: 'search-glyph' }), search), stateSel);

  const table = createTable({
    ariaLabel: t('invites.table'),
    rowId: (i) => i.inviteId,
    rowLabel: (i) => i.note || shortId(i.inviteId),
    columns: [
      { key: 'note', label: t('invites.col.invite'), sortable: true, render: (i) => h('span', { class: 'cell-stack' }, h('span', { class: 'cell-title', text: i.note || t('invites.noNote') }), h('span', { class: 'cell-sub mono', text: shortId(i.inviteId, 10, 4) })) },
      { key: 'state', label: t('invites.col.state'), sortable: true, render: (i) => inviteStateBadge(i.state) },
      { key: 'department', label: t('invites.col.department'), sortable: true, className: 'hide-sm', render: (i) => i.department || h('span', { class: 'muted', text: t('devices.noDepartment') }) },
      { key: 'role', label: t('invites.col.role'), sortable: true, className: 'hide-md', render: (i) => t('role.' + i.role) },
      { key: 'uses', label: t('invites.col.uses'), sortable: true, className: 'hide-sm num', render: (i) => i.uses + ' / ' + i.maxUses },
      { key: 'expiresAt', label: t('invites.col.expires'), sortable: true, render: (i) => timeEl(i.expiresAt) },
      { key: 'createdBy', label: t('invites.col.by'), className: 'hide-md', render: (i) => i.createdBy || '–' },
      { key: 'actions', label: t('invites.col.actions'), render: (i) => (i.state === 'PENDING' ? button({ label: t('invites.revoke'), size: 'sm', variant: 'ghost', ariaLabel: t('invites.revoke.for', { name: i.note || shortId(i.inviteId) }), on: { click: () => revoke(i) } }) : null) },
    ],
    onSort: (s) => {
      sortState = s;
      apply();
    },
  });
  let sortState = { key: 'expiresAt', dir: 'desc' };
  table.setSort(sortState);
  const getters = { note: (i) => (i.note || '').toLowerCase(), state: (i) => i.state, department: (i) => i.department || null, role: (i) => i.role, uses: (i) => i.uses, expiresAt: (i) => i.expiresAt };

  function apply() {
    const filtered = filterRows(invites, { text: state.text, fields: ['note', 'inviteId', 'department', 'role', 'createdBy'], predicates: state.state ? [(i) => i.state === state.state] : [] });
    const view = sortRows(filtered, sortState, getters);
    ctx.replaceQuery({ q: state.text, state: state.state });
    resultLine.textContent = invites.length ? t('invites.result', { shown: view.length, total: invites.length }) : '';
    mount(tableHost);
    if (invites.length === 0) {
      tableHost.appendChild(emptyState({ title: t('invites.empty.title'), text: t('invites.empty.text'), glyph: 'invites', action: button({ label: t('invites.create'), icon: 'plus', variant: 'primary', on: { click: () => openCreate() } }) }));
    } else if (view.length === 0) {
      tableHost.appendChild(emptyState({ title: t('invites.nomatch.title'), text: t('invites.nomatch.text'), glyph: 'search' }));
    } else {
      table.update({ rows: view, sort: sortState });
      tableHost.appendChild(table.el);
    }
  }

  search.addEventListener('input', () => {
    state.text = search.value;
    apply();
  });
  stateSel.addEventListener('change', () => {
    state.state = stateSel.value;
    apply();
  });

  mount(
    container,
    pageHeader({
      title: t('invites.title'),
      subtitle: t('invites.sub'),
      actions: [button({ label: t('invites.bulk'), icon: 'upload', on: { click: () => openBulk() } }), button({ label: t('invites.create'), icon: 'plus', variant: 'primary', on: { click: () => openCreate() } })],
    }),
    toolbar,
    resultLine,
    tableHost,
  );
  mount(tableHost, skeleton('table', 6));
  setActiveTable(table);

  async function load() {
    mount(tableHost, skeleton('table', 6));
    try {
      const rows = [];
      for (let offset = 0; offset < 5000; offset += 1000) {
        const page = await api.get('/invites', { query: { limit: 1000, offset } });
        rows.push(...page);
        if (page.length < 1000) break;
      }
      if (stopped) return;
      invites = rows;
      apply();
    } catch (e) {
      if (!stopped) mount(tableHost, errorState(e, load));
    }
  }

  async function loadSupport() {
    try {
      [departments, groups] = await Promise.all([api.get('/departments'), api.get('/groups')]);
      const s = await api.get('/settings');
      defaults = { ttl: s.inviteDefaultTtlMinutes || 1440, uses: s.inviteDefaultMaxUses || 1 };
    } catch (e) {
      /* the create dialog works without them; it explains what is missing */
    }
  }

  async function revoke(invite) {
    const ok = await confirmDialog({ title: t('invites.revoke.title'), message: t('invites.revoke.text', { name: invite.note || shortId(invite.inviteId) }), confirmLabel: t('invites.revoke'), danger: true });
    if (!ok) return;
    try {
      const updated = await api.post('/invites/' + encodeURIComponent(invite.inviteId) + '/revoke', {});
      const i = invites.findIndex((x) => x.inviteId === invite.inviteId);
      if (i >= 0) invites[i] = updated;
      apply();
      toast(t('invites.revoked'), { kind: 'success' });
    } catch (e) {
      toastError(e);
    }
  }

  // ------------------------------------------------------------------ create
  async function openCreate() {
    if (!departments.length && !groups.length) await loadSupport();
    const dept = selectInput([{ value: '', label: t('invites.create.noDept') }, ...departments.map((d) => ({ value: d.id, label: d.name }))], '', { ariaLabel: t('invites.create.department') });
    const df = field({ label: t('invites.create.department'), control: dept, hint: t('invites.create.department.hint') });
    const role = selectInput([{ value: 'member', label: t('role.member') }, { value: 'dept_admin', label: t('role.dept_admin') }, { value: 'admin', label: t('role.admin') }], 'member', { ariaLabel: t('invites.create.role') });
    const rf = field({ label: t('invites.create.role'), control: role, hint: t('invites.create.role.hint') });
    const roleWarn = h('div', {}, );
    role.addEventListener('change', () => {
      mount(roleWarn, role.value !== 'member' ? banner({ tone: 'warn', text: t('invites.create.role.warn') }) : null);
    });
    const preset = selectInput(
      [...PRESETS.map((p) => ({ value: String(p.minutes), label: t(p.key) })), { value: 'custom', label: t('invites.ttl.custom') }],
      PRESETS.some((p) => p.minutes === defaults.ttl) ? String(defaults.ttl) : 'custom',
      { ariaLabel: t('invites.create.expiry') },
    );
    const custom = textInput({ type: 'number', value: String(defaults.ttl), maxlength: 6 });
    custom.min = '5';
    custom.max = '43200';
    const customWrap = h('div', { hidden: preset.value !== 'custom' }, field({ label: t('invites.ttl.minutes'), control: custom, hint: t('invites.ttl.minutes.hint') }).el);
    preset.addEventListener('change', () => {
      customWrap.hidden = preset.value !== 'custom';
    });
    const ef = field({ label: t('invites.create.expiry'), control: preset });
    const uses = textInput({ type: 'number', value: String(defaults.uses) });
    uses.min = '1';
    uses.max = '1000';
    const uf = field({ label: t('invites.create.uses'), control: uses, hint: t('invites.create.uses.hint') });
    const note = textInput({ maxlength: 128, placeholder: t('invites.create.note.placeholder') });
    const nf = field({ label: t('invites.create.note'), control: note, hint: t('invites.create.note.hint') });
    const groupBox = h('fieldset', { class: 'fieldset' }, h('legend', { text: t('invites.create.groups') }));
    const groupBoxes = new Map();
    function drawGroups() {
      const list = groups.filter((g) => !dept.value || g.departmentId === dept.value);
      mount(groupBox, h('legend', { text: t('invites.create.groups') }));
      if (!list.length) {
        groupBox.appendChild(h('p', { class: 'muted small', text: t('invites.create.groups.none') }));
        return;
      }
      groupBox.appendChild(h('p', { class: 'muted small', text: t('invites.create.groups.hint') }));
      for (const g of list) {
        const allow = h('input', { type: 'checkbox', class: 'check', id: 'ga-' + g.id });
        const auto = h('input', { type: 'checkbox', class: 'check', id: 'gj-' + g.id });
        auto.addEventListener('change', () => {
          if (auto.checked) allow.checked = true;
        });
        allow.addEventListener('change', () => {
          if (!allow.checked) auto.checked = false;
        });
        groupBoxes.set(g.id, { allow, auto });
        groupBox.appendChild(h('div', { class: 'group-pick' }, h('span', { class: 'group-pick-name', text: g.name }), h('label', { class: 'check-row', for: 'ga-' + g.id }, allow, h('span', { text: t('invites.create.allow') })), h('label', { class: 'check-row', for: 'gj-' + g.id }, auto, h('span', { text: t('invites.create.autojoin') }))));
      }
    }
    dept.addEventListener('change', drawGroups);
    drawGroups();
    const message = h('div', { 'aria-live': 'polite' });
    const submit = button({ label: t('invites.create.submit'), variant: 'primary', icon: 'plus' });
    const form = h('form', { class: 'stack', novalidate: true }, df.el, rf.el, roleWarn, h('div', { class: 'two-col' }, ef.el, uf.el), customWrap, groupBox, nf.el, message);
    const dlg = openDialog({ title: t('invites.create.title'), size: 'md', body: form, actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), submit] });
    const run = () =>
      withBusy(submit, async () => {
        mount(message);
        const ttl = preset.value === 'custom' ? Number.parseInt(custom.value, 10) : Number.parseInt(preset.value, 10);
        const maxUses = Number.parseInt(uses.value, 10);
        if (!(ttl >= 5 && ttl <= 43200)) return mount(message, banner({ tone: 'bad', text: t('invites.ttl.minutes.hint') }));
        if (!(maxUses >= 1 && maxUses <= 1000)) return mount(message, banner({ tone: 'bad', text: t('invites.create.uses.hint') }));
        const allowed = [];
        const auto = [];
        for (const [id, b] of groupBoxes) {
          if (b.allow.checked) allowed.push(id);
          if (b.auto.checked) auto.push(id);
        }
        const body = { department: dept.value || undefined, role: role.value, allowedGroups: allowed, autoJoinGroups: auto, ttlMinutes: ttl, maxUses, note: note.value.trim() || undefined };
        try {
          const created =
            role.value === 'member'
              ? await api.post('/invites', body)
              : await guarded('admin.create', 'invite:' + role.value, (id) => api.post('/invites', body, { stepUp: id }), { rethrow: true });
          if (created) {
            invites.unshift(created.invite);
            apply();
            showCreated(dlg, created, note.value.trim());
          }
        } catch (e) {
          mount(message, banner({ tone: 'bad', text: e.message || t('error.generic'), actions: e.correlationId ? h('span', { class: 'ref', text: t('error.reference', { id: e.correlationId }) }) : null }));
        }
        return undefined;
      });
    submit.addEventListener('click', run);
    form.addEventListener('submit', (ev) => ev.preventDefault());
    form.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) run();
    });
  }

  function showCreated(dlg, created, name) {
    const qr = renderQr(created.qrSvg, { label: t('invites.result.qr'), className: 'qr qr-lg' });
    const item = { name, link: created.link, qrSvg: created.qrSvg, expiresAt: created.invite.expiresAt, maxUses: created.invite.maxUses };
    dlg.setBody(
      banner({ tone: 'warn', title: t('invites.result.once'), text: t('invites.result.once.text') }),
      h('div', { class: 'result-grid' }, h('div', { class: 'qr-block' }, qr), h('div', { class: 'stack' }, h('label', { class: 'label', for: 'invite-link', text: t('invites.result.link') }), h('textarea', { class: 'input textarea mono', id: 'invite-link', rows: '4', readonly: true, spellcheck: 'false', text: created.link }), h('div', { class: 'form-actions start' }, copyButton({ text: created.link, label: t('invites.result.copy'), small: false }), button({ label: t('invites.result.print'), icon: 'print', on: { click: () => printCards([item], ctx.hubName()) } })))),
    );
    dlg.setActions(
      button({ label: t('invites.result.another'), variant: 'secondary', on: { click: () => { dlg.close(); openCreate(); } } }),
      button({ label: t('common.done'), variant: 'primary', on: { click: () => dlg.close() } }),
    );
  }

  // ------------------------------------------------------------------ bulk
  async function openBulk() {
    if (!departments.length) await loadSupport();
    const area = h('textarea', { class: 'input textarea mono', rows: '8', spellcheck: 'false', autocomplete: 'off', placeholder: t('invites.bulk.placeholder'), 'aria-label': t('invites.bulk.paste') });
    const file = h('input', { type: 'file', class: 'file-input', accept: '.csv,text/csv,text/plain', 'aria-label': t('invites.bulk.file') });
    const preset = selectInput(PRESETS.map((p) => ({ value: String(p.minutes), label: t(p.key) })), '1440', { ariaLabel: t('invites.create.expiry') });
    const pf = field({ label: t('invites.create.expiry'), control: preset });
    const preview = h('div', { class: 'stack', 'aria-live': 'polite' });
    const go = button({ label: t('invites.bulk.submit', { n: 0 }), variant: 'primary', disabled: true });
    let validRows = [];

    function check() {
      const { rows, tooMany } = validateBulk(area.value, departments);
      const bad = rows.filter((r) => r.errors.length);
      validRows = rows.filter((r) => !r.errors.length);
      mount(preview);
      if (!rows.length) {
        go.disabled = true;
        go.querySelector('.btn-label').textContent = t('invites.bulk.submit', { n: 0 });
        return;
      }
      preview.appendChild(h('p', { class: 'muted', text: t('invites.bulk.summary', { ok: validRows.length, bad: bad.length }) }));
      if (tooMany) preview.appendChild(banner({ tone: 'info', text: t('invites.bulk.batches', { max: MAX_BULK_ROWS }) }));
      const tbl = h('table', { class: 'table table-compact', 'aria-label': t('invites.bulk.preview') }, h('thead', {}, h('tr', {}, ['invites.bulk.col.line', 'invites.bulk.col.name', 'invites.bulk.col.department', 'invites.bulk.col.check'].map((k) => h('th', { scope: 'col', text: t(k) })))), h('tbody', {}, rows.slice(0, 300).map((r) => h('tr', { class: r.errors.length ? 'row-bad' : '' }, h('td', { class: 'num', text: String(r.line) }), h('td', { text: r.name }), h('td', { text: r.department || '–' }), h('td', {}, r.errors.length ? badge(r.errors.map((e) => t('invites.bulk.err.' + e)).join(', '), 'bad') : badge(t('invites.bulk.ok'), 'ok'))))));
      preview.appendChild(h('div', { class: 'table-scroll short' }, tbl));
      if (rows.length > 300) preview.appendChild(h('p', { class: 'muted small', text: t('invites.bulk.previewCap', { n: rows.length - 300 }) }));
      go.disabled = validRows.length === 0;
      go.querySelector('.btn-label').textContent = t('invites.bulk.submit', { n: validRows.length });
    }
    area.addEventListener('input', check);
    file.addEventListener('change', async () => {
      const f = file.files && file.files[0];
      if (!f) return;
      if (f.size > 512 * 1024) return toast(t('invites.bulk.fileBig'), { kind: 'error' });
      area.value = await f.text();
      check();
      return undefined;
    });
    const dlg = openDialog({
      title: t('invites.bulk.title'),
      size: 'lg',
      body: [h('p', { class: 'muted', text: t('invites.bulk.help') }), file, h('div', { class: 'field' }, h('label', { class: 'label', for: 'bulk-text', text: t('invites.bulk.paste') }), area), pf.el, preview],
      actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go],
    });
    area.id = 'bulk-text';
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        const results = [];
        try {
          for (let i = 0; i < validRows.length; i += MAX_BULK_ROWS) {
            const batch = validRows.slice(i, i + MAX_BULK_ROWS);
            const res = await api.post('/invites/bulk', { csv: toBulkCsv(batch), ttlMinutes: Number.parseInt(preset.value, 10), role: 'member' });
            results.push(...res.rows);
          }
        } catch (e) {
          toastError(e);
        }
        if (results.length) {
          await load();
          showBulkResult(dlg, results);
        }
      }),
    );
  }

  function showBulkResult(dlg, rows) {
    const ok = rows.filter((r) => r.created);
    const failed = rows.length - ok.length;
    const items = ok.map((r) => ({ name: r.name, link: r.created.link, qrSvg: r.created.qrSvg, expiresAt: r.created.invite.expiresAt, maxUses: r.created.invite.maxUses, department: r.created.invite.department }));
    dlg.setBody(
      banner({ tone: 'warn', title: t('invites.result.once'), text: t('invites.bulk.result.once') }),
      h('p', { text: t('invites.bulk.result', { ok: ok.length, failed }) }),
      failed ? h('ul', { class: 'compact-list bad-list' }, rows.filter((r) => !r.created).map((r) => h('li', { text: t('invites.bulk.result.row', { line: r.line, name: r.name, error: r.error || '' }) }))) : null,
      h('div', { class: 'form-actions start' }, button({ label: t('invites.bulk.download'), icon: 'download', on: { click: () => downloadText('flash-hub-invites.csv', toCsv(['name', 'department', 'link', 'expires'], items.map((i) => [i.name, i.department || '', i.link, new Date(i.expiresAt).toISOString()]))) } }), button({ label: t('invites.bulk.printAll'), icon: 'print', on: { click: () => printCards(items, ctx.hubName()) } })),
    );
    dlg.setActions(button({ label: t('common.done'), variant: 'primary', on: { click: () => dlg.close() } }));
  }

  load();
  loadSupport();
  return {
    destroy() {
      stopped = true;
      setActiveTable(null);
    },
    onRoute(next) {
      ctx = { ...ctx, ...next };
    },
  };
}
