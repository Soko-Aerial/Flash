import { h } from '../dom.js';
import { icon } from '../icons.js';
import { data, NOW } from '../data.js';
import { state, deviceStatus, deviceIsOnline } from '../store.js';
import { n, initials, clock, relative, dayMonth } from '../format.js';
import { pageHead } from './common.js';

function healthCell({ label, value, unit = '', hint, lead = false }) {
  return h('div', { class: `health-cell${lead ? ' is-lead' : ''}` },
    h('div', { class: 'health-label' }, label),
    h('div', { class: 'health-value' }, typeof value === 'number' ? n(value) : value, unit ? h('span', { class: 'health-unit', text: unit }) : null),
    h('div', { class: 'health-hint', text: hint }),
  );
}

function pulseStrip() {
  const values = data.relay.perMinute;
  const max = Math.max(...values);
  const axisMax = Math.ceil(max / 10) * 10;
  const plotMax = axisMax;
  const bars = values.map((value, index) => {
    const pct = Math.max(3, (value / plotMax) * 100);
    const totalMinutes = data.relay.startedHour * 60 + data.relay.startedMinute + index;
    const hh = Math.floor(totalMinutes / 60) % 24;
    const mm = totalMinutes % 60;
    const label = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')} · ${n(value)} delivered`;
    const partial = index === values.length - 1;
    return h('span', {
      class: `pulse-bar${partial ? ' is-quiet' : ''}`,
      style: { height: `${pct}%` },
      title: `${label}${partial ? ' (minute in progress)' : ''}`,
    });
  });

  const annotationPct = (data.relay.annotation.index / (values.length - 1)) * 100;
  const yLabels = [axisMax, axisMax / 2, 0];

  return h('div', { class: 'pulse-wrap' },
    h('div', { class: 'pulse-annot', 'aria-hidden': 'true' },
      h('span', { style: { left: `${annotationPct}%` }, text: data.relay.annotation.label }),
    ),
    h('div', {
      class: 'pulse-chart',
      role: 'img',
      'aria-label': `Relay traffic for the last 60 minutes: ${n(data.relay.deliveredLastHour)} messages delivered, median ${data.relay.medianMs} milliseconds, ${data.relay.queuedNow} waiting now. The morning shift change is marked at 09:12.`,
    },
      h('div', { class: 'pulse-axis', 'aria-hidden': 'true' }, yLabels.map((v) => h('span', { text: n(v) }))),
      h('div', { class: 'pulse-plot' },
        h('div', { class: 'pulse-grid', 'aria-hidden': 'true' },
          [0, 50, 100].map((top) => h('span', { class: 'pulse-gridline', style: { top: `${top}%` } })),
        ),
        h('div', { class: 'pulse-bars' }, bars),
        h('div', { class: 'pulse-x', 'aria-hidden': 'true' },
          h('span', { text: '08:45' }),
          h('span', { text: '09:00' }),
          h('span', { text: '09:15' }),
          h('span', { text: '09:30' }),
          h('span', { text: '09:45' }),
        ),
      ),
    ),
  );
}

function waitRow({ iconName, title, sub, value, warn = false, href }) {
  return h('a', { class: 'wait-row', href },
    h('span', { class: 'wait-icon' }, icon(iconName, { size: 17 })),
    h('span', { class: 'wait-text' },
      h('span', { class: 'wait-title', text: title }),
      h('span', { class: 'wait-sub', text: sub }),
    ),
    h('span', { class: `wait-value${warn ? ' is-warn' : ''}`, text: value }),
    h('span', { class: 'wait-chevron' }, icon('chevronRight', { size: 15 })),
  );
}

function deviceRow(device) {
  const status = deviceStatus(device);
  const online = status === 'ACTIVE' && deviceIsOnline(device);
  return h('a', { class: 'mini-row', href: '#/devices' },
    h('span', { class: 'avatar', text: initials(device.name) }),
    h('span', { class: 'mini-main' },
      h('span', { class: 'mini-title', text: device.name }),
      h('span', { class: 'mini-sub', text: `${device.department} · ${device.platform}` }),
    ),
    h('span', { class: 'mini-end' },
      h('span', { class: `dot ${online ? 'dot-ok' : 'dot-off'}`, 'aria-hidden': 'true' }),
      h('span', { text: online ? 'now' : relative(device.lastSeenMinutes) }),
    ),
  );
}

function inviteRow(invite) {
  return h('a', { class: 'mini-row', href: '#/invites' },
    h('span', { class: 'mini-main' },
      h('span', { class: 'mini-title', text: invite.name }),
      h('span', { class: 'mini-sub', text: `${invite.department} · ${invite.role === 'dept_admin' ? 'Department admin' : 'Member'}` }),
    ),
    h('span', { class: 'chip chip-neutral mini-chip', text: 'Waiting' }),
  );
}

function activityRow(entry) {
  return h('a', { class: 'mini-row', href: '#/audit' },
    h('span', { class: 'mini-main' },
      h('span', { class: 'mini-title', text: entry.label }),
      h('span', { class: 'mini-sub', text: entry.detail }),
    ),
    h('span', { class: 'mini-end', text: entry.minutesAgo < 1 ? 'now' : entry.minutesAgo < 60 ? `${entry.minutesAgo} min` : relative(entry.minutesAgo) }),
  );
}

export function mountOverview(container, ctx) {
  const devices = data.devices;
  const active = devices.filter((d) => deviceStatus(d) === 'ACTIVE').length;
  const online = devices.filter((d) => deviceStatus(d) === 'ACTIVE' && deviceIsOnline(d)).length;
  const revoked = devices.filter((d) => deviceStatus(d) === 'REVOKED').length;
  const unenrolled = devices.filter((d) => deviceStatus(d) === 'UNENROLLED').length;
  const requests = state.requests.length;
  const invitesWaiting = state.invites.filter((i) => i.state === 'PENDING').length;

  const seenDepartments = new Set();
  const featured = [];
  for (const device of devices) {
    if (deviceIsOnline(device) && deviceStatus(device) === 'ACTIVE' && !seenDepartments.has(device.department)) {
      seenDepartments.add(device.department);
      featured.push(device);
    }
    if (featured.length === 3) break;
  }

  const newestInvites = state.invites.filter((i) => i.state === 'PENDING').slice(0, 3);
  const recent = data.audit.entries.slice(0, 3);

  const lede = [
    h('span', { class: 'lede-sentence' },
      h('span', { class: 'dot dot-ok' }),
      ' ',
      `Healthy since ${data.hub.healthySince}, ${online} of ${active} devices online.`,
    ),
    h('span', { class: 'finding' }, h('strong', { text: `${requests} ${requests === 1 ? 'person is' : 'people are'}` }), ` waiting to join${requests ? ', the oldest for 2 days' : ''}.`),
  ];

  const actions = [
    h('a', { class: 'btn btn-secondary', href: '#/invites' }, icon('plus', { size: 15 }), 'Create invite'),
    requests
      ? h('a', { class: 'btn btn-primary', href: '#/requests' }, `Review ${requests} requests`, icon('arrowRight', { size: 15 }))
      : h('span', { class: 'chip chip-ok', text: 'No requests waiting' }),
  ];

  const head = pageHead({
    title: 'Overview',
    lede,
    actions,
  });

  const health = h('section', { class: 'health-strip panel', 'aria-label': 'Hub health at a glance' },
    healthCell({ label: 'Online now', value: online, hint: `of ${active} active devices`, lead: true }),
    healthCell({ label: 'Active devices', value: active, hint: `${revoked} revoked · ${unenrolled} not enrolled` }),
    healthCell({ label: 'Relay queue', value: data.relay.queuedNow, hint: 'nothing waiting to deliver' }),
    healthCell({ label: 'Uptime', value: '6 d 4 h', hint: 'since 30 October, 05:35' }),

  );

  const relay = h('section', { class: 'panel relay-panel', 'aria-labelledby': 'relay-title' },
    h('div', { class: 'relay-head' },
      h('div', {},
        h('h2', { class: 'panel-title', id: 'relay-title', text: 'Relay traffic' }),
        h('p', { class: 'panel-sub', text: 'Messages delivered between devices, last 60 minutes' }),
      ),
      h('span', { class: 'relay-live' },
        h('span', { class: 'dot dot-live', 'aria-hidden': 'true' }),
        h('span', { text: `Live · ${clock(NOW)}` }),
      ),
    ),
    h('div', { class: 'relay-figure' }, n(data.relay.deliveredLastHour), h('span', { class: 'unit', text: 'messages delivered in the last hour' })),
    pulseStrip(),
    h('div', { class: 'relay-facts' },
      h('div', { class: 'relay-fact' }, h('div', { class: 'relay-fact-label', text: 'Refused last hour' }), h('div', { class: 'relay-fact-value' }, '0')),
      h('div', { class: 'relay-fact' }, h('div', { class: 'relay-fact-label', text: 'Median delivery' }), h('div', { class: 'relay-fact-value' }, '41 ', h('span', { class: 'unit', text: 'ms' }))),
      h('div', { class: 'relay-fact' }, h('div', { class: 'relay-fact-label', text: 'p95 delivery' }), h('div', { class: 'relay-fact-value' }, '118 ', h('span', { class: 'unit', text: 'ms' }))),
    ),
  );

  const waiting = h('section', { class: 'panel waiting-panel', 'aria-labelledby': 'waiting-title' },
    h('div', { class: 'relay-head', style: { paddingBottom: '2px' } },
      h('div', {},
        h('h2', { class: 'panel-title', id: 'waiting-title', text: 'Waiting on you' }),
        h('p', { class: 'panel-sub', text: 'Three checks' }),
      ),
    ),
    h('div', { class: 'waiting-rows' },
      waitRow({ iconName: 'requests', title: 'Join requests', sub: 'Oldest 2 days', value: String(requests), href: '#/requests' }),
      waitRow({ iconName: 'key', title: 'Delegation', sub: 'Expires 14 November', value: `${data.delegation.daysLeft} days`, warn: data.delegation.daysLeft < 14, href: '#/security?tab=delegation' }),
      waitRow({ iconName: 'audit', title: 'Audit chain', sub: `Last verified ${dayMonth(state.auditVerifiedAt)}`, value: 'Intact', href: '#/audit' }),
    ),
    h('p', { class: 'wait-note', text: 'Nothing is blocked. The relay is delivering normally.' }),
  );

  const ledger = h('section', { class: 'panel ledger-panel', 'aria-label': 'Devices, invites and recent activity' },
    h('div', { class: 'ledger-col' },
      h('div', { class: 'ledger-head' },
        h('h2', { class: 'ledger-title', text: 'Devices online' }),
        h('a', { class: 'text-action', href: '#/devices' }, `View all ${active}`),
      ),
      featured.map(deviceRow),
    ),
    h('div', { class: 'ledger-col' },
      h('div', { class: 'ledger-head' },
        h('h2', { class: 'ledger-title', text: 'Invites waiting' }),
        h('a', { class: 'text-action', href: '#/invites' }, `${invitesWaiting} waiting`),
      ),
      newestInvites.map(inviteRow),
    ),
    h('div', { class: 'ledger-col' },
      h('div', { class: 'ledger-head' },
        h('h2', { class: 'ledger-title', text: 'Latest activity' }),
        h('a', { class: 'text-action', href: '#/audit' }, 'Audit'),
      ),
      recent.map(activityRow),
    ),
  );

  container.append(
    head,
    health,
    h('div', { class: 'overview-grid' }, relay, waiting, ledger),
  );
}
