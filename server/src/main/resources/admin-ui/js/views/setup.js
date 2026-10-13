// First-run setup wizard: setup token -> account -> authenticator (QR + manual key) -> verify code -> recovery codes (shown once) ->
// done. The browser's step-up key is generated at the verify step and registered in the same request that creates the administrator.

import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { ApiError } from '../api.js';
import { renderQr } from '../qr.js';
import { groupText } from '../format.js';
import * as keys from '../stepup-key.js';
import { button, field, textInput, banner, withBusy, copyButton, skeleton, mono } from '../ui/widgets.js';
import { authFrame } from './login.js';

const STEPS = ['token', 'account', 'authenticator', 'recovery', 'done'];
const USERNAME = /^[A-Za-z0-9._@-]{3,64}$/;

function stepper(current) {
  return h(
    'ol',
    { class: 'stepper', 'aria-label': t('setup.steps') },
    STEPS.map((id, i) => {
      const state = i < STEPS.indexOf(current) ? 'done' : id === current ? 'current' : 'todo';
      return h(
        'li',
        { class: 'step step-' + state, 'aria-current': state === 'current' ? 'step' : null },
        h('span', { class: 'step-dot' }, state === 'done' ? icon('check', { size: 14 }) : String(i + 1)),
        h('span', { class: 'step-name', text: t('setup.step.' + id) }),
      );
    }),
  );
}

export function downloadText(filename, text) {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: filename, class: 'sr-only' });
  globalThis.document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

/**
 * @param {Element} container
 * @param {{goto: (route: string) => void}} ctx
 */
export function mountSetup(container, ctx) {
  const data = { token: '', username: '', password: '', begin: null, recoveryCodes: [], keyRegistered: false, keyNote: '' };
  let step = 'token';

  function frame(title, subtitle, ...body) {
    mount(container, authFrame({ title, subtitle, wide: true, children: [stepper(step), ...body] }));
    const heading = container.querySelector('#page-title');
    if (heading) heading.focus();
  }

  function go(next) {
    step = next;
    render();
  }

  function render() {
    ({ token: stepToken, account: stepAccount, authenticator: stepAuthenticator, recovery: stepRecovery, done: stepDone })[step]();
  }

  // ---------------------------------------------------------------------------------------------- token
  function stepToken() {
    const token = textInput({ type: 'password', autocomplete: 'off', maxlength: 256, value: data.token });
    const f = field({ label: t('setup.token'), control: token, hint: t('setup.token.hint'), required: true });
    const next = button({ label: t('common.next'), variant: 'primary', type: 'submit' });
    const form = h('form', { class: 'stack', novalidate: true }, f.el, h('div', { class: 'form-actions' }, next));
    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      f.clearError();
      if (!token.value.trim()) {
        f.setError(t('setup.token.required'));
        return token.focus();
      }
      data.token = token.value.trim();
      go('account');
    });
    frame(t('setup.token.title'), t('setup.token.sub'), form);
    token.focus();
  }

  // ---------------------------------------------------------------------------------------------- account
  function stepAccount() {
    const user = textInput({ autocomplete: 'username', maxlength: 64, value: data.username });
    const pass = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    const again = textInput({ type: 'password', autocomplete: 'new-password', maxlength: 256 });
    const uf = field({ label: t('setup.username'), control: user, hint: t('setup.username.hint'), required: true });
    const pf = field({ label: t('setup.password'), control: pass, hint: t('setup.password.hint'), required: true });
    const af = field({ label: t('setup.password2'), control: again, required: true });
    const back = button({ label: t('common.back'), variant: 'ghost', on: { click: () => go('token') } });
    const next = button({ label: t('common.next'), variant: 'primary', type: 'submit' });
    const form = h('form', { class: 'stack', novalidate: true }, uf.el, pf.el, af.el, h('div', { class: 'form-actions' }, back, next));
    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      for (const f of [uf, pf, af]) f.clearError();
      const name = user.value.trim();
      let bad = null;
      if (!USERNAME.test(name)) {
        uf.setError(t('setup.username.invalid'));
        bad = bad || user;
      }
      if (pass.value.length < 10) {
        pf.setError(t('setup.password.short'));
        bad = bad || pass;
      }
      if (pass.value !== again.value) {
        af.setError(t('setup.password.mismatch'));
        bad = bad || again;
      }
      if (bad) return bad.focus();
      data.username = name;
      data.password = pass.value;
      data.begin = null;
      go('authenticator');
    });
    frame(t('setup.account.title'), t('setup.account.sub'), form);
    user.focus();
  }

  // ---------------------------------------------------------------------------------------------- authenticator
  async function stepAuthenticator() {
    const body = h('div', { class: 'stack' }, skeleton('lines', 4));
    frame(t('setup.auth.title'), t('setup.auth.sub'), body);
    try {
      if (!data.begin) data.begin = await api.post('/setup/begin', { token: data.token }, { anonymous: true });
    } catch (e) {
      mount(body, banner({ tone: 'bad', text: (e && e.message) || t('error.generic') }), h('div', { class: 'form-actions' }, button({ label: t('common.back'), variant: 'ghost', on: { click: () => go('account') } }), button({ label: t('common.tryAgain'), variant: 'primary', on: { click: () => go('authenticator') } })));
      return;
    }
    const qr = renderQr(data.begin.qrSvg, { label: t('setup.auth.qrLabel'), className: 'qr qr-lg' });
    const code = textInput({ inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 8, className: 'input-code' });
    const cf = field({ label: t('setup.auth.code'), control: code, hint: t('setup.auth.code.hint'), required: true });
    const message = h('div', { 'aria-live': 'polite' });
    const back = button({ label: t('common.back'), variant: 'ghost', on: { click: () => go('account') } });
    const submit = button({ label: t('setup.auth.submit'), variant: 'primary', type: 'submit' });
    const secret = data.begin.totpSecret;
    const form = h('form', { class: 'stack', novalidate: true }, cf.el, message, h('div', { class: 'form-actions' }, back, submit));
    const supported = keys.isSupported();
    mount(
      body,
      h(
        'div',
        { class: 'setup-auth' },
        h('div', { class: 'qr-block' }, qr || h('p', { class: 'muted', text: t('setup.auth.noQr') })),
        h(
          'div',
          { class: 'stack' },
          h('p', { text: t('setup.auth.scan') }),
          h('div', { class: 'secret-box' }, h('div', { class: 'secret-label', text: t('setup.auth.manual') }), mono(groupText(secret), 'secret'), copyButton({ text: secret, label: t('setup.auth.copyKey') })),
        ),
      ),
      supported ? banner({ tone: 'info', text: t('setup.auth.keyNote') }) : banner({ tone: 'warn', title: t('stepup.unsupported.title'), text: t('stepup.unsupported') }),
      form,
    );
    form.addEventListener('submit', async (ev) => {
      ev.preventDefault();
      cf.clearError();
      mount(message);
      const value = code.value.replace(/\s+/g, '');
      if (!/^\d{6}$/.test(value)) {
        cf.setError(t('setup.auth.code.invalid'));
        return code.focus();
      }
      await withBusy(submit, async () => {
        let made = null;
        try {
          const payload = { setupId: data.begin.setupId, token: data.token, username: data.username, password: data.password, confirmCode: value };
          if (supported) {
            made = await keys.createKey(data.username, t('setup.keyLabel'));
            payload.consoleKey = { publicKey: made.publicKey, proof: made.proof, label: t('setup.keyLabel') };
          }
          const res = await api.post('/setup/complete', payload, { anonymous: true });
          data.recoveryCodes = res.recoveryCodes || [];
          data.password = '';
          if (made) {
            try {
              await keys.saveKey(made.record);
              data.keyRegistered = true;
            } catch (e) {
              data.keyNote = t('setup.done.keyNotStored');
            }
          } else data.keyNote = t('setup.done.keyUnsupported');
          go('recovery');
        } catch (e) {
          handleCompleteError(e, cf, code, message);
        }
      });
    });
    code.focus();
  }

  function handleCompleteError(e, cf, code, message) {
    if (e instanceof ApiError && e.status === 403) {
      mount(message, banner({ tone: 'bad', text: t('setup.err.token'), actions: button({ label: t('setup.err.retoken'), size: 'sm', on: { click: () => { data.token = ''; go('token'); } } }) }));
    } else if (e instanceof ApiError && e.status === 409) {
      data.begin = null;
      mount(message, banner({ tone: 'warn', text: t('setup.err.expired'), actions: button({ label: t('common.tryAgain'), size: 'sm', on: { click: () => go('authenticator') } }) }));
    } else if (e instanceof ApiError && e.status === 400) {
      cf.setError(t('setup.err.bad'));
      code.focus();
    } else if (e instanceof ApiError && e.status === 429) {
      mount(message, banner({ tone: 'warn', text: t('error.rate.wait', { seconds: e.retryAfter || 30 }) }));
    } else {
      mount(message, banner({ tone: 'bad', text: (e && e.message) || t('error.generic'), actions: e && e.correlationId ? h('span', { class: 'ref', text: t('error.reference', { id: e.correlationId }) }) : null }));
    }
  }

  // ---------------------------------------------------------------------------------------------- recovery codes
  function stepRecovery() {
    const list = h('ul', { class: 'recovery-codes', 'aria-label': t('setup.recovery.list') }, data.recoveryCodes.map((c) => h('li', {}, mono(c))));
    const ack = h('input', { type: 'checkbox', class: 'check', id: 'ack-saved' });
    const next = button({ label: t('common.continue'), variant: 'primary', disabled: true, on: { click: () => go('done') } });
    ack.addEventListener('change', () => {
      next.disabled = !ack.checked;
    });
    const text = data.recoveryCodes.join('\n');
    frame(
      t('setup.recovery.title'),
      t('setup.recovery.sub'),
      banner({ tone: 'warn', title: t('setup.recovery.once'), text: t('setup.recovery.warn') }),
      list,
      h('div', { class: 'form-actions start' }, copyButton({ text, label: t('setup.recovery.copy'), small: false }), button({ label: t('setup.recovery.download'), icon: 'download', on: { click: () => downloadText('flash-hub-recovery-codes.txt', t('setup.recovery.fileHeader', { user: data.username }) + '\n\n' + text + '\n') } })),
      h('label', { class: 'check-row', for: 'ack-saved' }, ack, h('span', { text: t('setup.recovery.ack') })),
      h('div', { class: 'form-actions' }, next),
    );
  }

  // ---------------------------------------------------------------------------------------------- done
  function stepDone() {
    data.recoveryCodes = [];
    frame(
      t('setup.done.title'),
      t('setup.done.sub'),
      banner({ tone: data.keyRegistered ? 'ok' : 'warn', text: data.keyRegistered ? t('setup.done.keyOk') : data.keyNote || t('setup.done.keyUnsupported') }),
      banner({ tone: 'info', title: t('setup.done.delegation.title'), text: t('setup.done.delegation') }),
      h('div', { class: 'form-actions' }, button({ label: t('setup.done.signin'), variant: 'primary', on: { click: () => ctx.goto('login') } })),
    );
  }

  render();
  return () => {};
}
