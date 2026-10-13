// Modal dialogs and the side drawer, both on the native <dialog> element (top layer, page behind inert, Esc, focus return) plus a
// manual Tab wrap so focus can never leave the dialog, and a labelled, described structure for assistive technology.

import { h, mount, focusables, uid } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { button } from './widgets.js';

function openModal({ title, body, actions = [], size = 'md', dismissible = true, drawer = false, onClose, initialFocus, description }) {
  const doc = globalThis.document;
  const opener = doc.activeElement;
  const titleId = uid('dlg-title');
  const bodyId = uid('dlg-body');
  let closed = false;
  const controller = { result: undefined };

  const closeBtn = dismissible
    ? h('button', { class: 'icon-btn dialog-close', type: 'button', 'aria-label': t('common.close'), title: t('common.close'), on: { click: () => close() } }, icon('close'))
    : null;
  const head = h('header', { class: 'dialog-head' }, h('h2', { id: titleId, class: 'dialog-title', text: title }), closeBtn);
  const bodyEl = h('div', { class: 'dialog-body', id: bodyId }, body);
  const foot = actions.length ? h('footer', { class: 'dialog-foot' }, actions) : null;
  const el = h(
    'dialog',
    { class: ['dialog', drawer ? 'drawer' : 'dialog-' + size], 'aria-labelledby': titleId, 'aria-describedby': description ? bodyId : null },
    h('div', { class: 'dialog-panel' }, head, bodyEl, foot),
  );

  function close(result) {
    if (closed) return;
    closed = true;
    controller.result = result;
    try {
      el.close();
    } catch (e) {
      /* already closed */
    }
    el.remove();
    if (opener && opener.isConnected && typeof opener.focus === 'function') opener.focus();
    if (onClose) onClose(result);
  }

  el.addEventListener('cancel', (ev) => {
    ev.preventDefault();
    if (dismissible) close();
  });
  el.addEventListener('click', (ev) => {
    // a click on the backdrop (the dialog element itself, outside the panel) closes a dismissible dialog
    if (dismissible && ev.target === el) close();
  });
  el.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Tab') return;
    const list = focusables(el);
    if (list.length === 0) {
      ev.preventDefault();
      return;
    }
    const first = list[0];
    const last = list[list.length - 1];
    if (ev.shiftKey && doc.activeElement === first) {
      ev.preventDefault();
      last.focus();
    } else if (!ev.shiftKey && doc.activeElement === last) {
      ev.preventDefault();
      first.focus();
    }
  });

  doc.body.appendChild(el);
  el.showModal();
  const target = (initialFocus && initialFocus()) || el.querySelector('[data-autofocus]') || focusables(bodyEl)[0] || closeBtn || focusables(el)[0];
  if (target) target.focus();
  return { el, close, setBody: (...nodes) => mount(bodyEl, ...nodes), setActions: (...nodes) => { if (foot) mount(foot, ...nodes); }, get bodyEl() { return bodyEl; } };
}

export function openDialog(opts) {
  return openModal(opts);
}

export function openDrawer(opts) {
  return openModal({ ...opts, drawer: true });
}

/**
 * A confirmation. Resolves to true when confirmed, false when cancelled.
 * `confirmText`: when set, the confirm button stays disabled until the administrator types exactly that text.
 */
export function confirmDialog({ title, message, detail, confirmLabel, cancelLabel, danger = false, confirmText = '' }) {
  return new Promise((resolve) => {
    let input = null;
    const confirmBtn = button({ label: confirmLabel || t('common.confirm'), variant: danger ? 'danger' : 'primary', on: { click: () => dlg.close(true) } });
    const children = [h('p', { class: 'dialog-message', text: message })];
    if (detail) children.push(h('p', { class: 'muted', text: detail }));
    if (confirmText) {
      const id = uid('confirm');
      input = h('input', { class: 'input', id, type: 'text', autocomplete: 'off', spellcheck: 'false', 'data-autofocus': '' });
      confirmBtn.disabled = true;
      input.addEventListener('input', () => {
        confirmBtn.disabled = input.value.trim() !== confirmText;
      });
      input.addEventListener('keydown', (ev) => {
        if (ev.key === 'Enter' && !confirmBtn.disabled) dlg.close(true);
      });
      children.push(
        h('div', { class: 'field' }, h('label', { class: 'label', for: id, text: t('confirm.type', { text: confirmText }) }), input),
      );
    }
    const dlg = openModal({
      title,
      body: children,
      size: 'sm',
      actions: [button({ label: cancelLabel || t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close(false) } }), confirmBtn],
      onClose: (r) => resolve(r === true),
    });
    if (!confirmText) confirmBtn.focus();
  });
}

/** An information dialog with one Close button. */
export function infoDialog({ title, body, size = 'md' }) {
  const dlg = openModal({ title, body, size, actions: [button({ label: t('common.close'), variant: 'secondary', on: { click: () => dlg.close() } })] });
  return dlg;
}
