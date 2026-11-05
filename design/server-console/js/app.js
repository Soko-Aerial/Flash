import { h, mount } from './dom.js';
import { icon } from './icons.js';
import { data } from './data.js';
import { state, applyTheme, toggleTheme, subscribe, waitingCount } from './store.js';
import { helpDialog, navSheet, toast } from './ui.js';
import { mountOverview } from './views/overview.js';
import { mountDevices } from './views/devices.js';
import { mountInvites } from './views/invites.js';
import { mountRequests } from './views/requests.js';
import { mountOrganization } from './views/organization.js';
import { mountAnnouncements } from './views/announcements.js';
import { mountAudit } from './views/audit.js';
import { mountSecurity } from './views/security.js';
import { mountSettings } from './views/settings.js';
import { mountLogin } from './views/login.js';

export const NAV_GROUPS = [
  {
    label: 'Hub',
    items: [
      { id: 'overview', label: 'Overview', glyph: 'overview' },
      { id: 'devices', label: 'Devices', glyph: 'devices' },
      { id: 'invites', label: 'Invites', glyph: 'invites' },
    ],
  },
  {
    label: 'People',
    items: [
      { id: 'requests', label: 'Join requests', glyph: 'requests', badge: true },
      { id: 'organization', label: 'Organization', glyph: 'org' },
      { id: 'announcements', label: 'Announcements', glyph: 'announcements' },
    ],
  },
  {
    label: 'Trust',
    items: [
      { id: 'audit', label: 'Audit', glyph: 'audit' },
      { id: 'security', label: 'Security', glyph: 'security' },
    ],
  },
];

const ROUTES = {
  overview: mountOverview,
  devices: mountDevices,
  invites: mountInvites,
  requests: mountRequests,
  organization: mountOrganization,
  announcements: mountAnnouncements,
  audit: mountAudit,
  security: mountSecurity,
  settings: mountSettings,
};

const pageLabels = {
  overview: 'Overview',
  devices: 'Devices',
  invites: 'Invites',
  requests: 'Join requests',
  organization: 'Organization',
  announcements: 'Announcements',
  audit: 'Audit log',
  security: 'Security',
  settings: 'Settings',
};

function navItem(item, { bottom = false } = {}) {
  const badge = item.badge ? h('span', { class: bottom ? 'bottom-badge' : 'nav-badge', text: String(waitingCount()) }) : null;
  return h(
    'a',
    {
      class: bottom ? 'bottom-item' : 'nav-item',
      href: `#/${item.id}`,
      'data-nav': item.id,
    },
    icon(item.glyph, { size: bottom ? 20 : 18, className: 'nav-glyph' }),
    h('span', { text: item.label }),
    badge,
  );
}

function createThemeButton() {
  const btn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': '', title: '' });
  function refresh() {
    const goingTo = state.theme === 'dark' ? 'light' : 'dark';
    mount(btn, icon(goingTo === 'light' ? 'sun' : 'moon', { size: 17 }));
    btn.setAttribute('aria-label', `Switch to ${goingTo} theme`);
    btn.title = `Switch to ${goingTo} theme`;
  }
  btn.addEventListener('click', () => {
    toggleTheme();
    btn.classList.remove('is-swapping');
    void btn.offsetWidth;
    btn.classList.add('is-swapping');
    setTimeout(() => btn.classList.remove('is-swapping'), 220);
  });
  subscribe((event) => { if (event.type === 'theme') refresh(); });
  refresh();
  return btn;
}

function buildShell() {
  const links = [];
  const badges = [];

  const groups = NAV_GROUPS.map((group) => {
    const list = h('ul', { class: 'nav', role: 'list' });
    for (const item of group.items) {
      const link = navItem(item);
      links.push({ id: item.id, el: link });
      const b = link.querySelector('.nav-badge');
      if (b) badges.push(b);
      list.append(h('li', {}, link));
    }
    return h('div', { class: 'nav-group' }, h('span', { class: 'nav-group-label', text: group.label }), list);
  });

  const settingsLink = h('a', { class: 'nav-item', href: '#/settings', 'data-nav': 'settings' },
    icon('settings', { size: 18, className: 'nav-glyph' }), h('span', { text: 'Settings' }));
  links.push({ id: 'settings', el: settingsLink });

  const helpBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Keyboard shortcuts', title: 'Keyboard shortcuts', on: { click: () => helpDialog() } }, icon('help', { size: 17 }));
  const themeBtn = createThemeButton();
  const utilRow = h('div', { class: 'util-row' }, helpBtn, themeBtn);

  let sessionSeconds = data.admin.sessionMinutes * 60;
  const sessionText = h('span', { class: 'session-time', text: `${data.admin.sessionMinutes} min` });
  const sessionRow = h('div', { class: 'session-row', title: 'Time left in this session' },
    icon('clock', { size: 14 }),
    h('span', { text: 'Session ends in' }),
    sessionText,
  );
  setInterval(() => {
    sessionSeconds = Math.max(0, sessionSeconds - 1);
    sessionText.textContent = sessionSeconds >= 60 ? `${Math.ceil(sessionSeconds / 60)} min` : `${sessionSeconds} s`;
    sessionRow.classList.toggle('is-warn', sessionSeconds <= 300);
  }, 1000);

  const signOut = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Sign out', title: 'Sign out', on: { click: () => go('login') } }, icon('logout', { size: 16 }));
  const adminRow = h('div', { class: 'admin-row' },
    h('span', { class: 'avatar', text: data.admin.initials }),
    h('div', { class: 'admin-text' },
      h('div', { class: 'admin-name', text: data.admin.name }),
      h('div', { class: 'admin-role', text: 'Organization admin' }),
    ),
    signOut,
  );

  const sidebar = h('aside', { class: 'sidebar' },
    h('div', { class: 'brand-block' },
      h('span', { class: 'brand-mark' }, icon('bolt', { size: 22 })),
      h('div', { class: 'brand-text' },
        h('div', { class: 'brand-name', text: 'Flash Hub' }),
        h('div', { class: 'brand-org', text: `${data.hub.org} · Production` }),
      ),
    ),
    h('div', { class: 'hub-line', 'aria-hidden': 'true' }),
    h('nav', { 'aria-label': 'Console pages' }, groups),
    h('div', { class: 'sidebar-spacer' }),
    settingsLink,
    utilRow,
    sessionRow,
    adminRow,
  );

  const crumb = h('span', { class: 'page-crumb' });
  const mobileTheme = createThemeButton();
  const menuBtn = h('button', { class: 'icon-btn', type: 'button', 'aria-label': 'All pages', title: 'All pages', on: { click: openSheet } }, icon('menu', { size: 18 }));
  const mobilebar = h('div', { class: 'mobilebar' },
    h('div', { class: 'brand-block', style: { padding: '0', flex: '1' } },
      h('span', { class: 'brand-mark' }, icon('bolt', { size: 20 })),
      h('div', { class: 'brand-text' },
        h('div', { class: 'brand-name', text: 'Flash Hub' }),
        h('div', { class: 'brand-org', text: `${data.hub.org} · Production` }),
      ),
    ),
    crumb,
    h('div', { class: 'bar-actions' }, mobileTheme, menuBtn),
  );

  const main = h('main', { class: 'main', id: 'main', tabindex: '-1' });
  const skip = h('a', { class: 'skip-link', href: '#main', text: 'Skip to the page content' });
  skip.addEventListener('click', (event) => { event.preventDefault(); main.focus(); });

  const bottomItems = [
    { id: 'overview', label: 'Overview', glyph: 'overview' },
    { id: 'devices', label: 'Devices', glyph: 'devices' },
    { id: 'invites', label: 'Invites', glyph: 'invites' },
    { id: 'requests', label: 'Requests', glyph: 'requests', badge: true },
  ].map((item) => {
    const link = navItem(item, { bottom: true });
    links.push({ id: item.id, el: link });
    const b = link.querySelector('.bottom-badge, .nav-badge');
    if (b) badges.push(b);
    return link;
  });
  const moreBtn = h('button', { class: 'bottom-item', type: 'button', on: { click: openSheet } },
    icon('more', { size: 20, className: 'nav-glyph' }), h('span', { text: 'More' }));
  const bottomNav = h('nav', { class: 'bottom-nav', 'aria-label': 'Console pages' }, bottomItems, moreBtn);

  function openSheet() {
    navSheet({
      groups: NAV_GROUPS.map((group) => ({
        ...group,
        items: group.items.map((item) => (item.badge ? { ...item, count: waitingCount() } : item)),
      })),
      settingsLabel: 'Settings',
      current: currentRoute(),
      onHelp: helpDialog,
      onLogout: () => go('login'),
    });
  }

  const mainCol = h('div', { class: 'main-col' }, mobilebar, main);
  const el = h('div', { class: 'shell' }, skip, sidebar, mainCol, bottomNav);

  const bottomRoutes = new Set(['overview', 'devices', 'invites', 'requests']);
  function setActive(route) {
    for (const { id, el: link } of links) {
      const on = id === route;
      if (on) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
    if (route && !bottomRoutes.has(route)) moreBtn.setAttribute('aria-current', 'page');
    else moreBtn.removeAttribute('aria-current');
    for (const b of badges) b.textContent = String(waitingCount());
  }

  function setCrumb(route) {
    crumb.replaceChildren(h('span', { class: 'muted', text: data.hub.org }), h('span', { class: 'faint', text: '·' }), h('span', { text: pageLabels[route] || 'Overview' }));
  }

  return { el, main, setActive, setCrumb };
}

function currentRoute() {
  const raw = window.location.hash.replace(/^#\/?/, '') || 'overview';
  return raw.split('?')[0].split('/')[0];
}

function parse() {
  const raw = window.location.hash.replace(/^#\/?/, '') || 'overview';
  const [path, queryString = ''] = raw.split('?');
  const segments = path.split('/').filter(Boolean);
  return { path: segments[0] || 'overview', segments, query: new URLSearchParams(queryString) };
}

function go(route) {
  window.location.hash = `#/${route}`;
}

const shell = buildShell();
const authRoot = h('div', { id: 'auth-root', hidden: true });
const appRoot = document.getElementById('app');
appRoot.append(shell.el, authRoot);
applyTheme();

function render() {
  const { path, segments, query } = parse();
  const isAuth = path === 'login';
  shell.el.hidden = isAuth;
  authRoot.hidden = !isAuth;
  document.body.classList.toggle('is-auth', isAuth);

  if (isAuth) {
    mount(authRoot);
    const login = mountLogin(authRoot, {
      go: (route) => go(route),
      themeButton: () => createThemeButton(),
    });
    return;
  }

  const mountRoute = ROUTES[path] || null;
  const routeKey = mountRoute ? path : 'notfound';
  shell.setActive(routeKey === 'notfound' ? '' : routeKey);
  shell.setCrumb(routeKey === 'notfound' ? 'Not found' : routeKey);
  document.title = `${mountRoute ? pageLabels[routeKey] : 'Not found'} · Flash Hub`;
  mount(shell.main);

  const ctx = {
    route: path,
    segments,
    query,
    go,
    setQuery(params) {
      const next = new URLSearchParams();
      for (const [key, value] of Object.entries(params || {})) {
        if (value !== '' && value !== null && value !== undefined) next.set(key, String(value));
      }
      const qs = next.toString();
      window.location.hash = `#/${path}${qs ? `?${qs}` : ''}`;
    },
    openHelp: helpDialog,
  };

  if (!mountRoute) {
    mount(shell.main,
      h('div', { class: 'page-head' },
        h('div', { class: 'page-head-text' },
          h('h1', { class: 'page-title', text: 'That page does not exist' }),
          h('p', { class: 'page-lede', text: 'The address does not match any page of the console.' }),
        ),
      ),
      h('div', {}, h('a', { class: 'btn btn-primary', href: '#/overview' }, 'Go to Overview')),
    );
    return;
  }

  mountRoute(shell.main, ctx);
  shell.main.focus({ preventScroll: true });
  window.scrollTo(0, 0);
}

window.addEventListener('hashchange', render);
subscribe((event) => {
  shell.setActive(currentRoute());
  if (event.type === 'audit') toast('Audit chain intact', 'ok');
});

const KEY_ROUTES = { o: 'overview', d: 'devices', i: 'invites', r: 'requests', a: 'audit', s: 'security', t: 'settings' };
let goPrefixUntil = 0;
document.addEventListener('keydown', (event) => {
  if (document.body.classList.contains('is-auth')) return;
  if (document.querySelector('dialog[open]')) return;
  const target = event.target;
  const typing = target instanceof Element && target.closest('input, textarea, select, [contenteditable]');
  if (typing) {
    if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') target.closest('form')?.requestSubmit();
    return;
  }
  if (event.metaKey || event.ctrlKey || event.altKey) return;
  if (Date.now() < goPrefixUntil) {
    const route = KEY_ROUTES[event.key];
    goPrefixUntil = 0;
    if (route) { event.preventDefault(); go(route); return; }
  }
  if (event.key === 'g') { goPrefixUntil = Date.now() + 900; return; }
  if (event.key === '/') {
    const search = document.querySelector('input[type="search"]');
    if (search) { event.preventDefault(); search.focus(); search.select?.(); }
    return;
  }
  if (event.key === '?') { event.preventDefault(); helpDialog(); return; }
  if (event.key === 'j' || event.key === 'k') {
    const rows = [...document.querySelectorAll('tr.row')];
    if (!rows.length) return;
    event.preventDefault();
    const current = document.activeElement instanceof Element ? document.activeElement.closest('tr.row') : null;
    let index = rows.indexOf(current);
    if (index < 0) index = event.key === 'j' ? -1 : rows.length;
    index = Math.max(0, Math.min(rows.length - 1, index + (event.key === 'j' ? 1 : -1)));
    rows[index].focus();
  }
});

render();
