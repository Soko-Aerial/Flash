// The application frame: navigation rail (icons only on small laptops, a bottom bar on phones), top bar with the session countdown,
// theme toggle, help and sign out, and the single <main> landmark every page renders into.

import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { getTheme, cycleTheme } from '../theme.js';
import { formatClock } from '../format.js';
import { openDrawer } from './dialog.js';

export const NAV = [
  { id: 'overview', glyph: 'overview', key: 'o' },
  { id: 'devices', glyph: 'devices', key: 'd' },
  { id: 'invites', glyph: 'invites', key: 'i' },
  { id: 'organization', glyph: 'org', key: 'g' },
  { id: 'announcements', glyph: 'announcements', key: 'n' },
  { id: 'audit', glyph: 'audit', key: 'a' },
  { id: 'security', glyph: 'security', key: 's' },
  { id: 'settings', glyph: 'settings', key: 't' },
];

const BOTTOM = ['overview', 'devices', 'invites', 'organization'];

function themeGlyph() {
  return { light: 'sun', dark: 'moon', system: 'monitor' }[getTheme()];
}

/**
 * @param {{onLogout: () => void, onHelp: () => void}} o
 */
export function createShell({ onLogout, onHelp }) {
  const badges = new Map(); // id -> [elements]
  let active = 'overview';
  const links = new Map(); // id -> [anchors]

  function navLink(item, { compact = false } = {}) {
    const badge = h('span', { class: 'nav-badge num', hidden: true, 'aria-hidden': 'true' });
    const a = h(
      'a',
      { class: ['nav-item', compact ? 'nav-item-compact' : ''], href: '#/' + item.id, 'data-nav': item.id, title: t('nav.' + item.id) },
      icon(item.glyph, { size: 22 }),
      h('span', { class: 'nav-label', text: compact ? t('nav.short.' + item.id) : t('nav.' + item.id) }),
      badge,
    );
    if (!links.has(item.id)) links.set(item.id, []);
    links.get(item.id).push(a);
    if (!badges.has(item.id)) badges.set(item.id, []);
    badges.get(item.id).push({ el: badge, anchor: a });
    return a;
  }

  const rail = h(
    'nav',
    { class: 'rail', 'aria-label': t('nav.main') },
    h('div', { class: 'brand' }, icon('bolt', { size: 26, className: 'brand-bolt' }), h('span', { class: 'brand-name', text: t('app.name') })),
    h('ul', { class: 'nav' }, NAV.map((item) => h('li', {}, navLink(item)))),
  );

  const hubName = h('span', { class: 'hub-name', text: '' });
  const countdown = h('span', { class: 'session-chip num', role: 'timer', title: t('session.countdown.title'), text: '' });
  const themeBtn = h('button', { class: 'icon-btn', type: 'button', on: { click: () => { cycleTheme(); refreshTheme(); } } }, icon(themeGlyph()));
  const helpBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': t('help.open'), title: t('help.open'), on: { click: onHelp } }, icon('keyboard'));
  const userLabel = h('span', { class: 'user-label', text: '' });
  const logoutBtn = h('button', { class: 'btn btn-ghost btn-sm', type: 'button', on: { click: onLogout } }, icon('logout', { size: 18 }), h('span', { class: 'btn-label', text: t('auth.logout') }));

  function refreshTheme() {
    const mode = getTheme();
    themeBtn.setAttribute('aria-label', t('theme.toggle', { mode: t('theme.' + mode) }));
    themeBtn.title = t('theme.toggle', { mode: t('theme.' + mode) });
    mount(themeBtn, icon(themeGlyph()));
  }
  refreshTheme();

  const topbar = h(
    'header',
    { class: 'topbar' },
    h('div', { class: 'topbar-left' }, icon('bolt', { size: 20, className: 'brand-bolt topbar-bolt' }), hubName),
    h('div', { class: 'topbar-right' }, countdown, userLabel, themeBtn, helpBtn, logoutBtn),
  );

  const main = h('main', { class: 'main', id: 'main', tabindex: '-1' });

  const moreBtn = h('button', { class: 'nav-item nav-item-compact', type: 'button', title: t('nav.more'), on: { click: openMore } }, icon('menu', { size: 22 }), h('span', { class: 'nav-label', text: t('nav.more') }));
  const bottom = h(
    'nav',
    { class: 'bottom-nav', 'aria-label': t('nav.main') },
    NAV.filter((i) => BOTTOM.includes(i.id)).map((i) => navLink(i, { compact: true })),
    moreBtn,
  );

  function openMore() {
    const list = h('ul', { class: 'nav nav-more' }, NAV.map((item) => h('li', {}, moreLink(item))));
    const dlg = openDrawer({ title: t('nav.more'), body: list });
    list.addEventListener('click', (ev) => {
      if (ev.target.closest('a')) dlg.close();
    });
  }
  function moreLink(item) {
    return h('a', { class: ['nav-item', item.id === active ? 'is-active' : ''], href: '#/' + item.id, 'aria-current': item.id === active ? 'page' : null }, icon(item.glyph, { size: 22 }), h('span', { class: 'nav-label', text: t('nav.' + item.id) }));
  }

  const skip = h('a', { class: 'skip-link', href: '#main', text: t('a11y.skip') });
  skip.addEventListener('click', (ev) => {
    ev.preventDefault();
    main.focus();
  });

  const el = h('div', { class: 'shell' }, skip, rail, h('div', { class: 'main-col' }, topbar, main), bottom);

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
      moreBtn.classList.toggle('is-active', !BOTTOM.includes(id) && NAV.some((n) => n.id === id));
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
      hubName.textContent = name;
    },
    setUser(name, role) {
      userLabel.textContent = name + ' · ' + t('role.' + String(role || 'org_admin').toLowerCase());
    },
    setCountdown(ms, tone) {
      countdown.textContent = formatClock(ms);
      countdown.classList.toggle('is-warn', tone === 'warn');
      countdown.setAttribute('aria-label', t('session.countdown.label', { time: formatClock(ms) }));
    },
    refreshTheme,
  };
}
