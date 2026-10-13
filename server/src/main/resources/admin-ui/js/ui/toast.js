// Toasts: a polite live region. Success fades after 5 s; an error stays until dismissed and carries the reference (correlation id).

import { h } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';

let region = null;

function ensureRegion() {
  if (region && region.isConnected) return region;
  region = h('div', { class: 'toasts', role: 'region', 'aria-label': t('toast.region') });
  globalThis.document.body.appendChild(region);
  return region;
}

/**
 * @param {string} message
 * @param {{kind?: 'success'|'error'|'info'|'warning', reference?: string, timeoutMs?: number}} [opts]
 */
export function toast(message, { kind = 'info', reference = '', timeoutMs } = {}) {
  const host = ensureRegion();
  const glyph = { success: 'success', error: 'error', warning: 'warning', info: 'info' }[kind];
  const dismiss = () => node.remove();
  const node = h(
    'div',
    { class: 'toast toast-' + kind, role: kind === 'error' ? 'alert' : 'status' },
    h('span', { class: 'toast-icon' }, icon(glyph)),
    h(
      'div',
      { class: 'toast-body' },
      h('div', { class: 'toast-message', text: message }),
      reference ? h('div', { class: 'toast-ref', text: t('error.reference', { id: reference }) }) : null,
    ),
    h('button', { class: 'icon-btn', type: 'button', 'aria-label': t('common.dismiss'), title: t('common.dismiss'), on: { click: dismiss } }, icon('close', { size: 16 })),
  );
  host.appendChild(node);
  const ms = timeoutMs ?? (kind === 'error' || kind === 'warning' ? 0 : 5000);
  if (ms > 0) setTimeout(dismiss, ms);
  return { dismiss };
}

export function toastError(err, fallback) {
  const message = err && err.message ? err.message : fallback || t('error.generic');
  toast(message, { kind: 'error', reference: err && err.correlationId ? err.correlationId : '' });
}
