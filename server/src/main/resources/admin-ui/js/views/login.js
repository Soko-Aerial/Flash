// Sign-in: username, password and the authenticator code (or a recovery code). Every failure says the same sentence for wrong user,
// wrong password or wrong code (the hub does not reveal which); a lockout shows a live countdown from Retry-After.

import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { api, rearmExpiry } from '../app.js';
import { ApiError } from '../api.js';
import { setSession } from '../state.js';
import { button, field, textInput, banner, withBusy } from '../ui/widgets.js';
import { cycleTheme } from '../theme.js';

export function authFrame({ title, subtitle, children, wide = false }) {
  return h(
    'div',
    { class: 'auth-page' },
    h(
      'main',
      { class: ['auth-card', wide ? 'auth-wide' : ''], id: 'main', tabindex: '-1' },
      h('div', { class: 'auth-brand' }, icon('bolt', { size: 30, className: 'brand-bolt' }), h('span', { class: 'brand-name', text: t('app.name') })),
      h('h1', { class: 'auth-title', id: 'page-title', tabindex: '-1', text: title }),
      subtitle ? h('p', { class: 'auth-sub', text: subtitle }) : null,
      children,
    ),
    h('button', { class: 'auth-theme icon-btn', type: 'button', 'aria-label': t('theme.cycle'), title: t('theme.cycle'), on: { click: () => cycleTheme() } }, icon('sun')),
  );
}

/**
 * @param {Element} container
 * @param {{notice?: 'expired'|'idle'|'loggedOut'|'passwordChanged'|'totpReset', setupOpen: boolean, onSignedIn: () => void, goto: (route: string) => void}} ctx
 */
export function mountLogin(container, ctx) {
  let recovery = false;
  let lockTimer = 0;
  const user = textInput({ autocomplete: 'username', name: 'username', maxlength: 64 });
  const pass = textInput({ type: 'password', autocomplete: 'current-password', name: 'password', maxlength: 256 });
  const code = textInput({ autocomplete: 'one-time-code', inputmode: 'numeric', maxlength: 24, className: 'input-code' });
  const userField = field({ label: t('login.username'), control: user, required: true });
  const passField = field({ label: t('login.password'), control: pass, required: true });
  const codeField = field({ label: t('login.code'), control: code, hint: t('login.code.hint'), required: true });
  const message = h('div', { class: 'form-message', 'aria-live': 'polite' });
  const submit = button({ label: t('login.submit'), variant: 'primary', type: 'submit', className: 'btn-block' });
  const toggle = h('button', { class: 'link-btn plain', type: 'button', on: { click: switchMode } }, t('login.useRecovery'));

  function switchMode() {
    recovery = !recovery;
    const label = codeField.el.querySelector('label');
    label.firstChild.textContent = recovery ? t('login.recoveryCode') : t('login.code');
    codeField.el.querySelector('.hint').textContent = recovery ? t('login.recoveryCode.hint') : t('login.code.hint');
    code.inputMode = recovery ? 'text' : 'numeric';
    code.maxLength = recovery ? 32 : 24;
    code.value = '';
    toggle.textContent = recovery ? t('login.useAuthenticator') : t('login.useRecovery');
    code.focus();
  }

  const form = h('form', { class: 'stack', novalidate: true, autocomplete: 'on' }, userField.el, passField.el, codeField.el, message, submit, h('div', { class: 'auth-links' }, toggle));

  function showMessage(tone, text, reference) {
    mount(message, banner({ tone, text, actions: reference ? h('span', { class: 'ref', text: t('error.reference', { id: reference }) }) : null }));
  }

  function lock(seconds) {
    clearInterval(lockTimer);
    const until = Date.now() + seconds * 1000;
    submit.disabled = true;
    const tick = () => {
      const left = Math.ceil((until - Date.now()) / 1000);
      if (left <= 0) {
        clearInterval(lockTimer);
        submit.disabled = false;
        mount(message);
        return;
      }
      mount(message, banner({ tone: 'warn', text: t('login.locked', { seconds: left }) }));
    };
    tick();
    lockTimer = setInterval(tick, 1000);
  }

  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    mount(message);
    for (const f of [userField, passField, codeField]) f.clearError();
    let bad = null;
    if (!user.value.trim()) {
      userField.setError(t('login.username.required'));
      bad = bad || user;
    }
    if (!pass.value) {
      passField.setError(t('login.password.required'));
      bad = bad || pass;
    }
    if (!code.value.trim()) {
      codeField.setError(t('login.code.required'));
      bad = bad || code;
    }
    if (bad) return bad.focus();
    await withBusy(submit, async () => {
      try {
        await api.post('/login', { username: user.value.trim(), password: pass.value, code: code.value.replace(/\s+/g, '') }, { anonymous: true });
        pass.value = '';
        code.value = '';
        const dto = await api.get('/session');
        rearmExpiry();
        setSession(dto);
        ctx.onSignedIn();
      } catch (e) {
        if (e instanceof ApiError && e.status === 429) {
          lock(e.retryAfter || 30);
        } else if (e instanceof ApiError && e.status === 401) {
          showMessage('bad', t('login.failed'), e.correlationId);
          code.value = '';
          pass.focus();
        } else if (e instanceof ApiError && e.code === 'ERR_SETUP_REQUIRED') {
          showMessage('warn', t('login.setupRequired'), e.correlationId);
        } else {
          showMessage('bad', (e && e.message) || t('error.generic'), e && e.correlationId);
        }
      }
    });
  });
  form.addEventListener('keydown', (ev) => {
    if (ev.key === 'Enter' && ev.ctrlKey) form.requestSubmit();
  });

  const notice = ctx.notice ? banner({ tone: ctx.notice === 'loggedOut' || ctx.notice === 'passwordChanged' ? 'ok' : 'info', text: t('login.notice.' + ctx.notice) }) : null;
  const setup = ctx.setupOpen
    ? h('p', { class: 'auth-foot' }, t('login.firstTime'), ' ', h('a', { href: '#/setup', text: t('login.startSetup') }))
    : null;

  mount(
    container,
    authFrame({
      title: t('login.title'),
      subtitle: t('login.subtitle'),
      children: [notice, form, setup],
    }),
  );
  (user.value ? pass : user).focus();
  return () => clearInterval(lockTimer);
}
