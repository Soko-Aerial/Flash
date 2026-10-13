// Overview: what an administrator needs at a glance and what needs attention. Each card loads on its own, so one failing call never
// blanks the page. Metrics refresh every 15 s while the page is visible; the throughput sparkline is built in this browser from those
// samples (the hub keeps no history), so it starts empty and says so.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { formatNumber, formatBytes, formatDuration, relativeTime } from '../format.js';
import { pageHeader, card, stat, badge, button, banner, emptyState, segmentedBar, sparkline, timeEl, dl, withBusy } from '../ui/widgets.js';
import { loadInto } from '../ui/boundary.js';
import { delegationBody, delegationTone, delegationBadge } from './delegation.js';
import { toast, toastError } from '../ui/toast.js';
import { icon } from '../icons.js';

const POLL_MS = 15000;

export function mountOverview(container, ctx) {
  let stopped = false;
  const samples = []; // {t, bytes}
  const rates = [];

  const head = pageHeader({
    title: t('overview.title'),
    subtitle: t('overview.sub'),
    actions: button({ label: t('common.refresh'), icon: 'refresh', on: { click: () => reload() } }),
  });
  const alerts = h('div', { class: 'stack' });
  const tiles = h('div', { class: 'tiles' });
  const grid = h('div', { class: 'grid grid-2' });
  const healthBody = h('div');
  const devicesBody = h('div');
  const invitesBody = h('div');
  const relayBody = h('div', { class: 'stack' });
  const eventsBody = h('div');
  const delegationHost = h('div');
  const requestsHost = h('div');
  grid.append(
    card({ title: t('overview.card.health'), id: 'ov-health' }, healthBody),
    card({ title: t('overview.card.relay'), subtitle: t('overview.relay.sub'), id: 'ov-relay' }, relayBody),
    card({ title: t('overview.card.devices'), id: 'ov-devices' }, devicesBody),
    card({ title: t('overview.card.invites'), id: 'ov-invites' }, invitesBody),
  );
  const eventsCard = card({ title: t('overview.card.events'), subtitle: t('overview.events.sub'), id: 'ov-events', actions: h('a', { class: 'btn btn-ghost btn-sm', href: '#/audit' }, t('overview.events.all')) }, eventsBody);
  mount(container, head, alerts, delegationHost, tiles, requestsHost, grid, eventsCard);

  let overview = null;

  async function loadOverview(quiet) {
    await loadInto(tiles, {
      quiet,
      skeleton: 'tiles',
      rows: 6,
      load: () => api.get('/overview'),
      render: (o) => {
        overview = o;
        ctx.onOverview(o);
        renderOverview(o);
        return renderTiles(o);
      },
    });
  }

  function renderTiles(o) {
    const active = o.devicesByStatus.ACTIVE || 0;
    const revoked = o.devicesByStatus.REVOKED || 0;
    const d = o.delegation;
    return [
      stat({ label: t('overview.tile.devices'), value: formatNumber(active), hint: t('overview.tile.devices.hint', { n: revoked }), href: '#/devices' }),
      stat({ label: t('overview.tile.online'), value: formatNumber(o.onlineDevices), hint: t('overview.tile.online.hint'), tone: o.onlineDevices > 0 ? 'ok' : undefined }),
      stat({ label: t('overview.tile.invites'), value: formatNumber(o.invites.pending), hint: t('overview.tile.invites.hint', { n: o.invites.used }), href: '#/invites' }),
      stat({ label: t('overview.tile.requests'), value: formatNumber(o.pendingJoinRequests), hint: o.pendingJoinRequests ? t('overview.tile.requests.act') : t('overview.tile.requests.none'), tone: o.pendingJoinRequests ? 'warn' : undefined, href: '#/organization?tab=requests' }),
      stat({ label: t('overview.tile.queue'), value: formatNumber(o.queueDepth), hint: t('overview.tile.queue.hint', { size: formatBytes(o.queueBytes) }) }),
      stat({
        label: t('overview.tile.delegation'),
        value: d.status === 'AWAITING' ? t('overview.tile.delegation.waiting') : d.daysLeft !== null && d.daysLeft !== undefined ? t('overview.tile.delegation.days', { n: d.daysLeft }) : '–',
        hint: d.status === 'AWAITING' ? t('overview.tile.delegation.waiting.hint') : t('overview.tile.delegation.hint'),
        tone: delegationTone(d) === 'ok' ? undefined : delegationTone(d),
        href: '#/security?tab=delegation',
      }),
    ];
  }

  function renderOverview(o) {
    mount(alerts);
    if (o.delegation.status === 'AWAITING') {
      mount(delegationHost, card({ title: t('overview.delegation.title'), className: 'card-attention', id: 'ov-del' }, delegationBody(o.delegation, { compact: true, onChanged: () => reload() })), h('p', { class: 'muted small', text: t('overview.delegation.more') }));
    } else if (o.delegation.warning) {
      mount(delegationHost, banner({ tone: delegationTone(o.delegation) === 'bad' ? 'bad' : 'warn', title: t('overview.delegation.expiring'), text: o.delegation.warning, actions: h('a', { class: 'btn btn-secondary btn-sm', href: '#/security?tab=delegation' }, t('overview.delegation.manage')) }));
    } else mount(delegationHost);
    if (o.pendingJoinRequests > 0) {
      mount(requestsHost, banner({ tone: 'info', title: t('overview.requests.title', { n: o.pendingJoinRequests }), text: t('overview.requests.text'), actions: h('a', { class: 'btn btn-primary btn-sm', href: '#/organization?tab=requests' }, t('overview.requests.review')) }));
    } else mount(requestsHost);
    renderHealth(o);
    renderDevices(o);
    renderInvites(o);
  }

  function renderHealth(o) {
    const d = o.delegation;
    const rev = o.revocations;
    const chain = h('span', { class: 'muted', text: t('overview.health.chain.unchecked') });
    const verify = button({ label: t('overview.health.verify'), size: 'sm', icon: 'check' });
    verify.addEventListener('click', () =>
      withBusy(verify, async () => {
        try {
          const v = await api.get('/audit/verify');
          mount(chain, v.ok ? badge(t('overview.health.chain.ok', { n: v.entries }), 'ok') : badge(t('overview.health.chain.broken', { n: v.firstBadIndex ?? '?' }), 'bad'));
          if (!v.ok) toast(t('audit.verify.brokenToast'), { kind: 'error' });
        } catch (e) {
          toastError(e);
        }
      }),
    );
    mount(
      healthBody,
      dl([
        [t('overview.health.hub'), h('span', {}, o.hubName)],
        [t('overview.health.delegation'), delegationBadge(d)],
        [t('overview.health.revocations'), rev ? h('span', {}, t('overview.health.revocations.v', { version: rev.listVersion, serials: rev.revokedSerials + rev.revokedDeviceIds }), rev.unpublishedChanges ? ' ' : null, rev.unpublishedChanges ? badge(t('overview.health.unpublished'), 'warn') : null) : h('span', { class: 'muted', text: t('overview.health.revocations.none') })],
        [t('overview.health.audit'), h('span', {}, t('overview.health.audit.n', { n: o.audit.entries }))],
        [t('overview.health.chain'), h('span', { class: 'inline' }, chain, verify)],
        [t('overview.health.quota'), h('span', {}, t('overview.health.quota.v', { used: (o.devicesByStatus.ACTIVE || 0), quota: o.deviceQuota }))],
        [t('overview.health.structure'), h('span', {}, t('overview.health.structure.v', { departments: o.departments, groups: o.groups }))],
      ]),
    );
  }

  function renderDevices(o) {
    const s = o.devicesByStatus;
    const total = (s.ACTIVE || 0) + (s.REVOKED || 0) + (s.UNENROLLED || 0);
    if (total === 0) {
      mount(devicesBody, emptyState({ title: t('overview.devices.empty.title'), text: t('overview.devices.empty.text'), glyph: 'devices', action: h('a', { class: 'btn btn-primary', href: '#/invites' }, t('overview.devices.empty.action')) }));
      return;
    }
    mount(
      devicesBody,
      segmentedBar([
        { label: t('status.active'), value: s.ACTIVE || 0, tone: 'ok' },
        { label: t('status.revoked'), value: s.REVOKED || 0, tone: 'bad' },
        { label: t('status.unenrolled'), value: s.UNENROLLED || 0, tone: 'warn' },
      ]),
      h('ul', { class: 'legend' }, [
        ['ok', t('status.active'), s.ACTIVE || 0],
        ['bad', t('status.revoked'), s.REVOKED || 0],
        ['warn', t('status.unenrolled'), s.UNENROLLED || 0],
      ].map(([tone, label, n]) => h('li', {}, h('span', { class: 'swatch swatch-' + tone, 'aria-hidden': 'true' }), h('span', { text: label }), h('span', { class: 'num strong', text: formatNumber(n) })))),
    );
  }

  function renderInvites(o) {
    const i = o.invites;
    const total = i.pending + i.used + i.expired + i.revoked;
    if (total === 0) {
      mount(invitesBody, emptyState({ title: t('overview.invites.empty.title'), text: t('overview.invites.empty.text'), glyph: 'invites', action: h('a', { class: 'btn btn-primary', href: '#/invites' }, t('overview.invites.empty.action')) }));
      return;
    }
    mount(
      invitesBody,
      segmentedBar([
        { label: t('invite.state.pending'), value: i.pending, tone: 'accent' },
        { label: t('invite.state.used'), value: i.used, tone: 'ok' },
        { label: t('invite.state.expired'), value: i.expired, tone: 'warn' },
        { label: t('invite.state.revoked'), value: i.revoked, tone: 'bad' },
      ]),
      h('ul', { class: 'legend' }, [
        ['accent', t('invite.state.pending'), i.pending],
        ['ok', t('invite.state.used'), i.used],
        ['warn', t('invite.state.expired'), i.expired],
        ['bad', t('invite.state.revoked'), i.revoked],
      ].map(([tone, label, n]) => h('li', {}, h('span', { class: 'swatch swatch-' + tone, 'aria-hidden': 'true' }), h('span', { text: label }), h('span', { class: 'num strong', text: formatNumber(n) })))),
    );
  }

  function renderRelay(m) {
    const now = m.serverTimeMs;
    const bytes = m.bytesIn + m.bytesOut;
    const last = samples[samples.length - 1];
    if (!last || last.t !== now) {
      if (last && now > last.t && bytes >= last.bytes) rates.push(((bytes - last.bytes) / (now - last.t)) * 1000);
      samples.push({ t: now, bytes });
      if (samples.length > 80) samples.shift();
      if (rates.length > 79) rates.shift();
    }
    const rate = rates.length ? rates[rates.length - 1] : null;
    mount(
      relayBody,
      h(
        'div',
        { class: 'relay-top' },
        h('div', {}, h('div', { class: 'stat-label', text: t('overview.relay.rate') }), h('div', { class: 'stat-value num', text: rate === null ? '–' : formatBytes(rate) + '/s' })),
        sparkline(rates, { width: 220, height: 48, label: t('overview.relay.spark') }),
      ),
      rates.length < 2 ? h('p', { class: 'muted small', text: t('overview.relay.collecting') }) : null,
      dl([
        [t('overview.relay.delivered'), h('span', { class: 'num', text: formatNumber(m.messagesDelivered) })],
        [t('overview.relay.queued'), h('span', { class: 'num', text: formatNumber(m.messagesQueued) })],
        [t('overview.relay.rejected'), h('span', { class: 'num', text: formatNumber(m.messagesRejected) })],
        [t('overview.relay.oldest'), m.oldestWaitingMs !== null && m.oldestWaitingMs !== undefined ? h('span', { class: 'num', text: formatDuration(m.oldestWaitingMs) }) : h('span', { class: 'muted', text: t('overview.relay.nothingWaiting') })],
        [t('overview.relay.uptime'), h('span', { class: 'num', text: formatDuration(m.uptimeSeconds * 1000) })],
        [t('overview.relay.handshakes'), h('span', { class: 'num', text: t('overview.relay.handshakes.v', { ok: formatNumber(m.handshakesOk), refused: formatNumber(m.handshakesRefused) }) })],
      ]),
    );
  }

  async function loadMetrics(quiet) {
    await loadInto(relayBody, { quiet, load: () => api.get('/metrics'), render: (m) => { renderRelay(m); return Array.from(relayBody.childNodes); }, rows: 4 });
  }

  async function loadEvents(quiet) {
    await loadInto(eventsBody, {
      quiet,
      rows: 5,
      load: () => api.get('/audit', { query: { order: 'desc', limit: 10 } }),
      render: (page) => {
        if (!page.entries.length) return emptyState({ title: t('overview.events.empty.title'), text: t('overview.events.empty.text'), glyph: 'audit' });
        return h('ul', { class: 'event-list' }, page.entries.map((e) => h('li', {}, h('span', { class: 'event-action' }, auditTone(e.action), h('span', { text: e.action })), h('span', { class: 'event-detail', text: e.actor + (e.details ? ' · ' + e.details : '') }), h('span', { class: 'event-time' }, timeEl(e.timestampMs)))));
      },
    });
  }

  function auditTone(action) {
    if (/failed|locked|refused|revoke|disable|reset/.test(action)) return h('span', { class: 'event-flag' }, icon('warning', { size: 16 }), h('span', { class: 'sr-only', text: t('overview.events.attention') }));
    return null;
  }

  async function reload(quiet = false) {
    await Promise.all([loadOverview(quiet), loadMetrics(quiet), loadEvents(quiet)]);
  }

  reload();
  const timer = setInterval(() => {
    if (stopped || globalThis.document.hidden) return;
    if (tiles.contains(globalThis.document.activeElement)) return;
    loadOverview(true);
    loadMetrics(true);
  }, POLL_MS);
  return () => {
    stopped = true;
    clearInterval(timer);
  };
}
