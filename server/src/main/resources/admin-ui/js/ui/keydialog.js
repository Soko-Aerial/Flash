// "Set up confirmation on this browser": registers this browser's step-up key (ADR-125). Needs a fresh authenticator code, because a stolen
// session cookie alone must not be able to install an attacker's key. The code must be one the hub has not accepted a moment ago, which
// is why the hint says to wait for the next one.

import { h } from '../dom.js';
import { t } from '../i18n.js';
import { openDialog } from './dialog.js';
import { button, field, textInput, banner, withBusy } from './widgets.js';
import { ApiError } from '../api.js';
import { StepUpError } from '../stepup.js';
import { session } from '../state.js';
import { toast } from './toast.js';

/** @returns {Promise<boolean>} true when a key was registered */
export function askToEnrol(reason, stepUp) {
  return new Promise((resolve) => {
    const intro = {
      'no-key': t('keydialog.intro.noKey'),
      'key-mismatch': t('keydialog.intro.mismatch'),
      'no-server-key': t('keydialog.intro.noServerKey'),
    }[reason] || t('keydialog.intro.noKey');
    const code = textInput({ inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 8, className: 'input-code' });
    code.setAttribute('data-autofocus', '');
    const codeField = field({ label: t('keydialog.code'), control: code, hint: t('keydialog.code.hint'), required: true });
    const label = textInput({ value: guessLabel(), maxlength: 64 });
    const labelField = field({ label: t('keydialog.label'), control: label, hint: t('keydialog.label.hint') });
    const form = h('form', { class: 'stack', novalidate: true }, codeField.el, labelField.el);
    const submit = button({ label: t('keydialog.submit'), variant: 'primary', type: 'submit' });

    const dlg = openDialog({
      title: t('keydialog.title'),
      size: 'sm',
      body: [h('p', { text: intro }), h('p', { class: 'muted', text: t('keydialog.why') }), form],
      actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close(false) } }), submit],
      onClose: (r) => resolve(r === true),
    });
    for (const input of [code, label]) {
      input.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter') {
          ev.preventDefault();
          form.requestSubmit();
        }
      });
    }
    submit.addEventListener('click', (ev) => {
      ev.preventDefault();
      form.requestSubmit();
    });
    form.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      codeField.clearError();
      const value = code.value.replace(/\s+/g, '');
      if (!/^\d{6}$/.test(value) && !/^[A-Za-z0-9-]{8,}$/.test(value)) {
        codeField.setError(t('keydialog.code.invalid'));
        code.focus();
        return;
      }
      await withBusy(submit, async () => {
        try {
          await stepUp.enrol({ username: session.user, code: value, label: label.value.trim() });
          toast(t('keydialog.done'), { kind: 'success' });
          dlg.close(true);
        } catch (e) {
          if (e instanceof StepUpError) codeField.setError(t('stepup.approval.' + (e.reason === 'approval-timeout' ? 'timeout' : e.reason === 'cancelled' ? 'cancelled' : e.reason === 'approver-unavailable' ? 'unavailable' : 'none')));
          else if (e instanceof ApiError && e.code === 'ERR_SIGNER_EXISTS') codeField.setError(t('keydialog.code.needRecovery'));
          else if (e instanceof ApiError && e.status === 403) codeField.setError(t('keydialog.code.refused'));
          else if (e instanceof ApiError && e.status === 429) codeField.setError(t('error.rate.wait', { seconds: e.retryAfter || 30 }));
          else codeField.setError((e && e.message) || t('error.generic'));
          code.focus();
          code.select();
        }
      });
    });
    if (!stepUp.supported()) {
      dlg.setBody(banner({ tone: 'warn', title: t('stepup.unsupported.title'), text: t('stepup.unsupported') }));
    }
  });
}

function guessLabel() {
  const ua = (globalThis.navigator && globalThis.navigator.userAgent) || '';
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : '';
  return (browser + (os ? ' on ' + os : '')).slice(0, 64);
}
