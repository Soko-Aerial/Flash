import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { data, NOW } from '../data.js';
import { deviceStatus, deviceIsOnline, revokeDevice } from '../store.js';
import { n, initials, relative, dayMonth, roleLabel, shortId } from '../format.js';
import { pageHead, tabBar, rovingRows, srHeading } from './common.js';
import { openDrawer, confirmAction, statusChip, fingerprintBlock, copyRow, toast, emptyState } from '../ui.js';

function daysFromNow(days) {
  return new Date(NOW.getTime() + days * 86_400_000);
}

function lastSeenCell(device) {
  if (deviceStatus(device) === 'REVOKED') return h('span', { class: 'muted', text: '—' });
  if (deviceIsOnline(device)) return h('span', { class: 'online-mark' }, h('span', { class: 'dot', 'aria-hidden': 'true' }), 'Online');
  return h('span', { class: 'online-mark is-off' }, h('span', { class: 'dot', 'aria-hidden': 'true' }), `${relative(device.lastSeenMinutes)} ago`);
}

function certCell(device) {
  if (deviceStatus(device) !== 'ACTIVE' || device.certDays === null) return h('span', { class: 'muted', text: '—' });
  if (device.certDays <= 14) {
    return h('span', { class: 'cert-soon', text: `${device.certDays} days`, title: 'Certificate expires soon' });
  }
  return h('span', { class: 'muted', text: dayMonth(daysFromNow(device.certDays)) });
}

function deviceActivity(device) {
  return [
    { title: 'Session opened', detail: `${device.platform} · ${device.ip}`, time: deviceIsOnline(device) ? 'now' : `${relative(device.lastSeenMinutes)} ago` },
    { title: 'Directory snapshot applied', detail: '9 departments · 14 groups', time: '38 min ago' },
    { title: 'Certificate renewed', detail: `valid until ${dayMonth(daysFromNow(device.certDays || 30))}`, time: '3 days ago' },
  ];
}

function openDevice(device, refresh) {
  const status = deviceStatus(device);
  const rows = [
    ['Department', device.department || '—'],
    ['Role', roleLabel(device.role)],
    ['Platform', device.platform],
    ['Device ID', h('span', { class: 'inline nowrap' }, h('span', { class: 'mono', text: shortId(device.id, 8, 4) }))],
    ['Certificate', device.certDays ? `${device.certDays} days left · ${dayMonth(daysFromNow(device.certDays))}` : '—'],
    ['Enrolled', `${device.enrolledDays} days ago`],
    ['Last seen', deviceIsOnline(device) ? 'Online now' : `${relative(device.lastSeenMinutes)} ago`],
    ['Address', h('span', { class: 'mono', text: device.ip })],
  ];

  const footer = [];
  if (status === 'ACTIVE') {
    footer.push(h('button', {
      class: 'btn btn-danger', type: 'button', on: {
        click: () => {
          confirmAction({
            title: `Revoke ${device.name}?`,
            message: `This ends the device's session and removes it from the directory. ${device.name} cannot connect again until the device is invited and enrolled afresh.`,
            confirmLabel: 'Revoke device',
            danger: true,
            note: h('p', { class: 'hint' }, 'The action is signed by this browser and recorded in the audit log.'),
            onConfirm: () => {
              revokeDevice(device.id, device.name);
              drawer.close();
              refresh();
              toast(`${device.name} was revoked`, 'ok');
            },
          });
        },
      },
    }, 'Revoke device'));
  } else if (status === 'REVOKED') {
    footer.push(h('span', { class: 'chip chip-bad', text: 'Revoked' }));
  } else {
    footer.push(h('span', { class: 'chip chip-warn', text: 'Has not finished enrolling' }));
  }
  footer.push(h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => drawer.close() } }, 'Close'));

  const drawer = openDrawer({
    title: device.name,
    body: [
      h('div', { class: 'inline' }, statusChip(status), h('span', { class: 'faint small', text: `${device.department} · ${device.platform}` })),
      h('dl', { class: 'definition-list' }, rows.map(([label, value]) => [h('dt', { text: label }), h('dd', {}, value)])),
      h('div', { class: 'stack tight' },
        h('h3', { text: 'TLS identity' }),
        h('div', { class: 'fingerprint-row' },
          h('div', { class: 'stack tight' },
            h('span', { class: 'faint small', text: 'Visual fingerprint' }),
            fingerprintBlock(device.fingerprint),
          ),
          h('div', { class: 'stack tight', style: { minWidth: '0' } },
            h('span', { class: 'faint small', text: 'SHA-256 fingerprint' }),
            copyRow(device.fingerprint, 'Copy fingerprint'),
          ),
        ),
      ),
      h('div', { class: 'stack tight' },
        h('h3', { text: 'Recent activity' }),
        h('div', { class: 'stack tight' }, deviceActivity(device).map((entry) => h('div', { class: 'inline between', style: { alignItems: 'baseline' } },
          h('span', { class: 'small', text: entry.title }),
          h('span', { class: 'faint small nowrap', text: entry.time }),
        ))),
      ),
    ],
    footer,
  });
  return drawer;
}

function downloadCsv(rows) {
  const header = ['Name', 'Device ID', 'Status', 'Department', 'Role', 'Platform', 'Last seen', 'Certificate days'];
  const lines = rows.map((d) => [
    d.name, d.id, deviceStatus(d), d.department || '', roleLabel(d.role), d.platform,
    deviceIsOnline(d) ? 'Online' : relative(d.lastSeenMinutes), d.certDays ?? '',
  ].map((v) => `"${String(v).replaceAll('"', '""')}"`).join(','));
  const blob = new Blob([[header.join(','), ...lines].join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: 'flash-devices.csv' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('Devices exported as CSV', 'ok');
}

export function mountDevices(container, ctx) {
  const filters = {
    text: ctx.query.get('q') || '',
    status: 'all',
    department: '',
    role: '',
    online: false,
  };

  const tableHost = h('div', { class: 'table-host' });
  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
  const tabCounts = {};

  const search = h('input', {
    class: 'input input-search', type: 'search', value: filters.text,
    placeholder: 'Search name, device or department',
    'aria-label': 'Search devices',
    on: { input: (event) => { filters.text = event.target.value; apply(); } },
  });

  const departmentSelect = h('select', { class: 'select select-sm', 'aria-label': 'Filter by department', on: { change: (event) => { filters.department = event.target.value; apply(); } } },
    h('option', { value: '', text: 'All departments' }),
    data.departments.map((d) => h('option', { value: d.name, text: d.name })),
  );
  const roleSelect = h('select', { class: 'select select-sm', 'aria-label': 'Filter by role', on: { change: (event) => { filters.role = event.target.value; apply(); } } },
    h('option', { value: '', text: 'All roles' }),
    h('option', { value: 'member', text: 'Member' }),
    h('option', { value: 'dept_admin', text: 'Department admin' }),
    h('option', { value: 'org_admin', text: 'Organization admin' }),
  );
  const onlineBtn = h('button', {
    class: 'btn btn-secondary btn-sm', type: 'button', 'aria-pressed': 'false',
    on: {
      click: () => {
        filters.online = !filters.online;
        onlineBtn.setAttribute('aria-pressed', String(filters.online));
        onlineBtn.classList.toggle('is-on', filters.online);
        apply();
      },
    },
  }, h('span', { class: 'dot dot-ok', 'aria-hidden': 'true' }), 'Online only');

  const tabsHost = h('div', { class: 'screen-tabs' });
  function setStatus(id) {
    filters.status = id;
    for (const btn of tabsHost.querySelectorAll('.tab')) btn.setAttribute('aria-pressed', btn.dataset.tab === id ? 'true' : 'false');
    mobileStatus.value = id;
    apply();
  }

  const statusItems = [
    { id: 'all', label: 'All', count: 0 },
    { id: 'active', label: 'Active', count: 0 },
    { id: 'revoked', label: 'Revoked', count: 0 },
    { id: 'not_enrolled', label: 'Not enrolled', count: 0 },
  ];
  const tabs = tabBar({
    items: statusItems,
    active: filters.status,
    label: 'Device status',
    onSelect: setStatus,
  });
  tabs.classList.add('tabs-wide');
  for (const btn of tabs.querySelectorAll('.tab')) {
    tabCounts[btn.dataset.tab] = btn.querySelector('.tab-count');
  }
  const mobileStatus = h('select', {
    class: 'select tabs-select', 'aria-label': 'Device status',
    on: { change: (event) => setStatus(event.target.value) },
  }, statusItems.map((item) => h('option', { value: item.id, text: item.id === 'all' ? 'All devices' : item.label })));
  mobileStatus.value = filters.status;
  mount(tabsHost, tabs, h('div', { class: 'tabs-select-wrap' }, mobileStatus));

  const ledeText = h('span', { text: '128 active devices, 74 online. 4 revoked, 6 not enrolled.' });
  const head = pageHead({
    title: 'Devices',
    lede: [h('span', { class: 'lede-sentence' }, h('span', { class: 'dot dot-ok' }), ' ', ledeText)],
    actions: [
      h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => downloadCsv(filtered()) } }, icon('download', { size: 15 }), 'Export CSV'),
      h('a', { class: 'btn btn-secondary', href: '#/invites' }, icon('plus', { size: 15 }), 'Invite a device'),
    ],
  });

  const toolbar = h('div', { class: 'toolbar', role: 'search' },
    h('div', { class: 'search-wrap' }, icon('search', { size: 16, className: 'search-glyph' }), search),
    departmentSelect,
    roleSelect,
    onlineBtn,
    h('div', { class: 'toolbar-end' }, h('span', { text: 'Sorted by name' })),
  );

  container.append(
    head,
    srHeading('Device list'),
    tabsHost,
    toolbar,
    resultLine,
    tableHost,
  );

  let view = [];

  function filtered() {
    const text = filters.text.trim().toLowerCase();
    return data.devices.filter((device) => {
      const status = deviceStatus(device);
      if (filters.status === 'active' && status !== 'ACTIVE') return false;
      if (filters.status === 'revoked' && status !== 'REVOKED') return false;
      if (filters.status === 'not_enrolled' && status !== 'UNENROLLED') return false;
      if (filters.department && device.department !== filters.department) return false;
      if (filters.role && device.role !== filters.role) return false;
      if (filters.online && !deviceIsOnline(device)) return false;
      if (!text) return true;
      return [device.name, device.id, device.department, device.platform, roleLabel(device.role), status]
        .filter(Boolean).some((value) => String(value).toLowerCase().includes(text));
    }).sort((a, b) => a.name.localeCompare(b.name));
  }

  function apply() {
    view = filtered();
    const all = data.devices.length;
    const counts = {
      all,
      active: data.devices.filter((d) => deviceStatus(d) === 'ACTIVE').length,
      revoked: data.devices.filter((d) => deviceStatus(d) === 'REVOKED').length,
      not_enrolled: data.devices.filter((d) => deviceStatus(d) === 'UNENROLLED').length,
    };
    for (const [id, node] of Object.entries(tabCounts)) node.textContent = String(counts[id]);
    const online = data.devices.filter((d) => deviceStatus(d) === 'ACTIVE' && deviceIsOnline(d)).length;
    ledeText.textContent = `${n(counts.active)} active devices, ${n(online)} online. ${n(counts.revoked)} revoked, ${n(counts.not_enrolled)} not enrolled.`;
    resultLine.textContent = view.length === all ? `${n(all)} devices` : `${n(view.length)} of ${n(all)} devices`;
    mount(tableHost);
    if (!view.length) {
      tableHost.append(h('div', { class: 'table-wrap' }, emptyState({
        title: 'No devices match these filters',
        text: 'Clear the search or choose another department.',
        action: h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => { filters.text = ''; filters.department = ''; filters.role = ''; filters.online = false; filters.status = 'all'; search.value = ''; departmentSelect.value = ''; roleSelect.value = ''; onlineBtn.setAttribute('aria-pressed', 'false'); onlineBtn.classList.remove('is-on'); for (const btn of tabsHost.querySelectorAll('.tab')) btn.setAttribute('aria-pressed', btn.dataset.tab === 'all' ? 'true' : 'false'); apply(); } } }, 'Clear filters'),
      })));
      return;
    }
    tableHost.append(h('div', { class: 'table-wrap' },
      h('div', { class: 'table-scroll' },
        h('table', { class: 'table device-list', 'aria-label': 'Devices' },
          h('thead', {},
            h('tr', {},
              h('th', { scope: 'col', text: 'Device' }),
              h('th', { scope: 'col', text: 'Status' }),
              h('th', { scope: 'col', class: 'hide-md', text: 'Department' }),
              h('th', { scope: 'col', class: 'hide-md', text: 'Role' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Last seen' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Certificate' }),
              h('th', { scope: 'col' }, h('span', { class: 'sr-only', text: 'Open' })),
            ),
          ),
          h('tbody', {}, view.map((device) => h('tr', {
            class: `row${deviceStatus(device) === 'REVOKED' ? ' is-muted' : ''}`,
            tabindex: '0',
            'aria-label': `Open ${device.name}`,
            on: {
              click: () => openDevice(device, apply),
              keydown: (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openDevice(device, apply); } },
            },
          },
            h('td', {}, h('span', { class: 'identity' },
              h('span', { class: 'avatar', text: initials(device.name) }),
              h('span', { class: 'cell-stack' },
                h('span', { class: 'cell-title', text: device.name }),
                h('span', { class: 'cell-sub sub-id', text: shortId(device.id, 8, 4) }),
                h('span', { class: 'cell-sub sub-dept', text: device.department || 'No department' }),
                h('span', { class: 'cell-sub sub-phone', text: `${device.department || 'No department'} · ${deviceIsOnline(device) ? 'Online' : `${relative(device.lastSeenMinutes)} ago`}` }),
              ),
            )),
            h('td', {}, statusChip(deviceStatus(device))),
            h('td', { class: 'hide-md' }, device.department || h('span', { class: 'muted', text: 'No department' })),
            h('td', { class: 'hide-md' }, roleLabel(device.role)),
            h('td', { class: 'hide-sm' }, lastSeenCell(device)),
            h('td', { class: 'hide-sm' }, certCell(device)),
            h('td', { class: 'num' }, h('span', { class: 'muted' }, icon('chevronRight', { size: 15 }))),
          ))),
        ),
      ),
      h('div', { class: 'table-foot' },
        h('span', { text: `Showing 1–${view.length} of ${n(all)}` }),
        h('span', { text: `${n(counts.active)} active · ${n(counts.revoked)} revoked` }),
      ),
    ));
    rovingRows(tableHost);
  }

  apply();
}
