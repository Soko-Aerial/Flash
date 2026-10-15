// The delegation panel, shared by Overview (a short card) and Security (the full panel). While the hub is awaiting its delegation it shows
// the exact `keytool issue-delegation` command with this hub's public key and a file/paste installer; afterwards the status, window,
// scopes and device limit with Reload and Replace.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api, guarded } from '../app.js';
import { formatDate, formatNumber, groupText } from '../format.js';
import { button, banner, badge, dl, codeBlock, copyButton, mono, field, withBusy, timeEl } from '../ui/widgets.js';
import { toast, toastError } from '../ui/toast.js';
import { openDialog } from '../ui/dialog.js';

export function keytoolCommand(hubPublicKey) {
  return [
    'server keytool issue-delegation \\',
    '  --root-key ./root/root-identity.pk8 \\',
    '  --server-pub ' + (hubPublicKey || '<hub public key>') + ' \\',
    '  --scopes enrol:member,enrol:dept_admin,revoke:member \\',
    '  --valid-days 90 --max-devices 500 \\',
    '  --out delegation.cert',
  ].join('\n');
}

export function delegationTone(d) {
  if (d.status === 'AWAITING') return 'warn';
  if (d.status === 'EXPIRED' || (d.daysLeft !== null && d.daysLeft !== undefined && d.daysLeft < 0)) return 'bad';
  if (d.daysLeft !== null && d.daysLeft !== undefined && d.daysLeft < 14) return 'warn';
  return 'ok';
}

export function delegationBadge(d) {
  if (d.status === 'AWAITING') return badge(t('delegation.status.awaiting'), 'warn');
  if (d.status === 'EXPIRED') return badge(t('delegation.status.expired'), 'bad');
  return badge(t('delegation.status.active'), delegationTone(d));
}

/** The "awaiting delegation" explanation with the command and the installer. */
export function awaitingPanel(d, { onChanged, compact = false }) {
  return h(
    'div',
    { class: 'stack' },
    banner({ tone: 'warn', title: t('delegation.awaiting.title'), text: t('delegation.awaiting.text') }),
    h('ol', { class: 'plain-steps' }, [1, 2, 3].map((n) => h('li', { text: t('delegation.awaiting.step' + n) }))),
    codeBlock(keytoolCommand(d.hubPublicKey), { label: t('delegation.command') }),
    d.hubPublicKey ? h('p', { class: 'muted small', text: t('delegation.hubKey.note') }) : banner({ tone: 'info', text: t('delegation.hubKey.missing') }),
    compact ? null : installPanel({ onChanged }),
  );
}

export function installPanel({ onChanged }) {
  const area = h('textarea', { class: 'input textarea mono', rows: '6', spellcheck: 'false', autocomplete: 'off', 'aria-label': t('delegation.paste') });
  const f = field({ label: t('delegation.paste'), control: area, hint: t('delegation.paste.hint') });
  const file = h('input', { type: 'file', class: 'file-input', accept: '.cert,.json,application/json,text/plain', 'aria-label': t('delegation.file') });
  file.addEventListener('change', async () => {
    const chosen = file.files && file.files[0];
    if (!chosen) return;
    if (chosen.size > 16 * 1024) {
      f.setError(t('delegation.file.big'));
      return;
    }
    area.value = await chosen.text();
    f.clearError();
  });
  const install = button({ label: t('delegation.install'), variant: 'primary', icon: 'upload' });
  install.addEventListener('click', () => {
    f.clearError();
    let cert;
    try {
      cert = JSON.parse(area.value);
      if (!cert || typeof cert !== 'object' || cert.serial === undefined) throw new Error('shape');
    } catch (e) {
      f.setError(t('delegation.invalid'));
      return area.focus();
    }
    withBusy(install, async () => {
      const res = await guarded('delegation.install', 'serial:' + cert.serial, (id) => api.post('/delegation', cert, { stepUp: id }), { rethrow: true }).catch((e) => {
        if (e && e.status === 409) f.setError(t('delegation.rejected'));
        else toastError(e);
        return undefined;
      });
      if (res) {
        toast(t('delegation.installed'), { kind: 'success' });
        if (onChanged) onChanged(res);
      }
    });
  });
  return h('div', { class: 'stack install-panel' }, h('h3', { class: 'subhead', text: t('delegation.install.title') }), file, f.el, h('div', { class: 'form-actions start' }, install));
}

/** The details of an installed delegation. */
export function activePanel(d, { onChanged }) {
  const rows = [
    [t('delegation.field.status'), delegationBadge(d)],
    [t('delegation.field.validTo'), d.validTo ? h('span', {}, formatDate(d.validTo), ' ', h('span', { class: 'muted', text: d.daysLeft !== null && d.daysLeft !== undefined ? '(' + t('delegation.daysLeft', { n: d.daysLeft }) + ')' : '' })) : null],
    [t('delegation.field.validFrom'), d.validFrom ? formatDate(d.validFrom) : null],
    [t('delegation.field.serial'), d.serial !== null && d.serial !== undefined ? mono(String(d.serial)) : null],
    [t('delegation.field.maxDevices'), d.maxDevices ? formatNumber(d.maxDevices) : null],
    [t('delegation.field.scopes'), d.scopes && d.scopes.length ? h('span', { class: 'chips' }, d.scopes.map((s) => badge(s, 'neutral'))) : null],
    [t('delegation.field.root'), d.rootFingerprint ? mono(groupText(d.rootFingerprint, 8), 'wrap') : null],
  ];
  const reload = button({ label: t('delegation.reload'), icon: 'refresh', size: 'sm' });
  reload.addEventListener('click', () =>
    withBusy(reload, async () => {
      const res = await guarded('delegation.reload', 'delegation', (id) => api.post('/delegation/reload', {}, { stepUp: id }));
      if (res) {
        toast(t('delegation.reloaded'), { kind: 'success' });
        if (onChanged) onChanged(res);
      }
    }),
  );
  const replace = button({ label: t('delegation.replace'), icon: 'upload', size: 'sm', variant: 'secondary' });
  replace.addEventListener('click', () => {
    const dlg = openDialog({
      title: t('delegation.replace.title'),
      body: [h('p', { class: 'muted', text: t('delegation.replace.text') }), installPanel({ onChanged: (res) => { dlg.close(); if (onChanged) onChanged(res); } })],
      actions: [button({ label: t('common.close'), variant: 'ghost', on: { click: () => dlg.close() } })],
    });
  });
  return h(
    'div',
    { class: 'stack' },
    d.warning ? banner({ tone: delegationTone(d) === 'bad' ? 'bad' : 'warn', text: d.warning }) : null,
    d.error ? banner({ tone: 'bad', title: t('delegation.error'), text: d.error }) : null,
    dl(rows),
    h('div', { class: 'form-actions start' }, reload, replace, copyButton({ text: keytoolCommand(d.hubPublicKey), label: t('delegation.copyCommand') })),
  );
}

export function delegationBody(d, opts) {
  return d.status === 'AWAITING' ? awaitingPanel(d, opts) : activePanel(d, opts);
}

export function renderInto(container, d, opts) {
  mount(container, delegationBody(d, opts));
}

export { timeEl };
