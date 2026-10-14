// "Approve on your phone" (ADR-131 / FO-14). Shown while the console waits for the administrator's approver phone: it displays the six
// digits the phone shows for the same confirmation, so the administrator can check that the request on the phone is THIS one, and has
// a Cancel button. The step-up flow polls the hub; this dialog only shows and reports Cancel.

import { h } from '../dom.js';
import { t } from '../i18n.js';
import { openDialog } from './dialog.js';
import { button } from './widgets.js';

/**
 * @param {{code: string, action: string, target: string, expiresAtMs: number}} info
 * @returns {{cancelled: () => boolean, close: () => void}}
 */
export function showApprovalDialog(info) {
  let cancelled = false;
  let closing = false;
  const code = String(info.code || '');
  const shown = code.length === 6 ? code.slice(0, 3) + ' ' + code.slice(3) : code;
  const dlg = openDialog({
    title: t('approval.title'),
    size: 'sm',
    dismissible: true,
    body: [
      h('p', { text: t('approval.text') }),
      h('p', { class: 'approval-code', 'aria-label': t('approval.code.label'), text: shown }),
      h('p', { class: 'muted small', text: t('approval.hint') }),
      h('p', { class: 'muted small', role: 'status', 'aria-live': 'polite', text: t('approval.waiting') }),
    ],
    actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } })],
    onClose: () => {
      if (!closing) cancelled = true;
    },
  });
  return {
    cancelled: () => cancelled,
    close: () => {
      closing = true;
      dlg.close();
    },
  };
}
