// Security: this administrator's account (password, recovery codes, authenticator, the confirmation key of this browser), all
// administrators, open sessions, the delegation, the revocation list, and the trust anchors (root fingerprint, TLS and listener summary).

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api, guarded, events } from '../app.js';
import { formatNumber, formatDateTime, groupText, shortId } from '../format.js';
import { renderFingerprint } from '../identicon.js';
import { renderQr } from '../qr.js';
import * as keys from '../stepup-key.js';
import { session } from '../state.js';
import { pageHeader, card, button, badge, banner, emptyState, textInput, selectInput, field, withBusy, timeEl, tabs, skeleton, errorState, mono, dl, copyButton } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDialog, confirmDialog } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { loadInto } from '../ui/boundary.js';
import { askToEnrol } from '../ui/keydialog.js';
import { stepUp } from '../app.js';
import { delegationBody } from './delegation.js';
import { downloadText } from './setup.js';
import { devicePicker, loadDeviceNames, deviceLabel } from './devicepicker.js';
import { setActiveTable } from '../shortcuts.js';

const TABS = ['account', 'admins', 'sessions', 'delegation', 'revocations', 'trust'];

/** Shows one-time credentials (QR, key, recovery codes) in a dialog; resolves when closed. */
export function showCredentials({ title, credentials, intro }) {
  return new Promise((resolve) => {
    const qr = renderQr(credentials.qrSvg, { label: t('setup.auth.qrLabel'), className: 'qr qr-md' });
    const codes = credentials.recoveryCodes || [];
    const text = codes.join('\n');
    const ack = h('input', { type: 'checkbox', class: 'check', id: 'cred-ack' });
    const done = button({ label: t('common.done'), variant: 'primary', disabled: true, on: { click: () => dlg.close() } });
    ack.addEventListener('change', () => {
      done.disabled = !ack.checked;
    });
    const dlg = openDialog({
      title,
      size: 'md',
      dismissible: false,
      body: [
        intro ? h('p', { text: intro }) : null,
        banner({ tone: 'warn', title: t('setup.recovery.once'), text: t('creds.once') }),
        credentials.totpSecret ? h('div', { class: 'setup-auth' }, h('div', { class: 'qr-block' }, qr), h('div', { class: 'secret-box' }, h('div', { class: 'secret-label', text: t('setup.auth.manual') }), mono(groupText(credentials.totpSecret), 'secret'), copyButton({ text: credentials.totpSecret, label: t('setup.auth.copyKey') }))) : null,
        codes.length ? h('ul', { class: 'recovery-codes', 'aria-label': t('setup.recovery.list') }, codes.map((c) => h('li', {}, mono(c)))) : null,
        codes.length ? h('div', { class: 'form-actions start' }, copyButton({ text, label: t('setup.recovery.copy'), small: false }), button({ label: t('setup.recovery.download'), icon: 'download', on: { click: () => downloadText('flash-hub-recovery-codes.txt', t('setup.recovery.fileHeader', { user: credentials.username }) + '\n\n' + text + '\n') } })) : null,
        h('label', { class: 'check-row', for: 'cred-ack' }, ack, h('span', { text: t('creds.ack') })),
      ],
      actions: [done],
      onClose: () => resolve(),
    });
  });
}

export function mountSecurity(container, ctx) {
  let stopped = false;
  let current = TABS.includes(ctx.query.tab) ? ctx.query.tab : 'account';
  let ready = false;
  const tabBar = tabs({
    label: t('sec.tabs'),
    selected: current,
    items: TABS.map((id) => ({ id, label: t('sec.tab.' + id) })),
    onSelect: (id) => {
      current = id;
      if (!ready) return;
      ctx.replaceQuery({ tab: id === 'account' ? '' : id });
      renderTab();
    },
  });
  const panel = tabBar.panel;
  mount(container, pageHeader({ title: t('sec.title'), subtitle: t('sec.sub') }), tabBar.el, panel);

  function renderTab() {
    setActiveTable(null);
    ({ account: renderAccount, admins: renderAdmins, sessions: renderSessions, delegation: renderDelegation, revocations: renderRevocations, trust: renderTrust })[current]();
  }

  // ------------------------------------------------------------------ account
  function renderAccount() {
    const keyHost = h('div', { class: 'stack' });
    const pwHost = h('div', { class: 'stack' });
    const recHost = h('div', { class: 'stack' });
    const totpHost = h('div', { class: 'stack' });
    const apprHost = h('div', { class: 'stack' });
    mount(
      panel,
      h(
        'div',
        { class: 'grid grid-2' },
        card({ title: t('sec.key.title'), subtitle: t('sec.key.sub'), id: 'sec-key' }, keyHost),
        card({ title: t('sec.pw.title'), id: 'sec-pw' }, pwHost),
        card({ title: t('sec.rec.title'), subtitle: t('sec.rec.sub'), id: 'sec-rec' }, recHost),
        card({ title: t('sec.totp.title'), subtitle: t('sec.totp.sub'), id: 'sec-totp' }, totpHost),
        card({ title: t('sec.appr.title'), subtitle: t('sec.appr.sub'), id: 'sec-appr' }, apprHost),
      ),
    );
    renderKeyCard(keyHost);
    renderPasswordCard(pwHost);
    renderRecoveryCard(recHost);
    renderTotpCard(totpHost);
    renderApproverCard(apprHost);
  }

  async function renderKeyCard(host) {
    mount(host, skeleton('lines', 3));
    const supported = keys.isSupported();
    let local = null;
    let dto = null;
    try {
      [local, dto] = await Promise.all([supported ? keys.loadKey(session.user) : null, api.get('/session')]);
    } catch (e) {
      mount(host, errorState(e, () => renderKeyCard(host)));
      return;
    }
    const serverId = dto.enrolledDeviceId;
    let tone = 'warn';
    let statusText = t('sec.key.none');
    if (!supported) statusText = t('stepup.unsupported');
    else if (local && serverId && local.keyId === serverId) {
      tone = 'ok';
      statusText = t('sec.key.ok');
    } else if (local && serverId) statusText = t('sec.key.mismatch');
    else if (local && !serverId) statusText = t('sec.key.serverNone');
    else if (!local && serverId) statusText = t('sec.key.otherBrowser');
    const enrol = button({ label: serverId ? t('sec.key.replace') : t('sec.key.setup'), icon: 'key', variant: tone === 'ok' ? 'secondary' : 'primary', disabled: !supported });
    enrol.addEventListener('click', async () => {
      const ok = await askToEnrol(local ? 'key-mismatch' : 'no-key', stepUp);
      if (ok) renderKeyCard(host);
    });
    const forget = local ? button({ label: t('sec.key.forget'), variant: 'ghost', icon: 'trash', on: { click: async () => { if (await confirmDialog({ title: t('sec.key.forget.title'), message: t('sec.key.forget.text'), confirmLabel: t('sec.key.forget'), danger: true })) { await keys.removeKey(session.user); renderKeyCard(host); } } } }) : null;
    mount(
      host,
      banner({ tone: tone === 'ok' ? 'ok' : 'warn', text: statusText }),
      dl([
        [t('sec.key.server'), serverId ? mono(serverId) : h('span', { class: 'muted', text: t('sec.key.noneRegistered') })],
        [t('sec.key.here'), local ? mono(local.keyId) : h('span', { class: 'muted', text: t('sec.key.noneHere') })],
        [t('sec.key.label'), local && local.label ? local.label : null],
      ]),
      h('div', { class: 'form-actions start' }, enrol, forget),
      h('p', { class: 'muted small', text: t('sec.key.limits') }),
    );
  }

  // ADR-131 (FO-14): the approver phone. Binding the first one needs a fresh authenticator code (and this browser's confirmation when it has
  // a key); replacing it needs the old phone's approval; removing it (the lost-phone path) needs a recovery code.
  async function renderApproverCard(host) {
    mount(host, skeleton('lines', 2));
    let dto;
    let names;
    try {
      [dto, names] = await Promise.all([api.get('/session'), loadDeviceNames(true)]);
    } catch (e) {
      mount(host, errorState(e, () => renderApproverCard(host)));
      return;
    }
    const bound = dto.approverDeviceId || null;
    const hasSigner = !!dto.enrolledDeviceId;
    let picked = null;
    const pickedNote = h('p', { class: 'muted small', role: 'status', 'aria-live': 'polite' });
    const picker = devicePicker({
      label: t('sec.appr.pick'),
      onPick: (d) => {
        picked = d;
        pickedNote.textContent = t('sec.appr.picked', { name: d.name || d.deviceId });
      },
      exclude: () => new Set(bound ? [bound] : []),
    });
    const code = textInput({ inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 8, className: 'input-code' });
    const codeField = field({ label: t('sec.appr.code'), control: code, hint: t('keydialog.code.hint') });
    const go = button({ label: bound ? t('sec.appr.replace') : t('sec.appr.bind'), icon: 'key', variant: 'primary' });
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        codeField.clearError();
        if (!picked) {
          pickedNote.textContent = t('sec.appr.pick');
          return;
        }
        const deviceId = picked.deviceId;
        const clean = code.value.replace(/\s+/g, '');
        try {
          if (!bound && !/^\d{6}$/.test(clean)) {
            codeField.setError(t('keydialog.code.invalid'));
            code.focus();
            return;
          }
          const send = (id) => api.post('/admins/me/approver', { deviceId, code: clean }, id ? { stepUp: id } : undefined);
          let ok;
          if (bound || hasSigner) ok = await guarded('approver.change', 'approver:' + deviceId, (id) => send(id), { rethrow: true });
          else ok = await send(null);
          if (ok === undefined) return;
          toast(t('sec.appr.done'), { kind: 'success' });
          await renderApproverCard(host);
        } catch (e) {
          if (e && e.status === 403) codeField.setError(t('keydialog.code.refused'));
          else if (e && e.status === 429) codeField.setError(t('error.rate.wait', { seconds: e.retryAfter || 30 }));
          else toastError(e);
        }
      }),
    );
    const remove = bound
      ? button({
          label: t('sec.appr.remove'),
          variant: 'ghost',
          icon: 'trash',
          on: {
            click: () => {
              const rc = textInput({ autocomplete: 'off', maxlength: 64, className: 'input-code' });
              rc.setAttribute('data-autofocus', '');
              const rf = field({ label: t('sec.appr.remove.code'), control: rc, required: true });
              const rm = button({ label: t('sec.appr.remove'), variant: 'danger' });
              const dlg = openDialog({
                title: t('sec.appr.remove.title'),
                size: 'sm',
                body: [h('p', { text: t('sec.appr.remove.text') }), rf.el],
                actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), rm],
              });
              rm.addEventListener('click', () =>
                withBusy(rm, async () => {
                  rf.clearError();
                  try {
                    await api.post('/admins/me/approver/remove', { code: rc.value.replace(/\s+/g, '') });
                    dlg.close();
                    toast(t('sec.appr.removed'), { kind: 'success' });
                    await renderApproverCard(host);
                  } catch (e) {
                    if (e && e.status === 403) rf.setError(t('sec.appr.remove.refused'));
                    else if (e && e.status === 429) rf.setError(t('error.rate.wait', { seconds: e.retryAfter || 30 }));
                    else rf.setError((e && e.message) || t('error.generic'));
                    rc.focus();
                  }
                }),
              );
            },
          },
        })
      : null;
    mount(
      host,
      banner({ tone: bound ? 'ok' : 'warn', text: bound ? t('sec.appr.bound') : t('sec.appr.none') }),
      bound ? dl([[t('sec.appr.device'), deviceLabel(names, bound)]]) : null,
      picker.el,
      pickedNote,
      bound ? null : codeField.el,
      h('div', { class: 'form-actions start' }, go, remove),
      h('p', { class: 'muted small', text: t('sec.appr.limits') }),
    );
  }

  function renderPasswordCard(host) {
    const oldPw = textInput({ type: 'password', autocomplete: 'current-password', maxlength: 256 });
    const newPw = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    const again = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    const of = field({ label: t('sec.pw.old'), control: oldPw, required: true });
    const nf = field({ label: t('sec.pw.new'), control: newPw, hint: t('setup.password.hint'), required: true });
    const af = field({ label: t('setup.password2'), control: again, required: true });
    const submit = button({ label: t('sec.pw.submit'), variant: 'primary', type: 'submit' });
    const form = h('form', { class: 'stack', novalidate: true }, of.el, nf.el, af.el, h('p', { class: 'muted small', text: t('sec.pw.note') }), h('div', { class: 'form-actions start' }, submit));
    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      for (const x of [of, nf, af]) x.clearError();
      if (!oldPw.value) return of.setError(t('sec.pw.old.required'));
      if (newPw.value.length < 10) return nf.setError(t('setup.password.short'));
      if (newPw.value !== again.value) return af.setError(t('setup.password.mismatch'));
      return withBusy(submit, async () => {
        try {
          await api.post('/admins/me/password', { oldPassword: oldPw.value, newPassword: newPw.value });
          ctx.signedOut('passwordChanged');
        } catch (e) {
          if (e.status === 403) of.setError(t('sec.pw.old.wrong'));
          else toastError(e);
        }
      });
    });
    mount(host, form);
  }

  function renderRecoveryCard(host) {
    const remaining = h('p', { class: 'muted' });
    api.get('/admins').then((list) => {
      const me = list.find((a) => a.username.toLowerCase() === session.user.toLowerCase());
      remaining.textContent = me ? t('sec.rec.remaining', { n: me.recoveryCodesRemaining }) : '';
    }).catch(() => {});
    const regen = button({ label: t('sec.rec.regen'), icon: 'refresh', variant: 'secondary' });
    regen.addEventListener('click', () => {
      const code = textInput({ inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 8, className: 'input-code' });
      code.setAttribute('data-autofocus', '');
      const cf = field({ label: t('keydialog.code'), control: code, hint: t('keydialog.code.hint') });
      const go = button({ label: t('sec.rec.regen'), variant: 'primary' });
      const dlg = openDialog({ title: t('sec.rec.regen.title'), size: 'sm', body: [h('p', { text: t('sec.rec.regen.text') }), cf.el], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go] });
      go.addEventListener('click', () =>
        withBusy(go, async () => {
          cf.clearError();
          try {
            const body = { code: code.value.replace(/\s+/g, '') };
            // With a signer enrolled the hub also wants its confirmation (new recovery codes could replace the signer).
            let res;
            try {
              res = await api.post('/admins/me/recovery-codes', body);
            } catch (first) {
              if (!(first && first.code === 'ERR_STEP_UP')) throw first;
              res = await guarded('recovery.regenerate', 'codes', (id) => api.post('/admins/me/recovery-codes', body, { stepUp: id }), { rethrow: true });
              if (!res) return;
            }
            dlg.close();
            await showCredentials({ title: t('sec.rec.new.title'), credentials: { username: session.user, recoveryCodes: res.recoveryCodes }, intro: t('sec.rec.new.intro') });
            renderRecoveryCard(host);
          } catch (e) {
            if (e.status === 403) cf.setError(t('keydialog.code.refused'));
            else cf.setError(e.message || t('error.generic'));
            code.focus();
          }
        }),
      );
    });
    mount(host, remaining, h('div', { class: 'form-actions start' }, regen));
  }

  function renderTotpCard(host) {
    const go = button({ label: t('sec.totp.reset'), icon: 'refresh', variant: 'secondary' });
    go.addEventListener('click', async () => {
      const ok = await confirmDialog({ title: t('sec.totp.reset.title'), message: t('sec.totp.reset.text'), confirmLabel: t('sec.totp.reset'), danger: true });
      if (ok) await resetTotp(session.user, true);
    });
    mount(host, h('p', { class: 'muted', text: t('sec.totp.note') }), h('div', { class: 'form-actions start' }, go));
  }

  async function resetTotp(name, self) {
    const creds = await guarded('admin.create', 'totp:' + name, (id) => api.post('/admins/' + encodeURIComponent(name) + '/totp-reset', {}, { stepUp: id }));
    if (!creds) return;
    await showCredentials({ title: t('sec.totp.new.title', { name }), credentials: creds, intro: t('sec.totp.new.intro') });
    if (self) ctx.signedOut('totpReset');
  }

  // ------------------------------------------------------------------ administrators
  async function renderAdmins() {
    const host = h('div', { class: 'table-host' });
    mount(panel, h('div', { class: 'section-actions' }, button({ label: t('sec.admin.create'), icon: 'plus', variant: 'primary', on: { click: () => createAdmin() } })), host);
    await loadInto(host, {
      skeleton: 'table',
      rows: 3,
      load: () => api.get('/admins'),
      render: (admins) => {
        const table = createTable({
          ariaLabel: t('sec.admin.table'),
          rowId: (a) => a.username,
          rowLabel: (a) => a.username,
          columns: [
            { key: 'name', label: t('sec.admin.col.name'), render: (a) => h('span', { class: 'cell-stack' }, h('span', { class: 'cell-title', text: a.username }), h('span', { class: 'cell-sub', text: a.createdBy ? t('sec.admin.createdBy', { name: a.createdBy }) : t('sec.admin.firstAdmin') })) },
            { key: 'status', label: t('sec.admin.col.status'), render: (a) => (a.disabled ? badge(t('sec.admin.disabled'), 'bad') : badge(t('sec.admin.enabled'), 'ok')) },
            { key: 'key', label: t('sec.admin.col.key'), className: 'hide-sm', render: (a) => (a.enrolledDeviceId ? badge(t('sec.admin.key.yes'), 'ok') : badge(t('sec.admin.key.no'), 'warn')) },
            { key: 'codes', label: t('sec.admin.col.codes'), className: 'num hide-md', render: (a) => String(a.recoveryCodesRemaining) },
            { key: 'since', label: t('sec.admin.col.since'), className: 'hide-md', render: (a) => timeEl(a.createdAtMs) },
            { key: 'actions', label: t('sec.admin.col.actions'), render: (a) => adminActions(a) },
          ],
        });
        table.update({ rows: admins });
        return [admins.length === 1 ? banner({ tone: 'info', text: t('sec.admin.single') }) : null, table.el];
      },
    });
  }

  function adminActions(a) {
    const isMe = a.username.toLowerCase() === (session.user || '').toLowerCase();
    const row = h('div', { class: 'row-actions' });
    row.appendChild(button({ label: t('sec.admin.resetPw'), size: 'sm', variant: 'ghost', ariaLabel: t('sec.admin.resetPw.for', { name: a.username }), on: { click: () => resetPassword(a) } }));
    row.appendChild(button({ label: t('sec.admin.resetTotp'), size: 'sm', variant: 'ghost', ariaLabel: t('sec.admin.resetTotp.for', { name: a.username }), on: { click: () => confirmTotp(a, isMe) } }));
    if (!isMe) row.appendChild(button({ label: a.disabled ? t('sec.admin.enable') : t('sec.admin.disable'), size: 'sm', variant: a.disabled ? 'secondary' : 'ghost', ariaLabel: (a.disabled ? t('sec.admin.enable.for', { name: a.username }) : t('sec.admin.disable.for', { name: a.username })), on: { click: () => toggleAdmin(a) } }));
    return row;
  }

  async function confirmTotp(a, isMe) {
    const ok = await confirmDialog({ title: t('sec.totp.reset.title'), message: isMe ? t('sec.totp.reset.text') : t('sec.admin.resetTotp.text', { name: a.username }), confirmLabel: t('sec.totp.reset'), danger: true });
    if (ok) await resetTotp(a.username, isMe);
  }

  async function toggleAdmin(a) {
    const disabling = !a.disabled;
    const ok = await confirmDialog({
      title: disabling ? t('sec.admin.disable.title', { name: a.username }) : t('sec.admin.enable.title', { name: a.username }),
      message: disabling ? t('sec.admin.disable.text') : t('sec.admin.enable.text'),
      confirmLabel: disabling ? t('sec.admin.disable') : t('sec.admin.enable'),
      danger: disabling,
    });
    if (!ok) return;
    const res = await guarded('admin.disable', a.username, (id) => api.post('/admins/' + encodeURIComponent(a.username) + (disabling ? '/disable' : '/enable'), {}, { stepUp: id }));
    if (res) {
      toast(disabling ? t('sec.admin.disabled.done', { name: a.username }) : t('sec.admin.enabled.done', { name: a.username }), { kind: 'success' });
      renderAdmins();
    }
  }

  function resetPassword(a) {
    const pw = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    pw.setAttribute('data-autofocus', '');
    const pf = field({ label: t('sec.pw.new'), control: pw, hint: t('setup.password.hint'), required: true });
    const go = button({ label: t('sec.admin.resetPw'), variant: 'primary' });
    const dlg = openDialog({ title: t('sec.admin.resetPw.title', { name: a.username }), size: 'sm', body: [h('p', { class: 'muted', text: t('sec.admin.resetPw.text') }), pf.el], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go] });
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        pf.clearError();
        if (pw.value.length < 10) return pf.setError(t('setup.password.short'));
        const res = await guarded('admin.create', 'reset:' + a.username, (id) => api.post('/admins/' + encodeURIComponent(a.username) + '/password', { newPassword: pw.value }, { stepUp: id }));
        if (res) {
          dlg.close();
          toast(t('sec.admin.resetPw.done', { name: a.username }), { kind: 'success' });
        }
        return undefined;
      }),
    );
  }

  function createAdmin() {
    const name = textInput({ autocomplete: 'off', maxlength: 64 });
    const pw = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    const nf = field({ label: t('setup.username'), control: name, hint: t('setup.username.hint'), required: true });
    const pf = field({ label: t('sec.admin.create.password'), control: pw, hint: t('sec.admin.create.password.hint'), required: true });
    const go = button({ label: t('sec.admin.create.submit'), variant: 'primary' });
    const dlg = openDialog({ title: t('sec.admin.create'), size: 'sm', body: [banner({ tone: 'warn', text: t('sec.admin.create.warn') }), nf.el, pf.el], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go] });
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        nf.clearError();
        pf.clearError();
        const username = name.value.trim();
        if (!/^[A-Za-z0-9._@-]{3,64}$/.test(username)) return nf.setError(t('setup.username.invalid'));
        if (pw.value.length < 10) return pf.setError(t('setup.password.short'));
        let creds;
        try {
          creds = await guarded('admin.create', username, (id) => api.post('/admins', { username, password: pw.value }, { stepUp: id }), { rethrow: true });
        } catch (e) {
          if (e.status === 409 || e.status === 400) return nf.setError(e.message || t('error.generic'));
          return toastError(e);
        }
        if (creds) {
          dlg.close();
          await showCredentials({ title: t('sec.admin.created.title', { name: username }), credentials: creds, intro: t('sec.admin.created.intro', { name: username }) });
          renderAdmins();
        }
        return undefined;
      }),
    );
  }

  // ------------------------------------------------------------------ sessions
  async function renderSessions() {
    const host = h('div', { class: 'table-host' });
    mount(panel, host);
    await loadInto(host, {
      skeleton: 'table',
      rows: 3,
      load: () => api.get('/sessions'),
      render: (rows) => {
        if (!rows.length) return emptyState({ title: t('sec.sessions.empty'), glyph: 'user' });
        const table = createTable({
          ariaLabel: t('sec.sessions.table'),
          rowId: (r) => r.sessionId,
          rowLabel: (r) => r.username,
          columns: [
            { key: 'user', label: t('sec.sessions.col.user'), render: (r) => h('span', { class: 'inline' }, r.username, r.sessionId === session.sessionId ? badge(t('sec.sessions.this'), 'accent') : null) },
            { key: 'created', label: t('sec.sessions.col.created'), render: (r) => timeEl(r.createdAtMs) },
            { key: 'seen', label: t('sec.sessions.col.seen'), render: (r) => timeEl(r.lastSeenMs) },
            { key: 'expires', label: t('sec.sessions.col.expires'), className: 'hide-sm', render: (r) => timeEl(r.expiresAtMs) },
            { key: 'actions', label: t('sec.sessions.col.actions'), render: (r) => button({ label: r.sessionId === session.sessionId ? t('sec.sessions.signOut') : t('sec.sessions.revoke'), size: 'sm', variant: 'ghost', ariaLabel: t('sec.sessions.revoke.for', { name: r.username }), on: { click: () => revokeSession(r) } }) },
          ],
        });
        table.update({ rows });
        return [h('p', { class: 'muted', text: t('sec.sessions.note') }), table.el];
      },
    });
  }

  async function revokeSession(r) {
    const mine = r.sessionId === session.sessionId;
    const ok = await confirmDialog({ title: mine ? t('sec.sessions.signOut.title') : t('sec.sessions.revoke.title', { name: r.username }), message: mine ? t('sec.sessions.signOut.text') : t('sec.sessions.revoke.text'), confirmLabel: mine ? t('sec.sessions.signOut') : t('sec.sessions.revoke'), danger: true });
    if (!ok) return;
    try {
      await api.post('/sessions/' + encodeURIComponent(r.sessionId) + '/revoke', {});
      if (mine) ctx.signedOut('loggedOut');
      else {
        toast(t('sec.sessions.revoked'), { kind: 'success' });
        renderSessions();
      }
    } catch (e) {
      toastError(e);
    }
  }

  // ------------------------------------------------------------------ delegation
  async function renderDelegation() {
    const host = h('div');
    mount(panel, card({ title: t('sec.delegation.title'), subtitle: t('sec.delegation.sub'), id: 'sec-del' }, host));
    await loadInto(host, {
      load: () => api.get('/delegation'),
      render: (d) => delegationBody(d, { onChanged: () => { renderDelegation(); events.emit('delegation-changed'); } }),
    });
  }

  // ------------------------------------------------------------------ revocations
  async function renderRevocations() {
    const host = h('div', { class: 'stack' });
    mount(panel, host);
    await loadInto(host, {
      skeleton: 'table',
      rows: 4,
      load: () => api.get('/revocations'),
      render: (r) => {
        const s = r.status;
        const serial = textInput({ type: 'number', placeholder: t('sec.rev.serial.placeholder') });
        const sf = field({ label: t('sec.rev.serial'), control: serial, hint: t('sec.rev.serial.hint') });
        const go = button({ label: t('sec.rev.serial.submit'), variant: 'danger', size: 'sm' });
        go.addEventListener('click', () =>
          withBusy(go, async () => {
            sf.clearError();
            const n = Number.parseInt(serial.value, 10);
            if (!(n > 0)) return sf.setError(t('sec.rev.serial.invalid'));
            const ok = await confirmDialog({ title: t('sec.rev.serial.title', { n }), message: t('sec.rev.serial.text'), confirmLabel: t('sec.rev.serial.submit'), danger: true });
            if (!ok) return undefined;
            const res = await guarded('device.revoke', 'serial:' + n, (id) => api.post('/revocations/serial', { serial: n, reason: t('devices.revoke.reason.default') }, { stepUp: id }));
            if (res) {
              toast(res.ok ? t('sec.rev.serial.done') : t('sec.rev.serial.already'), { kind: 'success' });
              renderRevocations();
            }
            return undefined;
          }),
        );
        const table = r.entries.length
          ? createTable({
              ariaLabel: t('sec.rev.table'),
              rowId: (e) => e.kind + ':' + e.subject,
              rowLabel: (e) => e.subject,
              columns: [
                { key: 'kind', label: t('sec.rev.col.kind'), render: (e) => badge(t('sec.rev.kind.' + e.kind), 'neutral') },
                { key: 'subject', label: t('sec.rev.col.subject'), render: (e) => mono(shortId(e.subject, 16, 6)) },
                { key: 'reason', label: t('sec.rev.col.reason'), className: 'hide-sm', render: (e) => e.reason },
                { key: 'at', label: t('sec.rev.col.at'), render: (e) => timeEl(e.revokedAt) },
                { key: 'by', label: t('sec.rev.col.by'), className: 'hide-md', render: (e) => e.revokedBy },
              ],
            })
          : null;
        if (table) table.update({ rows: r.entries });
        return [
          dl([
            [t('sec.rev.version'), h('span', { class: 'num', text: String(s.listVersion) })],
            [t('sec.rev.count'), h('span', { class: 'num', text: t('sec.rev.count.v', { serials: s.revokedSerials, devices: s.revokedDeviceIds }) })],
            [t('sec.rev.signing'), s.canSign ? badge(t('sec.rev.signing.ok'), 'ok') : badge(t('sec.rev.signing.no'), 'warn')],
            [t('sec.rev.unpublished'), s.unpublishedChanges ? badge(t('sec.rev.unpublished.yes'), 'warn') : badge(t('sec.rev.unpublished.no'), 'ok')],
          ]),
          s.unpublishedChanges ? banner({ tone: 'warn', text: t('sec.rev.unpublished.note') }) : null,
          table ? table.el : emptyState({ title: t('sec.rev.empty.title'), text: t('sec.rev.empty.text'), glyph: 'security' }),
          h('div', { class: 'inline-form' }, sf.el, go),
        ];
      },
    });
  }

  // ------------------------------------------------------------------ trust
  async function renderTrust() {
    const host = h('div', { class: 'stack' });
    mount(panel, host);
    await loadInto(host, {
      load: async () => {
        const [d, s] = await Promise.all([api.get('/delegation'), api.get('/settings')]);
        return { d, s };
      },
      render: ({ d, s }) => {
        const fp = d.rootFingerprint;
        return [
          card(
            { title: t('sec.trust.root.title'), subtitle: t('sec.trust.root.sub'), id: 'sec-root' },
            fp
              ? h('div', { class: 'fp-row fp-large' }, renderFingerprint(fp, { label: t('devices.fingerprint.visual'), pixel: 14 }), h('div', { class: 'stack tight' }, mono(groupText(fp, 8), 'wrap'), copyButton({ text: fp, label: t('devices.fingerprint.copy') }), h('p', { class: 'muted small', text: t('sec.trust.root.compare') })))
              : banner({ tone: 'info', text: t('sec.trust.root.none') }),
          ),
          card(
            { title: t('sec.trust.hub.title'), id: 'sec-hubkey' },
            d.hubPublicKey ? h('div', { class: 'stack tight' }, mono(d.hubPublicKey, 'wrap'), copyButton({ text: d.hubPublicKey, label: t('sec.trust.hub.copy') })) : h('p', { class: 'muted', text: t('delegation.hubKey.missing') }),
          ),
          card(
            { title: t('sec.trust.net.title'), subtitle: t('sec.trust.net.sub'), id: 'sec-net' },
            dl([
              [t('sec.trust.net.url'), mono(s.externalUrl)],
              [t('sec.trust.net.tls'), badge(s.tlsMode, s.tlsMode === 'none' ? 'warn' : 'ok')],
              [t('sec.trust.net.port'), mono(String(s.port))],
              [t('sec.trust.net.admin'), s.adminOnPublicPort ? h('span', {}, mono(String(s.adminPort)), ' ', badge(t('sec.trust.net.public'), 'warn')) : h('span', {}, mono(String(s.adminPort)), ' ', badge(t('sec.trust.net.private'), 'ok'))],
              [t('sec.trust.net.proxies'), String(s.trustedProxies)],
              [t('sec.trust.net.insecure'), s.allowInsecure ? badge(t('sec.trust.net.insecure.on'), 'bad') : badge(t('sec.trust.net.insecure.off'), 'ok')],
            ]),
            s.allowInsecure ? banner({ tone: 'bad', text: t('sec.trust.net.insecure.warn') }) : null,
          ),
        ];
      },
    });
  }

  ready = true;
  renderTab();
  return {
    destroy() {
      stopped = true;
      setActiveTable(null);
    },
    onRoute(next) {
      ctx = { ...ctx, ...next };
      const wanted = TABS.includes(next.query.tab) ? next.query.tab : 'account';
      if (wanted !== current) tabBar.select(wanted);
    },
  };
}
