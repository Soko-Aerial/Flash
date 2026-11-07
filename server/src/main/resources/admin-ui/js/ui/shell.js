// The application frame: a labelled sidebar on the page ground (no second top bar), a compact bar and
// bottom navigation on narrower screens, and the one <main> landmark every page renders into.

import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { getTheme, cycleTheme } from '../theme.js';
import { formatClock } from '../format.js';
import { openDrawer } from './dialog.js';
import { canOpenConsoleRoute } from '../state.js';

export const NAV = [
  { id: 'overview', glyph: 'overview', key: 'o' },
  { id: 'devices', glyph: 'devices', key: 'd' },
  { id: 'invites', glyph: 'invites', key: 'i' },
  { id: 'requests', glyph: 'requests', key: 'r' },
  { id: 'organization', glyph: 'org', key: 'g' },
  { id: 'announcements', glyph: 'announcements', key: 'n' },
  { id: 'audit', glyph: 'audit', key: 'a' },
  { id: 'security', glyph: 'security', key: 's' },
  { id: 'settings', glyph: 'settings', key: 't' },
];

const GROUPS = [
  { label: 'nav.group.hub', ids: ['overview', 'devices', 'invites'] },
  { label: 'nav.group.people', ids: ['requests', 'organization', 'announcements'] },
  { label: 'nav.group.trust', ids: ['audit', 'security'] },
];
const BOTTOM = ['overview', 'devices', 'invites', 'requests'];

function themeGlyph() {
  return { light: 'sun', dark: 'moon', system: 'monitor' }[getTheme()];
}

function initials(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0] || '?')[0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

const byId = (id) => NAV.find((item) => item.id === id);

export function createShell({ onLogout, onHelp }) {
  const links = new Map(); // id -> [anchors]
  const badges = new Map(); // id -> [badge elements]
  let active = 'overview';

  function navLink(item, { bottom = false } = {}) {
    const badge = h('span', { class: bottom ? 'fx-bottom-badge' : 'fx-nav-badge', hidden: true, 'aria-hidden': 'true' });
    const a = h(
      'a',
      {
        class: bottom ? 'fx-bottom-item' : 'fx-nav-item',
        href: '#/' + item.id,
        'data-nav': item.id,
        title: t('nav.' + item.id),
      },
      icon(item.glyph, { size: bottom ? 20 : 18, className: 'fx-nav-glyph' }),
      h('span', { text: bottom ? t('nav.short.' + item.id) : t('nav.' + item.id) }),
      badge,
    );
    if (!links.has(item.id)) links.set(item.id, []);
    links.get(item.id).push(a);
    if (!badges.has(item.id)) badges.set(item.id, []);
    badges.get(item.id).push({ el: badge, anchor: a });
    return a;
  }

  const groups = GROUPS.map((group) =>
    h(
      'div',
      { class: 'fx-nav-group' },
      h('span', { class: 'fx-nav-label', text: t(group.label) }),
      h('ul', { class: 'fx-nav', role: 'list' }, group.ids.filter(canOpenConsoleRoute).map((id) => h('li', {}, navLink(byId(id))))),
    ),
  );

  const settingsLink = canOpenConsoleRoute('settings') ? navLink(byId('settings')) : null;
  const brandHub = h('div', { class: 'fx-brand-hub', text: '' });
  const mobileHub = h('div', { class: 'fx-brand-hub', text: '' });
  const brand = (compact = false) =>
    h(
      'div',
      { class: 'fx-brand' },
      h('span', { class: 'fx-brand-mark' }, icon('bolt', { size: compact ? 20 : 22 })),
      h(
        'div',
        { class: 'fx-brand-text' },
        h('div', { class: 'fx-brand-name', text: t('app.name') }),
        compact ? mobileHub : brandHub,
      ),
    );

  const hubLine = h('div', { class: 'fx-hub-line', 'aria-hidden': 'true' });

  const helpBtn = h(
    'button',
    { class: 'icon-btn', type: 'button', 'aria-label': t('help.open'), title: t('help.open'), on: { click: onHelp } },
    icon('keyboard'),
  );
  const themeBtn = h(
    'button',
    { class: 'icon-btn', type: 'button', 'aria-label': t('theme.cycle'), title: t('theme.cycle'), on: { click: () => { cycleTheme(); refreshTheme(); } } },
    icon(themeGlyph()),
  );
  const utilRow = h('div', { class: 'fx-util-row' }, helpBtn, themeBtn);

  const sessionTime = h('span', { class: 'fx-session-time', text: '' });
  const sessionRow = h(
    'div',
    { class: 'fx-session', title: t('session.countdown.title') },
    icon('clock', { size: 14 }),
    h('span', { text: t('session.endsIn') }),
    sessionTime,
  );

  const userAvatar = h('span', { class: 'fx-avatar', text: '?' });
  const userName = h('div', { class: 'fx-user-name', text: '' });
  const userRole = h('div', { class: 'fx-user-role', text: '' });
  const logoutBtn = h(
    'button',
    { class: 'icon-btn', type: 'button', 'aria-label': t('auth.logout'), title: t('auth.logout'), on: { click: onLogout } },
    icon('logout', { size: 16 }),
  );
  const userRow = h('div', { class: 'fx-user' }, userAvatar, h('div', { class: 'fx-user-text' }, userName, userRole), logoutBtn);

  const sidebar = h(
    'aside',
    { class: 'fx-sidebar' },
    brand(),
    hubLine,
    h('nav', { 'aria-label': t('nav.main') }, groups),
    h('div', { class: 'fx-sidebar-spacer' }),
    h('div', { class: 'fx-sidebar-foot' }, settingsLink, utilRow, sessionRow, userRow),
  );

  const crumb = h('span', { class: 'fx-crumb' });
  const mobileTheme = h(
    'button',
    { class: 'icon-btn', type: 'button', 'aria-label': t('theme.cycle'), title: t('theme.cycle'), on: { click: () => { cycleTheme(); refreshTheme(); } } },
    icon(themeGlyph()),
  );
  const menuBtn = h(
    'button',
    { class: 'icon-btn fx-menu-btn', type: 'button', 'aria-label': t('nav.more'), title: t('nav.more'), on: { click: openMore } },
    icon('menu'),
  );
  const mobilebar = h(
    'div',
    { class: 'fx-mobilebar' },
    brand(true),
    crumb,
    h('div', { class: 'fx-bar-actions' }, mobileTheme, menuBtn),
  );

  const main = h('main', { class: 'fx-main', id: 'main', tabindex: '-1' });
  const skip = h('a', { class: 'skip-link', href: '#main', text: t('a11y.skip') });
  skip.addEventListener('click', (ev) => {
    ev.preventDefault();
    main.focus();
  });

  const bottomItems = BOTTOM.map((id) => navLink(byId(id), { bottom: true }));
  const moreBtn = h(
    'button',
    { class: 'fx-bottom-item', type: 'button', on: { click: openMore } },
    icon('more', { size: 20, className: 'fx-nav-glyph' }),
    h('span', { text: t('nav.more') }),
  );
  const bottom = h('nav', { class: 'fx-bottom-nav', 'aria-label': t('nav.main') }, bottomItems, moreBtn);

  function sheetLink(item) {
    return h(
      'a',
      { class: 'fx-nav-item', href: '#/' + item.id, 'aria-current': active === item.id ? 'page' : null },
      icon(item.glyph, { size: 20, className: 'fx-nav-glyph' }),
      h('span', { text: t('nav.' + item.id) }),
      item.id === 'requests' && badgeCount('requests') ? h('span', { class: 'fx-nav-badge', text: String(badgeCount('requests')) }) : null,
    );
  }

  function openMore() {
    const body = h('nav', { class: 'fx-sheet-nav', 'aria-label': t('nav.main') });
    for (const group of GROUPS) {
      body.append(h('div', { class: 'fx-sheet-label', text: t(group.label) }));
      for (const id of group.ids.filter(canOpenConsoleRoute)) body.append(sheetLink(byId(id)));
    }
    if (canOpenConsoleRoute('settings')) {
      body.append(h('div', { class: 'fx-sheet-label', text: t('nav.settings') }), sheetLink(byId('settings')));
    }
    const drawer = openDrawer({
      title: t('nav.more'),
      body,
      actions: [
        h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => { drawer.close(); onHelp(); } } }, icon('keyboard', { size: 18 }), h('span', { text: t('help.open') })),
        h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => { drawer.close(); onLogout(); } } }, icon('logout', { size: 18 }), h('span', { text: t('auth.logout') })),
      ],
    });
    body.addEventListener('click', (ev) => {
      if (ev.target.closest('a')) drawer.close();
    });
  }

  function refreshTheme() {
    const mode = getTheme();
    const label = t('theme.toggle', { mode: t('theme.' + mode) });
    for (const button of [themeBtn, mobileTheme]) {
      button.setAttribute('aria-label', label);
      button.title = label;
      mount(button, icon(themeGlyph()));
    }
  }
  refreshTheme();

  function badgeCount(id) {
    const entry = (badges.get(id) || [])[0];
    return entry && !entry.el.hidden ? entry.el.textContent : '';
  }

  const el = h('div', { class: 'fx-shell' }, skip, sidebar, h('div', { class: 'fx-main-col' }, mobilebar, main), bottom);

  return {
    el,
    main,
    setActive(id) {
      active = id;
      for (const [k, anchors] of links) {
        for (const a of anchors) {
          const on = k === id;
          a.classList.toggle('is-active', on);
          if (on) a.setAttribute('aria-current', 'page');
          else a.removeAttribute('aria-current');
        }
      }
      if (id && !BOTTOM.includes(id)) moreBtn.setAttribute('aria-current', 'page');
      else moreBtn.removeAttribute('aria-current');
      crumb.replaceChildren(h('span', { text: id ? t('nav.' + id) : t('app.name') }));
    },
    setBadge(id, n) {
      for (const { el: b, anchor } of badges.get(id) || []) {
        b.hidden = !n;
        b.textContent = n ? (n > 99 ? '99+' : String(n)) : '';
        anchor.setAttribute('aria-label', n ? t('nav.withBadge', { name: t('nav.' + id), n }) : '');
        if (!n) anchor.removeAttribute('aria-label');
      }
    },
    setHub(name) {
      const text = name || '';
      brandHub.textContent = text;
      brandHub.hidden = !text;
      mobileHub.textContent = text;
      mobileHub.hidden = !text;
    },
    setUser(name, role) {
      const display = name || '';
      userName.textContent = display;
      userAvatar.textContent = initials(display);
      const roleKey = String(role || 'org_admin').toLowerCase();
      userRole.textContent = t('role.short.' + roleKey);
    },
    setCountdown(ms, tone) {
      sessionTime.textContent = formatClock(ms);
      sessionRow.classList.toggle('is-warn', tone === 'warn');
      sessionRow.setAttribute('aria-label', t('session.countdown.label', { time: formatClock(ms) }));
    },
    refreshTheme,
  };
}
