// Keyboard shortcuts: `g` then a letter navigates, `/` focuses the page's search, `?` opens the help, j/k/Enter/x work in the page's table.
// They are ignored while typing in a field, while a dialog is open, and when Ctrl/Meta/Alt is held.

import { h } from './dom.js';
import { t } from './i18n.js';
import { NAV } from './ui/shell.js';
import { openDialog } from './ui/dialog.js';
import { button, kbd } from './ui/widgets.js';

let table = null;
let goTo = () => {};
let pendingG = 0;

/** The current page registers its table so j/k/x/Enter work; pass null on leaving. */
export function setActiveTable(api) {
  table = api;
}

export function installShortcuts({ navigate }) {
  goTo = navigate;
  globalThis.document.addEventListener('keydown', onKey);
}

function typing(target) {
  if (!target || !target.tagName) return false;
  const tag = target.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable === true;
}

function onKey(ev) {
  if (ev.defaultPrevented || ev.ctrlKey || ev.metaKey || ev.altKey) return;
  const doc = globalThis.document;
  if (doc.querySelector('dialog[open]')) return;
  if (typing(ev.target)) {
    if (ev.key === 'Escape' && ev.target.matches && ev.target.matches('[data-search]') && ev.target.value) {
      ev.target.value = '';
      ev.target.dispatchEvent(new Event('input', { bubbles: true }));
    }
    return;
  }
  const key = ev.key;
  if (pendingG && Date.now() - pendingG < 1200) {
    pendingG = 0;
    const item = NAV.find((n) => n.key === key.toLowerCase());
    if (item) {
      ev.preventDefault();
      goTo(item.id);
    }
    return;
  }
  pendingG = 0;
  if (key === 'g') {
    pendingG = Date.now();
    return;
  }
  if (key === '/') {
    const search = doc.querySelector('[data-search]');
    if (search) {
      ev.preventDefault();
      search.focus();
      search.select();
    }
    return;
  }
  if (key === '?') {
    ev.preventDefault();
    openHelp();
    return;
  }
  if (table) {
    if (key === 'j') {
      ev.preventDefault();
      table.moveFocus(1);
    } else if (key === 'k') {
      ev.preventDefault();
      table.moveFocus(-1);
    } else if (key === 'x') {
      ev.preventDefault();
      table.toggleFocused();
    }
  }
}

export function openHelp() {
  const rows = [
    [['g', 'o'], t('help.go.overview')],
    [['g', 'd'], t('help.go.devices')],
    [['g', 'i'], t('help.go.invites')],
    [['g', 'g'], t('help.go.organization')],
    [['g', 'a'], t('help.go.audit')],
    [['g', 's'], t('help.go.security')],
    [['g', 't'], t('help.go.settings')],
    [['/'], t('help.search')],
    [['?'], t('help.help')],
    [['Esc'], t('help.escape')],
    [['j'], t('help.next')],
    [['k'], t('help.prev')],
    [['Enter'], t('help.open.row')],
    [['x'], t('help.select')],
    [['Ctrl', 'Enter'], t('help.submit')],
  ];
  const list = h('dl', { class: 'shortcut-list' });
  for (const r of rows) {
    list.appendChild(h('dt', {}, r[0].flatMap((k, i) => (i ? [h('span', { class: 'plus', text: r[0][0] === 'g' ? t('help.then') : '+' }), kbd(k)] : [kbd(k)]))));
    list.appendChild(h('dd', { text: r[1] }));
  }
  const dlg = openDialog({
    title: t('help.title'),
    body: [h('p', { class: 'muted', text: t('help.intro') }), list],
    actions: [button({ label: t('common.close'), variant: 'secondary', on: { click: () => dlg.close() } })],
  });
  return dlg;
}
