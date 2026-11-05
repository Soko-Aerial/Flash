// Shared floating UI: toasts, dialogs, drawers, help, confirmations, copy.

import { h, mount, copyText } from './dom.js';
import { icon } from './icons.js';
import { data } from './data.js';

let toastHost = null;

export function toast(message, kind = 'ok') {
  if (!toastHost) {
    toastHost = h('div', { class: 'toasts', role: 'status', 'aria-live': 'polite' });
    document.body.append(toastHost);
  }
  const el = h(
    'div',
    { class: `toast toast-${kind}` },
    h('span', { class: 'toast-icon' }, icon(kind === 'bad' ? 'alert' : kind === 'ok' ? 'check' : 'info', { size: 16 })),
    h('span', { class: 'toast-body', text: message }),
  );
  const closeBtn = h('button', { class: 'icon-btn toast-close', type: 'button', 'aria-label': 'Dismiss', on: { click: () => dismiss() } }, icon('close', { size: 14 }));
  el.append(closeBtn);
  toastHost.append(el);
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  let dismissed = false;
  function dismiss() {
    if (dismissed) return;
    dismissed = true;
    el.classList.add('is-leaving');
    setTimeout(() => el.remove(), reduced ? 0 : 150);
  }
  setTimeout(dismiss, 4600);
  return el;
}

function wireClose(dialog) {
  const nativeClose = dialog.close.bind(dialog);
  dialog.close = () => {
    if (dialog.classList.contains('is-closing')) return;
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
      nativeClose();
      return;
    }
    dialog.classList.add('is-closing');
    setTimeout(() => {
      dialog.classList.remove('is-closing');
      nativeClose();
    }, 140);
  };
  dialog.addEventListener('cancel', (event) => {
    event.preventDefault();
    dialog.close();
  });
}

function closeOnBackdrop(dialog) {
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) dialog.close();
  });
}

export function openDialog({ title, body, footer, size = '', onClose = null }) {
  const titleId = `dlg-${Math.random().toString(36).slice(2, 8)}`;
  const closeBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', on: { click: () => dialog.close() } }, icon('close', { size: 18 }));
  const panel = h(
    'div',
    { class: 'dialog-panel' },
    h('div', { class: 'dialog-head' },
      h('div', { class: 'dialog-title', id: titleId, text: title }),
      closeBtn,
    ),
    h('div', { class: 'dialog-body' }, body),
    footer ? h('div', { class: 'dialog-foot' }, footer) : null,
  );
  const dialog = h('dialog', { class: `dialog ${size}`, 'aria-labelledby': titleId }, panel);
  document.body.append(dialog);
  closeOnBackdrop(dialog);
  dialog.addEventListener('close', () => {
    dialog.remove();
    if (onClose) onClose();
  });
  wireClose(dialog);
  dialog.showModal();
  return dialog;
}

export function openDrawer({ title, body, footer, onClose = null }) {
  const titleId = `drawer-${Math.random().toString(36).slice(2, 8)}`;
  const closeBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Close', on: { click: () => dialog.close() } }, icon('close', { size: 18 }));
  const panel = h(
    'div',
    { class: 'dialog-panel' },
    h('div', { class: 'drawer-head' },
      h('div', { class: 'dialog-title', id: titleId, text: title }),
      closeBtn,
    ),
    h('div', { class: 'drawer-body' }, body),
    footer ? h('div', { class: 'drawer-foot' }, footer) : null,
  );
  const dialog = h('dialog', { class: 'drawer', 'aria-labelledby': titleId }, panel);
  document.body.append(dialog);
  closeOnBackdrop(dialog);
  dialog.addEventListener('close', () => {
    dialog.remove();
    if (onClose) onClose();
  });
  wireClose(dialog);
  dialog.showModal();
  return dialog;
}

export function copyButton(value, label = 'Copy') {
  return h(
    'button',
    {
      class: 'icon-btn',
      type: 'button',
      'aria-label': label,
      title: label,
      on: {
        click: async () => {
          const ok = await copyText(value);
          toast(ok ? 'Copied to the clipboard' : 'Could not copy', ok ? 'ok' : 'bad');
        },
      },
    },
    icon('copy', { size: 15 }),
  );
}

export function helpDialog() {
  const rows = data.shortcuts.map(([keys, label]) => {
    const keyNodes = [];
    keys.split(' then ').forEach((part, index) => {
      if (index) keyNodes.push(h('span', { class: 'faint', text: ' then ' }));
      keyNodes.push(h('kbd', { class: 'kbd', text: part }));
    });
    return h('div', { class: 'shortcut-row' },
      h('div', { class: 'shortcut-keys' }, keyNodes),
      h('div', { class: 'shortcut-label', text: label }),
    );
  });
  return openDialog({
    title: 'Keyboard shortcuts',
    size: 'dialog-lg',
    body: [
      h('p', { class: 'dialog-message', text: 'Shortcuts are ignored while you type in a field, while a modifier is held, or while a dialog is open.' }),
      h('div', { class: 'shortcut-table' }, rows),
    ],
    footer: h('button', { class: 'btn btn-secondary', type: 'button', on: { click: (e) => e.target.closest('dialog').close() } }, 'Close'),
  });
}

export function confirmAction({ title, message, confirmLabel, danger = false, onConfirm, note = null }) {
  const dialog = openDialog({
    title,
    size: 'dialog-sm',
    body: [
      h('p', { class: 'dialog-message', text: message }),
      note,
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Cancel'),
      h('button', { class: `btn ${danger ? 'btn-danger' : 'btn-primary'}`, type: 'button', on: { click: () => { dialog.close(); onConfirm(); } } }, confirmLabel),
    ],
  });
  return dialog;
}

export function emptyState({ title, text, action = null }) {
  return h('div', { class: 'empty' },
    h('span', { class: 'empty-icon' }, icon('info', { size: 24 })),
    h('div', { class: 'empty-title', text: title }),
    text ? h('p', { class: 'empty-text', text }) : null,
    action ? h('div', { style: { marginTop: '8px' } }, action) : null,
  );
}

export function statusChip(status) {
  if (status === 'ACTIVE') return h('span', { class: 'chip chip-ok', text: 'Active' });
  if (status === 'REVOKED') return h('span', { class: 'chip chip-bad', text: 'Revoked' });
  return h('span', { class: 'chip chip-warn', text: 'Not enrolled' });
}

export function inviteChip(state) {
  if (state === 'PENDING') return h('span', { class: 'chip chip-neutral', text: 'Waiting' });
  if (state === 'USED') return h('span', { class: 'chip chip-ok', text: 'Used' });
  if (state === 'EXPIRED') return h('span', { class: 'chip chip-warn', text: 'Expired' });
  return h('span', { class: 'chip chip-bad', text: 'Revoked' });
}

export function fingerprintBlock(hex) {
  const clean = hex.replace(/\s+/g, '');
  const cells = [];
  for (let i = 0; i < 64; i += 1) {
    const bit = parseInt(clean[i] || '0', 16) % 3 === 0;
    cells.push(h('span', { class: `fp-cell${bit ? ' fp-on' : ''}` }));
  }
  return h('div', { class: 'fingerprint', 'aria-hidden': 'true' }, cells);
}

export function copyRow(value, label) {
  return h('div', { class: 'copy-row' },
    h('span', { class: 'copy-value', text: value }),
    copyButton(value, label),
  );
}

export function navSheet({ groups, settingsLabel, current, onNavigate, onHelp, onLogout }) {
  const items = [];
  for (const group of groups) {
    items.push(h('div', { class: 'sheet-section-label', text: group.label }));
    for (const item of group.items) {
      items.push(h('a', {
        class: 'nav-item',
        href: `#/${item.id}`,
        'aria-current': current === item.id ? 'page' : null,
        on: { click: () => dialog.close() },
      }, icon(item.glyph, { size: 20 }), h('span', { text: item.label }), item.id === 'requests' ? h('span', { class: 'nav-badge', text: String(item.count ?? '') }) : null));
    }
  }
  items.push(h('div', { class: 'sheet-section-label', text: 'Console' }));
  items.push(h('a', { class: 'nav-item', href: '#/settings', 'aria-current': current === 'settings' ? 'page' : null, on: { click: () => dialog.close() } }, icon('settings', { size: 20 }), h('span', { text: settingsLabel })));
  items.push(h('button', { class: 'nav-item', type: 'button', on: { click: () => { dialog.close(); onHelp(); } } }, icon('help', { size: 20 }), h('span', { text: 'Keyboard shortcuts' })));
  items.push(h('button', { class: 'nav-item', type: 'button', on: { click: () => { dialog.close(); onLogout(); } } }, icon('logout', { size: 20 }), h('span', { text: 'Sign out' })));
  const dialog = openDialog({ title: 'Go to', body: h('nav', { class: 'sheet-nav', 'aria-label': 'Console pages' }, items), size: 'dialog-sm' });
  return dialog;
}
