// Overview: the hub's health, the decision queue, and the last observed relay samples. The hub keeps no history, so the
// strip is built in this browser and says so; every figure otherwise comes from /overview or /metrics.

import { h } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { formatNumber, formatBytes, formatDuration, formatDate, relativeTime } from '../format.js';
import { loadInto } from '../ui/boundary.js';

const POLL_MS = 15000;
const SAMPLE_LIMIT = 60;

function initials(name) {
  const parts = String(name || '').trim().split(/\s+/).filter(Boolean);
  return ((parts[0] || '?')[0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

function clockLabel(ms) {
  try {
    return new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(ms));
  } catch (e) {
    return '';
  }
}

function healthCell({ label, value, hint, lead = false, unit = null }) {
  return h(
    'div',
    { class: ['fx-health-cell', lead ? 'is-lead' : ''] },
    h('div', { class: 'fx-health-label', text: label }),
    h('div', { class: 'fx-health-value' }, value, unit ? h('span', { class: 'fx-health-unit', text: unit }) : null),
    h('div', { class: 'fx-health-hint', text: hint }),
  );
}

function waitRow({ glyph, title, sub, value, href, warn = false }) {
  return h(
    'a',
    { class: 'fx-wait-row', href },
    h('span', { class: 'fx-wait-icon' }, icon(glyph, { size: 17 })),
    h('span', { class: 'fx-wait-text' }, h('span', { class: 'fx-wait-title', text: title }), h('span', { class: 'fx-wait-sub', text: sub })),
    h('span', { class: ['fx-wait-value', warn ? 'is-warn' : ''], text: value }),
    h('span', { class: 'fx-wait-chevron' }, icon('chevronRight', { size: 15 })),
  );
}

function ledgerHead(title, linkLabel, href) {
  return h(
    'div',
    { class: 'fx-ledger-head' },
    h('h2', { class: 'fx-ledger-title', text: title }),
    h('a', { class: 'fx-panel-link', href, text: linkLabel }),
  );
}

function deviceRow(device) {
  return h(
    'a',
    { class: 'fx-mini-row', href: '#/devices' },
    h('span', { class: 'fx-avatar', text: initials(device.name) }),
    h(
      'span',
      { class: 'fx-mini-main' },
      h('span', { class: 'fx-mini-title', text: device.name }),
      h('span', { class: 'fx-mini-sub', text: device.department || t('devices.noDepartment') }),
    ),
    h('span', { class: 'fx-mini-end' }, h('span', { class: ['dot', device.online ? 'fx-dot-ok' : 'fx-dot-off'] }), device.online ? t('devices.online') : relativeTime(device.lastSeenAt)),
  );
}

function inviteRow(invite) {
  return h(
    'a',
    { class: 'fx-mini-row', href: '#/invites' },
    h(
      'span',
      { class: 'fx-mini-main' },
      h('span', { class: 'fx-mini-title', text: invite.note || t('overview.new.untitledInvite') }),
      h('span', { class: 'fx-mini-sub', text: [invite.department, t('role.' + String(invite.role || '').toLowerCase())].filter(Boolean).join(' · ') }),
    ),
    h('span', { class: 'badge badge-neutral', text: t('overview.new.waitingState') }),
  );
}

const ACTIVITY_LABELS = {
  'device.revoke': 'overview.action.deviceRevoke',
  'join.approve': 'overview.action.joinApprove',
  'join.decline': 'overview.action.joinDecline',
  'admin.session.start': 'overview.action.adminSessionStart',
  'delegation.verify': 'overview.action.delegationVerify',
  'invite.create': 'overview.action.inviteCreate',
  'device.enroll': 'overview.action.deviceEnroll',
};

function activityRow(entry) {
  const key = ACTIVITY_LABELS[entry.action];
  return h(
    'a',
    { class: 'fx-mini-row', href: '#/audit' },
    h('span', { class: 'fx-mini-main' }, h('span', { class: 'fx-mini-title', text: key ? t(key) : entry.action }), h('span', { class: 'fx-mini-sub', text: entry.details || entry.actor || '' })),
    h('span', { class: 'fx-mini-end', text: relativeTime(entry.timestampMs) }),
  );
}

export function mountOverview(container, ctx) {
  let stopped = false;
  const samples = [];
  let lastMetrics = null;
  let overviewPromise = null;
  let metricsPromise = null;

  const title = h('h1', { class: 'page-title', id: 'page-title', tabindex: '-1', text: t('overview.title') });
  const lede = h('p', { class: 'page-sub fx-lede', text: t('common.loading') });
  const reviewLabel = h('span', { class: 'btn-label', text: t('overview.new.reviewAll') });
  const review = h('a', { class: 'btn btn-primary', href: '#/requests' }, reviewLabel, icon('chevronRight', { size: 15 }));
  const create = h('a', { class: 'btn btn-secondary', href: '#/invites' }, icon('plus', { size: 15 }), h('span', { class: 'btn-label', text: t('overview.new.createInvite') }));
  const head = h(
    'div',
    { class: 'page-head' },
    h('div', { class: 'page-titles' }, title, lede),
    h('div', { class: 'page-actions' }, create, review),
  );

  const healthHost = h('div', { class: 'fx-health' });
  const health = h('section', { class: 'fx-panel', 'aria-label': t('overview.card.health') }, healthHost);
  const relayHost = h('div');
  const relay = h('section', { class: 'fx-panel fx-relay', 'aria-labelledby': 'fx-relay-title' }, relayHost);
  const waitingHost = h('div');
  const waiting = h('section', { class: 'fx-panel fx-waiting', 'aria-labelledby': 'fx-waiting-title' }, waitingHost);

  const devicesHost = h('div');
  const invitesHost = h('div');
  const activityHost = h('div');
  const ledger = h(
    'section',
    { class: 'fx-panel fx-ledger', 'aria-label': t('overview.new.activity') },
    h('div', { class: 'fx-ledger-col' }, ledgerHead(t('overview.new.devices'), t('overview.new.viewAll'), '#/devices'), devicesHost),
    h('div', { class: 'fx-ledger-col' }, ledgerHead(t('overview.new.invites'), t('nav.invites'), '#/invites'), invitesHost),
    h('div', { class: 'fx-ledger-col' }, ledgerHead(t('overview.new.activity'), t('overview.new.audit'), '#/audit'), activityHost),
  );

  container.append(head, health, h('div', { class: 'fx-overview-grid' }, relay, waiting, ledger));

  function updateHead(o) {
    const active = o.devicesByStatus?.ACTIVE || 0;
    const online = o.onlineDevices || 0;
    const waitingCount = o.pendingJoinRequests || 0;
    lede.replaceChildren(
      h('span', { class: 'fx-inline' }, h('span', { class: 'dot fx-dot-ok' }), h('span', { text: t('overview.new.online', { online, active }) })),
      h('span', { class: 'fx-finding' }, h('strong', { text: t('overview.new.waiting', { n: waitingCount }) })),
    );
    reviewLabel.textContent = waitingCount ? t('overview.new.review', { n: waitingCount }) : t('overview.new.reviewAll');
  }

  function overview() {
    if (!overviewPromise) {
      overviewPromise = api.get('/overview').then((o) => {
        ctx.onOverview(o);
        updateHead(o);
        return o;
      });
    }
    return overviewPromise;
  }

  function metrics() {
    if (!metricsPromise) metricsPromise = api.get('/metrics');
    return metricsPromise;
  }

  function live() {
    return Promise.all([overview(), metrics()]);
  }

  function renderHealth(o, m) {
    const active = o.devicesByStatus?.ACTIVE || 0;
    const revoked = o.devicesByStatus?.REVOKED || 0;
    const unenrolled = o.devicesByStatus?.UNENROLLED || 0;
    const uptime = m ? formatDuration((m.uptimeSeconds || 0) * 1000) : null;
    return [
      healthCell({ label: t('overview.tile.online'), value: formatNumber(o.onlineDevices || 0), hint: t('overview.new.ofActive', { n: formatNumber(active) }), lead: true }),
      healthCell({ label: t('overview.new.activeDevices'), value: formatNumber(active), hint: t('overview.new.revoked', { revoked: formatNumber(revoked), unenrolled: formatNumber(unenrolled) }) }),
      healthCell({ label: t('overview.new.relayQueue'), value: formatNumber(o.queueDepth || 0), hint: o.queueBytes ? formatBytes(o.queueBytes) : t('overview.new.queueHint') }),
      uptime
        ? healthCell({ label: t('overview.new.uptime'), value: uptime, hint: t('overview.new.uptimeHint') })
        : healthCell({ label: t('overview.health.quota'), value: o.deviceQuota ? formatNumber(active) : '–', hint: o.deviceQuota ? t('overview.health.quota.v', { used: formatNumber(active), quota: formatNumber(o.deviceQuota) }) : t('overview.health.revocations.none') }),
    ];
  }

  function renderRelay(m) {
    const now = m.serverTimeMs;
    if (lastMetrics && now > lastMetrics.t && m.messagesDelivered >= lastMetrics.delivered) {
      const minutes = (now - lastMetrics.t) / 60000;
      const rate = minutes > 0 ? (m.messagesDelivered - lastMetrics.delivered) / minutes : 0;
      samples.push({ t: now, value: rate });
      if (samples.length > SAMPLE_LIMIT) samples.shift();
    }
    lastMetrics = { t: now, delivered: m.messagesDelivered };

    const filled = samples.slice(-SAMPLE_LIMIT);
    const maxValue = Math.max(10, ...filled.map((s) => s.value));
    const yMax = Math.max(10, Math.ceil(maxValue / 10) * 10);
    const bars = [];
    for (let i = 0; i < SAMPLE_LIMIT; i += 1) {
      const sample = filled[i - (SAMPLE_LIMIT - filled.length)];
      const height = sample ? Math.max(sample.value > 0 ? 2 : 0, (sample.value / yMax) * 100) : 0;
      bars.push(
        h('span', {
          class: ['fx-pulse-bar', sample ? '' : 'is-empty'],
          vars: { h: height + '%' },
          title: sample ? `${formatNumber(Math.round(sample.value))} / min · ${clockLabel(sample.t)}` : '',
        }),
      );
    }
    const labels = filled.length
      ? [filled[0].t, filled[Math.floor(filled.length / 4)]?.t, filled[Math.floor(filled.length / 2)]?.t, filled[Math.floor((filled.length * 3) / 4)]?.t, filled[filled.length - 1].t].map(clockLabel)
      : ['', '', '', '', ''];

    return [
      h(
        'div',
        { class: 'fx-relay-head' },
        h('div', {}, h('h2', { class: 'fx-panel-title', id: 'fx-relay-title', text: t('overview.new.relayTitle') }), h('p', { class: 'fx-panel-sub', text: t('overview.new.relaySub') })),
        h('span', { class: 'fx-live' }, h('span', { class: 'dot' }), h('span', { text: 'Live · ' + clockLabel(now) })),
      ),
      h('div', { class: 'fx-relay-figure' }, formatNumber(m.messagesDelivered || 0), h('span', { class: 'unit', text: t('overview.new.deliveredTotal') })),
      h(
        'div',
        { class: 'fx-pulse-wrap' },
        h(
          'div',
          { class: 'fx-pulse-chart', role: 'img', 'aria-label': t('overview.new.chart') },
          h('div', { class: 'fx-pulse-axis', 'aria-hidden': 'true' }, [yMax, Math.round(yMax / 2), 0].map((v) => h('span', { text: formatNumber(v) }))),
          h(
            'div',
            { class: 'fx-pulse-plot' },
            h('div', { class: 'fx-pulse-grid', 'aria-hidden': 'true' }, [0, 50, 100].map((top) => h('span', { class: 'fx-pulse-gridline', vars: { top: top + '%' } }))),
            h('div', { class: 'fx-pulse-bars' }, bars),
            h('div', { class: 'fx-pulse-x', 'aria-hidden': 'true' }, labels.map((text) => h('span', { text }))),
          ),
        ),
        samples.length ? h('p', { class: 'fx-pulse-empty', text: t('overview.new.sampleNote') }) : h('p', { class: 'fx-pulse-empty', text: t('overview.new.collecting') }),
      ),
      h(
        'div',
        { class: 'fx-relay-facts' },
        h('div', { class: 'fx-relay-fact' }, h('div', { class: 'fx-relay-fact-label', text: t('overview.new.queuedNow') }), h('div', { class: 'fx-relay-fact-value' }, formatNumber(m.queueDepth || 0))),
        h('div', { class: 'fx-relay-fact' }, h('div', { class: 'fx-relay-fact-label', text: t('overview.new.rejected') }), h('div', { class: 'fx-relay-fact-value' }, formatNumber(m.messagesRejected || 0))),
        h('div', { class: 'fx-relay-fact' }, h('div', { class: 'fx-relay-fact-label', text: t('overview.new.oldest') }), h('div', { class: 'fx-relay-fact-value' }, m.oldestWaitingMs ? formatDuration(m.oldestWaitingMs) : t('overview.new.nothingWaiting'))),
      ),
    ];
  }

  function renderWaiting(o) {
    const d = o.delegation || {};
    const days = d.daysLeft;
    const delegationValue = d.status === 'AWAITING' ? t('overview.tile.delegation.waiting') : days !== null && days !== undefined ? t('overview.tile.delegation.days', { n: days }) : '–';
    const delegationSub = d.status === 'AWAITING' ? t('overview.tile.delegation.waiting.hint') : d.validTo ? t('overview.new.delegationSub', { date: formatDate(d.validTo) }) : t('overview.tile.delegation.hint');
    return [
      h(
        'div',
        { class: 'fx-panel-head' },
        h('div', {}, h('h2', { class: 'fx-panel-title', id: 'fx-waiting-title', text: t('overview.new.waitingTitle') }), h('p', { class: 'fx-panel-sub', text: t('overview.new.waitingSub') })),
      ),
      h(
        'div',
        { class: 'fx-waiting-rows' },
        waitRow({ glyph: 'requests', title: t('overview.tile.requests'), sub: t('overview.new.joinSub'), value: formatNumber(o.pendingJoinRequests || 0), href: '#/requests' }),
        waitRow({ glyph: 'lock', title: t('overview.tile.delegation'), sub: delegationSub, value: delegationValue, warn: d.status !== 'ACTIVE' || (days !== null && days !== undefined && days < 14), href: '#/security?tab=delegation' }),
        waitRow({ glyph: 'audit', title: t('overview.health.chain'), sub: t('overview.new.auditSub'), value: t('overview.new.check'), href: '#/audit' }),
      ),
      h('p', { class: 'fx-wait-note', text: t('overview.new.waitNote') }),
    ];
  }

  function renderDevices(rows) {
    const online = (rows || []).filter((d) => d.online).slice(0, 3);
    if (!online.length) return h('p', { class: 'fx-empty', text: t('overview.new.noDevices') });
    return online.map(deviceRow);
  }

  function renderInvites(rows) {
    const pending = (rows || []).filter((i) => i.state === 'PENDING').slice(0, 3);
    if (!pending.length) return h('p', { class: 'fx-empty', text: t('overview.new.noInvites') });
    return pending.map(inviteRow);
  }

  function renderActivity(page) {
    const entries = (page && page.entries) || [];
    if (!entries.length) return h('p', { class: 'fx-empty', text: t('overview.new.noActivity') });
    return entries.slice(0, 3).map(activityRow);
  }

  function loadAll(quiet = false) {
    loadInto(healthHost, {
      quiet,
      skeleton: 'tiles',
      rows: 4,
      load: async () => ({ o: await overview(), m: await metrics().catch(() => null) }),
      render: ({ o, m }) => renderHealth(o, m),
    });
    loadInto(relayHost, { quiet, rows: 4, load: live, render: ([, m]) => renderRelay(m) });
    loadInto(waitingHost, { quiet, rows: 3, load: overview, render: (o) => renderWaiting(o) });
    loadInto(devicesHost, { quiet, rows: 3, load: () => api.get('/devices', { query: { limit: 24 } }), render: renderDevices });
    loadInto(invitesHost, { quiet, rows: 3, load: () => api.get('/invites', { query: { limit: 100 } }), render: renderInvites });
    loadInto(activityHost, { quiet, rows: 3, load: () => api.get('/audit', { query: { order: 'desc', limit: 3 } }), render: renderActivity });
  }

  function reload(quiet = false) {
    overviewPromise = null;
    metricsPromise = null;
    loadAll(quiet);
  }

  reload();
  const timer = setInterval(() => {
    if (stopped || globalThis.document.hidden) return;
    reload(true);
  }, POLL_MS);
  return () => {
    stopped = true;
    clearInterval(timer);
  };
}
