import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';

export function mountLogin(container, ctx) {
  let recovery = false;
  const message = h('p', { class: 'form-message field-error', role: 'alert' });

  const username = h('input', { class: 'input', name: 'username', autocomplete: 'username', placeholder: 'Your username', 'aria-label': 'Username' });
  const password = h('input', { class: 'input', name: 'password', type: 'password', autocomplete: 'current-password', placeholder: 'Your password', 'aria-label': 'Password' });
  const code = h('input', { class: 'input', name: 'code', inputmode: 'numeric', autocomplete: 'one-time-code', placeholder: '6-digit code', 'aria-label': 'Authenticator code' });
  const recoveryCode = h('input', { class: 'input', name: 'recovery', autocomplete: 'off', placeholder: 'Recovery code', 'aria-label': 'Recovery code' });

  const submit = h('button', { class: 'btn btn-primary btn-block', type: 'submit' }, 'Sign in');
  const toggle = h('button', {
    class: 'text-action', type: 'button',
    on: {
      click: () => {
        recovery = !recovery;
        toggle.textContent = recovery ? 'Use an authenticator code' : 'Use a recovery code';
        codeField.hidden = recovery;
        recoveryField.hidden = !recovery;
        message.textContent = '';
        (recovery ? recoveryCode : code).focus();
      },
    },
  }, 'Use a recovery code');

  const codeField = h('label', { class: 'field' },
    h('span', { class: 'label', text: 'Authenticator code' }),
    code,
  );
  const recoveryField = h('label', { class: 'field', hidden: true },
    h('span', { class: 'label', text: 'Recovery code' }),
    recoveryCode,
    h('span', { class: 'hint', text: 'One of the single-use codes you saved when the account was created.' }),
  );

  const form = h('form', {
    class: 'auth-form',
    on: {
      submit: (event) => {
        event.preventDefault();
        message.textContent = '';
        if (!username.value.trim() || !password.value) {
          message.textContent = 'Enter your username and password.';
          (username.value.trim() ? password : username).focus();
          return;
        }
        const secret = recovery ? recoveryCode.value.trim() : code.value.trim();
        if (!secret) {
          message.textContent = recovery ? 'Enter one of your recovery codes.' : 'Enter the six-digit code from your authenticator.';
          (recovery ? recoveryCode : code).focus();
          return;
        }
        submit.disabled = true;
        submit.classList.add('is-busy');
        submit.textContent = '';
        submit.append('Signing in');
        setTimeout(() => ctx.go('overview'), 620);
      },
    },
  },
    message,
    h('label', { class: 'field' }, h('span', { class: 'label', text: 'Username' }), username),
    h('label', { class: 'field' }, h('span', { class: 'label', text: 'Password' }), password),
    codeField,
    recoveryField,
    submit,
  );

  const themeBtn = ctx.themeButton();
  const auth = h('div', { class: 'auth-shell' },
    h('div', { class: 'auth-theme' }, themeBtn),
    h('div', { class: 'auth-brand' },
      h('span', { class: 'brand-mark', style: { width: '30px', height: '30px' } }, icon('bolt', { size: 22 })),
      h('div', {},
        h('div', { class: 'brand-name', text: 'Flash Hub' }),
        h('div', { class: 'brand-org', text: `${data.hub.org} · ${data.hub.site}` }),
      ),
    ),
    h('div', { class: 'hub-line auth-hub-line', 'aria-hidden': 'true' }),
    h('section', { class: 'auth-card', 'aria-labelledby': 'auth-title' },
      h('h1', { class: 'auth-title', id: 'auth-title', text: 'Sign in' }),
      h('p', { class: 'auth-sub', text: 'Administration console · this hub only' }),
      form,
      h('div', { class: 'auth-links' },
        toggle,
        h('span', { class: 'faint small', text: 'Session lasts 30 minutes' }),
      ),
      h('p', { class: 'auth-note', text: 'This page loads nothing from outside your network. If you are unsure of the address, close it and use the link your operator gave you.' }),
    ),
    h('p', { class: 'auth-foot', text: `${data.hub.address} · version ${data.hub.version}` }),
  );

  mount(container, auth);
  username.focus();
}
