// Entry point: finds out whether the hub still needs its first setup and whether this browser holds a session, then shows the setup
// wizard, the sign-in page or the console (frame + the page the address names). Everything the pages share (API client, step-up, session)
// lives in app.js and state.js; this file only wires them together.

import { h, mount } from './dom.js';
import { t, getLocale } from './i18n.js';
import { api, events, setEnrolHook, setApprovalHook, stepUp } from './app.js';
import { session, setSession, clearSession, subscribe } from './state.js';
import { createRouter, parseHash } from './router.js';
import { createShell } from './ui/shell.js';
import { installShortcuts, openHelp, setActiveTable } from './shortcuts.js';
import { watchSession } from './sessionwatch.js';
import { askToEnrol } from './ui/keydialog.js';
import { showApprovalDialog } from './ui/approvaldialog.js';
import { emptyState, errorState, button } from './ui/widgets.js';
import { authFrame, mountLogin } from './views/login.js';
import { mountSetup } from './views/setup.js';
import { mountOverview } from './views/overview.js';
import { mountDevices } from './views/devices.js';
import { mountInvites } from './views/invites.js';
import { mountOrganization } from './views/organization.js';
import { mountAnnouncements } from './views/announcements.js';
import { mountAudit } from './views/audit.js';
import { mountSecurity } from './views/security.js';
import { mountSettings } from './views/settings.js';

const VIEWS = {
  overview: mountOverview,
  devices: mountDevices,
  invites: mountInvites,
  organization: mountOrganization,
  announcements: mountAnnouncements,
  audit: mountAudit,
  security: mountSecurity,
  settings: mountSettings,
};
const OVERVIEW_POLL_MS = 60000;

const doc = globalThis.document;
const app = doc.getElementById('app');

let mode = 'boot'; // boot | setup | login | app
let setupOpen = false;
let hubNameValue = '';
let shell = null;
let stopWatch = null;
let overviewTimer = 0;
let mounted = null; // {route, destroy, onRoute}
let returnTo = '';
let unsubscribe = null;
let router = null;

function setTitle(route) {
  const page = mode === 'app' && route ? t('nav.' + route) : '';
  const parts = [page, hubNameValue, t('app.name')].filter(Boolean);
  doc.title = parts.join(' · ');
}

function closeOverlays() {
  for (const d of Array.from(doc.querySelectorAll('dialog'))) {
    try {
      d.close();
    } catch (e) {
      /* already closed */
    }
    d.remove();
  }
  const toasts = doc.querySelector('.toasts');
  if (toasts) mount(toasts);
}

function teardownView() {
  if (mounted && mounted.destroy) {
    try {
      mounted.destroy();
    } catch (e) {
      globalThis.console.error(e);
    }
  }
  mounted = null;
  setActiveTable(null);
}

function teardownApp() {
  teardownView();
  if (stopWatch) stopWatch();
  stopWatch = null;
  clearInterval(overviewTimer);
  if (unsubscribe) unsubscribe();
  unsubscribe = null;
  shell = null;
}

// ---------------------------------------------------------------------------------------------------------------- the context pages get

const ctx = {
  get query() {
    return router.current.query;
  },
  get segments() {
    return router.current.segments;
  },
  go: (segments, query) => router.go(segments, query),
  replaceHash: (segments) => router.replace(segments, {}),
  replaceQuery: (query) => router.replaceQuery(query),
  setBadge: (id, n) => shell && shell.setBadge(id, n),
  hubName: () => hubNameValue,
  onOverview: (o) => applyOverview(o),
  signedOut: (notice) => signOut(notice),
};

function applyOverview(o) {
  if (!shell) return;
  hubNameValue = o.hubName || hubNameValue;
  shell.setHub(hubNameValue);
  shell.setBadge('organization', o.pendingJoinRequests || 0);
  setTitle(router.current.route);
}

async function refreshOverview() {
  try {
    applyOverview(await api.get('/overview'));
  } catch (e) {
    /* the page that needs the numbers shows its own error */
  }
}

// ---------------------------------------------------------------------------------------------------------------- screens

function showBootError(err) {
  mode = 'boot';
  mount(
    app,
    authFrame({
      title: err && err.status === 404 ? t('boot.unavailable.title') : t('boot.failed.title'),
      subtitle: err && err.status === 404 ? t('boot.unavailable.text') : t('boot.failed.text'),
      children: [errorState(err, () => start())],
    }),
  );
}

function showSetup() {
  mode = 'setup';
  teardownApp();
  setTitle('');
  doc.title = t('setup.title') + ' · ' + t('app.name');
  const cleanup = mountSetup(app, {
    goto: (route) => {
      // The only way out of the wizard is its last step: setup is finished, so the sign-in page must not offer it again.
      setupOpen = false;
      router.go([route]);
    },
  });
  mounted = { route: 'setup', destroy: typeof cleanup === 'function' ? cleanup : cleanup && cleanup.destroy };
}

function showLogin(notice) {
  mode = 'login';
  teardownApp();
  doc.title = t('login.title') + ' · ' + t('app.name');
  const cleanup = mountLogin(app, {
    notice,
    setupOpen,
    onSignedIn: () => showApp(),
    goto: (route) => router.go([route]),
  });
  mounted = { route: 'login', destroy: typeof cleanup === 'function' ? cleanup : cleanup && cleanup.destroy };
}

function showApp() {
  mode = 'app';
  teardownApp();
  closeOverlays();
  shell = createShell({ onLogout: () => logout(), onHelp: () => openHelp() });
  shell.setUser(session.user, session.role);
  mount(app, shell.el);
  unsubscribe = subscribe(() => session.user && shell && shell.setUser(session.user, session.role));
  stopWatch = watchSession({ onTick: (ms, tone) => shell && shell.setCountdown(ms, tone) });
  clearInterval(overviewTimer);
  overviewTimer = setInterval(() => {
    if (!doc.hidden) refreshOverview();
  }, OVERVIEW_POLL_MS);
  refreshOverview();
  const wanted = parseHash(returnTo && !/^#\/(login|setup)/.test(returnTo) ? returnTo : '#/overview');
  returnTo = '';
  if (wanted.route === '') router.replace(['overview'], {});
  else router.replace(wanted.segments, wanted.query);
  route(router.current);
}

async function logout() {
  try {
    await api.post('/logout', {});
  } catch (e) {
    /* the cookie is gone either way */
  }
  signOut('loggedOut');
}

async function signOut(notice) {
  if (mode !== 'app') return;
  if (notice === 'expired') returnTo = globalThis.location.hash;
  clearSession();
  closeOverlays();
  await loadSetupStatus();
  showLogin(notice);
  router.replace(['login'], {});
}

// ---------------------------------------------------------------------------------------------------------------- routing

function notFound() {
  return emptyState({
    title: t('route.notFound.title'),
    text: t('route.notFound.text'),
    glyph: 'search',
    action: button({ label: t('route.notFound.back'), variant: 'primary', on: { click: () => router.go(['overview']) } }),
  });
}

function route(parsed) {
  if (mode === 'boot') return;
  const name = parsed.route;
  if (mode === 'setup') {
    if (name !== 'setup') showLogin('');
    return;
  }
  if (mode === 'login') {
    if (name === 'setup' && setupOpen) showSetup();
    else if (name !== 'login' && name !== '' && name !== 'setup' && !returnTo) returnTo = globalThis.location.hash;
    return;
  }
  if (name === '' || name === 'login' || name === 'setup') {
    router.replace(['overview'], {});
    return route(router.current);
  }
  setTitle(name);
  if (mounted && mounted.route === name) {
    if (mounted.onRoute) mounted.onRoute({ ...snapshot() });
    return undefined;
  }
  teardownView();
  shell.setActive(VIEWS[name] ? name : '');
  const container = h('div', { class: 'page', 'data-page': name });
  mount(shell.main, container);
  if (!VIEWS[name]) {
    mount(container, notFound());
  } else {
    try {
      const result = VIEWS[name](container, ctx);
      mounted = {
        route: name,
        destroy: typeof result === 'function' ? result : result && result.destroy,
        onRoute: result && typeof result.onRoute === 'function' ? result.onRoute : null,
      };
    } catch (e) {
      globalThis.console.error(e);
      mount(container, errorState({ message: t('error.view') }, () => {
        mounted = null;
        route(router.current);
      }));
    }
  }
  globalThis.scrollTo(0, 0);
  shell.main.focus({ preventScroll: true });
  return undefined;
}

function snapshot() {
  return { query: router.current.query, segments: router.current.segments, go: ctx.go, replaceHash: ctx.replaceHash, replaceQuery: ctx.replaceQuery, setBadge: ctx.setBadge, hubName: ctx.hubName, onOverview: ctx.onOverview, signedOut: ctx.signedOut };
}

// ---------------------------------------------------------------------------------------------------------------- boot

async function loadSetupStatus() {
  try {
    const status = await api.get('/setup/status', { anonymous: true });
    setupOpen = !!status.setupOpen;
    return status;
  } catch (e) {
    setupOpen = false;
    throw e;
  }
}

async function start() {
  mode = 'boot';
  mount(app, h('div', { class: 'boot', role: 'status', 'aria-label': t('common.loading') }, h('div', { class: 'skeleton skeleton-line short' })));
  let status;
  try {
    status = await loadSetupStatus();
  } catch (e) {
    return showBootError(e);
  }
  try {
    const dto = await api.get('/session', { anonymous: true });
    setSession(dto);
    mode = 'login'; // showApp() takes over from here
    returnTo = globalThis.location.hash;
    return showApp();
  } catch (e) {
    if (e && e.status && e.status !== 401 && e.status !== 419) return showBootError(e);
  }
  if (status.setupOpen && !status.hasAdmins && router.current.route !== 'login') return showSetup();
  return showLogin('');
}

doc.documentElement.lang = String(getLocale()).split('-')[0] || 'en';
setTitle('');
setEnrolHook((reason) => askToEnrol(reason, stepUp));
setApprovalHook((info) => showApprovalDialog(info));
router = createRouter(globalThis, (parsed) => route(parsed));
events.on('expired', () => signOut('expired'));
events.on('delegation-changed', () => refreshOverview());
installShortcuts({ navigate: (id) => mode === 'app' && router.go([id]) });
doc.addEventListener('visibilitychange', async () => {
  if (doc.hidden || mode !== 'app') return;
  try {
    setSession(await api.get('/session'));
  } catch (e) {
    /* a 401 has already signalled the expiry */
  }
});
start();
