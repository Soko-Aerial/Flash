// One believable world for the prototype. Every total on screen is computed from these rows or from the one
// aggregate it belongs to; the assertions at the bottom fail loudly if a figure stops reconciling.

export const NOW = new Date('2026-11-05T09:45:00');

export const hub = {
  org: 'Meridian Works',
  name: 'Meridian Hub',
  site: 'Geneva · Production',
  address: 'hub.meridian.example',
  version: '2.1.0',
  build: '2026-10-20',
  healthySince: '30 October',
  uptime: { days: 6, hours: 4, minutes: 10 },
};

export const admin = {
  name: 'Rita Okonkwo',
  short: 'R. Okonkwo',
  initials: 'RO',
  role: 'Organization administrator',
  roleKey: 'org_admin',
  sessionMinutes: 24,
};

const firstNames = [
  'Amina', 'Lukas', 'Sofia', 'Mateo', 'Priya', 'Noah', 'Ines', 'Jonas', 'Mei', 'Elena',
  'Tomas', 'Yara', 'Hugo', 'Nadia', 'Rita', 'Felix', 'Clara', 'Omar', 'Leila', 'Ben',
  'Anika', 'Diego', 'Marta', 'Samir', 'Freya', 'Kenji', 'Nora', 'Idris', 'Greta', 'Rui',
  'Zara', 'Milo', 'Hana', 'Arthur', 'Ivy', 'Pavel', 'Aisha', 'Theo', 'Lucia', 'Emeka',
];
const lastNames = [
  'Haddad', 'Weber', 'Rossi', 'Silva', 'Raman', 'Fischer', 'Duarte', 'Berg', 'Tan', 'Marchetti',
  'Novak', 'Osei', 'Lindqvist', 'Farouk', 'Okonkwo', 'Brandt', 'Moreau', 'Hansen', 'Keller', 'Wright',
  'Ivanova', 'Costa', 'Petrov', 'Nakamura', 'Larsen', 'Mbeki', 'Sato', 'Ahmed', 'Schulz', 'Almeida',
  'Kowalski', 'Kim', 'Dubois', 'Nilsen', 'Abara', 'Yilmaz', 'Popescu', 'Garcia', 'Mensah', 'Cho',
];
const platforms = [
  'Pixel 8', 'iPhone 15', 'ThinkPad T14s', 'Galaxy S24', 'Surface Laptop 6', 'Pixel 7a',
  'MacBook Air M3', 'iPhone 14', 'ThinkPad X1 Carbon', 'Galaxy S23', 'Pixel 8 Pro', 'Surface Pro 9',
];

export const departments = [
  { name: 'Engineering', head: 'Lena Fischer', members: 22, groups: 3 },
  { name: 'Field Operations', head: 'Tobias Kern', members: 17, groups: 4 },
  { name: 'Operations', head: 'Yara Osei', members: 16, groups: 2 },
  { name: 'Security', head: 'Amara Diallo', members: 14, groups: 2 },
  { name: 'Finance', head: 'Hugo Lindqvist', members: 13, groups: 1 },
  { name: 'People', head: 'Priya Raman', members: 12, groups: 1 },
  { name: 'Legal', head: 'Ines Duarte', members: 11, groups: 1 },
  { name: 'Facilities', head: 'Jonas Berg', members: 12, groups: 1 },
  { name: 'Leadership', head: 'Mei Tan', members: 11, groups: 1 },
];

const deptPool = departments.flatMap((d) => Array(d.members).fill(d.name)).slice(0, 128);
const onlineTarget = 74;
const onlineIdx = new Set(Array.from({ length: onlineTarget }, (_, k) => Math.floor((k * 128) / onlineTarget)));

function hex8(seed) {
  let x = seed >>> 0;
  x = (x ^ (x << 13)) >>> 0;
  x = (x ^ (x >> 17)) >>> 0;
  x = (x ^ (x << 5)) >>> 0;
  return x.toString(16).padStart(8, '0');
}

const devices = [];
for (let i = 0; i < 138; i += 1) {
  const active = i < 128;
  const unenrolled = i >= 132;
  const status = active ? 'ACTIVE' : unenrolled ? 'UNENROLLED' : 'REVOKED';
  const online = active && onlineIdx.has(i);
  const certDays = active ? 3 + ((i * 7) % 38) : null;
  const lastSeenMinutes = online ? (i % 3) * 15 : 6 + ((i * 31) % 600);
  devices.push({
    id: hex8(0x5eed0000 + i * 2654435761),
    serial: `4A2C${(0x4000 + i).toString(16).toUpperCase()}`,
    name: `${firstNames[i % firstNames.length]} ${lastNames[(i * 7 + Math.floor(i / 40) * 13 + 3) % lastNames.length]}`,
    platform: platforms[i % platforms.length],
    department: deptPool[Math.min(i, 127)] || null,
    role: i < 3 ? 'org_admin' : i < 12 ? 'dept_admin' : 'member',
    status,
    online,
    lastSeenMinutes,
    certDays,
    enrolledDays: 3 + ((i * 11) % 210),
    fingerprint: hex8(0xabc00000 + i * 97) + hex8(0xdef00000 + i * 41) + hex8(0x12300000 + i * 13) + hex8(0x45600000 + i * 7),
    ip: `10.20.${4 + (i % 9)}.${18 + (i % 31)}`,
  });
}
devices[0].name = 'Amina Haddad';
devices[0].platform = 'Pixel 8';

const deviceCounts = {
  active: devices.filter((d) => d.status === 'ACTIVE').length,
  revoked: devices.filter((d) => d.status === 'REVOKED').length,
  unenrolled: devices.filter((d) => d.status === 'UNENROLLED').length,
  online: devices.filter((d) => d.online).length,
};

const inviteStates = [
  ...Array(12).fill('PENDING'),
  ...Array(34).fill('USED'),
  ...Array(3).fill('EXPIRED'),
  ...Array(1).fill('REVOKED'),
];
const inviteWhen = {
  PENDING: (i) => 1 + ((i * 3) % 6),
  USED: (i) => 2 + ((i * 5) % 40),
  EXPIRED: (i) => 12 + i,
  REVOKED: () => 9,
};
const invites = inviteStates.map((state, i) => ({
  id: `inv-${(i + 1).toString().padStart(3, '0')}`,
  name: `${firstNames[(i + 9) % firstNames.length]} ${lastNames[(i * 13 + 8) % lastNames.length]}`,
  department: departments[(i * 3 + 1) % departments.length].name,
  role: i % 11 === 0 ? 'dept_admin' : 'member',
  state,
  createdDaysAgo: inviteWhen[state](i),
  expiresDays: state === 'PENDING' ? 7 - ((i * 2) % 5) : state === 'EXPIRED' ? -(1 + (i % 3)) : 0,
  code: `MRD-${(i * 7 + 13).toString(36).toUpperCase().padStart(4, '0')}-${(i * 11 + 5).toString(36).toUpperCase().padStart(4, '0')}`,
  usedBy: state === 'USED' ? `${firstNames[(i + 9) % firstNames.length]} ${lastNames[(i * 13 + 8) % lastNames.length]}` : null,
}));
const invitesByState = {
  pending: invites.filter((i) => i.state === 'PENDING').length,
  used: invites.filter((i) => i.state === 'USED').length,
  expired: invites.filter((i) => i.state === 'EXPIRED').length,
  revoked: invites.filter((i) => i.state === 'REVOKED').length,
};

const requests = [
  {
    id: 'req-1',
    name: 'Elena Marchetti',
    platform: 'Pixel 8',
    department: 'Field Operations',
    asked: '2 days ago',
    askedMinutes: 2880,
    message: 'Moving to the Geneva office on Monday. I need the field channel before I travel.',
    unpaired: true,
  },
  {
    id: 'req-2',
    name: 'Tomas Novak',
    platform: 'Galaxy S23',
    department: 'Engineering',
    asked: '1 day ago',
    askedMinutes: 1420,
    message: 'Joining the platform team; my old device stays with the Prague office.',
    unpaired: false,
  },
  {
    id: 'req-3',
    name: 'Yara Osei',
    platform: 'iPhone 14',
    department: 'Operations',
    asked: '6 hours ago',
    askedMinutes: 360,
    message: 'Replacement for the phone I lost on site last week.',
    unpaired: true,
  },
  {
    id: 'req-4',
    name: 'Hugo Lindqvist',
    platform: 'Surface Pro 9',
    department: 'Finance',
    asked: '3 hours ago',
    askedMinutes: 180,
    message: 'Contract review starts Thursday; I need the finance group.',
    unpaired: false,
  },
  {
    id: 'req-5',
    name: 'Nadia Farouk',
    platform: 'Pixel 7',
    department: 'People',
    asked: '1 hour ago',
    askedMinutes: 65,
    message: 'Onboarding the November cohort.',
    unpaired: false,
  },
];

const groups = [
  { name: 'Control Room', members: 24, owner: 'Yara Osei', access: 'Open', last: '2 min ago' },
  { name: 'Field Ops · North', members: 17, owner: 'Tobias Kern', access: 'Open', last: '14 min ago' },
  { name: 'Engineering Standup', members: 22, owner: 'Lena Fischer', access: 'Open', last: '1 hour ago' },
  { name: 'Emergency Response', members: 9, owner: 'Amara Diallo', access: 'Closed', last: 'Today' },
  { name: 'Geneva Office Move', members: 31, owner: 'Priya Raman', access: 'Open', last: '2 days ago' },
  { name: 'Finance Review', members: 13, owner: 'Hugo Lindqvist', access: 'Closed', last: 'Yesterday' },
  { name: 'Field Ops · South', members: 11, owner: 'Mateo Silva', access: 'Open', last: '3 hours ago' },
  { name: 'Facilities Coordination', members: 12, owner: 'Jonas Berg', access: 'Open', last: 'Yesterday' },
  { name: 'Legal Counsel', members: 11, owner: 'Ines Duarte', access: 'Closed', last: '2 days ago' },
  { name: 'Leadership Briefing', members: 11, owner: 'Mei Tan', access: 'Closed', last: 'Today' },
  { name: 'Onboarding · November', members: 8, owner: 'Nadia Farouk', access: 'Open', last: '1 hour ago' },
  { name: 'Security Watch', members: 14, owner: 'Amara Diallo', access: 'Closed', last: '28 min ago' },
  { name: 'Procurement', members: 6, owner: 'R. Okonkwo', access: 'Open', last: 'Yesterday' },
  { name: 'Hub Admins', members: 3, owner: 'R. Okonkwo', access: 'Closed', last: 'Now' },
];

// 60 one-minute samples, 08:45–09:44. The morning shift change at 09:12 is the one event marked on the chart.
const perMinute = Array.from({ length: 60 }, (_, i) => {
  const base = 18 + ((i * 7) % 13) + (i > 20 ? 8 : 0) + (i > 40 ? 4 : 0);
  return i === 27 ? base + 27 : base;
});
const deliveredLastHour = perMinute.reduce((a, b) => a + b, 0);
const relay = {
  perMinute,
  deliveredLastHour,
  queuedNow: 0,
  refusedLastHour: 0,
  refusedToday: 3,
  medianMs: 41,
  p95Ms: 118,
  annotation: { index: 27, label: 'Shift change · 09:12' },
  startedHour: 8,
  startedMinute: 45,
};

const auditEntries = [
  { action: 'device.revoke', label: 'Device revoked', actor: 'R. Okonkwo', detail: 'K. Mensah · lost in transit', minutesAgo: 35 },
  { action: 'join.approve', label: 'Join request approved', actor: 'L. Fischer', detail: 'Sofia Rossi · Operations', minutesAgo: 50 },
  { action: 'admin.session.start', label: 'Administrator signed in', actor: 'R. Okonkwo', detail: '10.20.4.18 · Firefox on Windows', minutesAgo: 63 },
  { action: 'delegation.verify', label: 'Delegation verified', actor: 'hub', detail: 'serial 4A2C-91F0 · signature intact', minutesAgo: 74 },
  { action: 'invite.create', label: 'Invite created', actor: 'R. Okonkwo', detail: 'Nadia Farouk · People', minutesAgo: 93 },
  { action: 'directory.sync', label: 'Directory synced', actor: 'hub', detail: '9 departments · 128 members · 14 groups', minutesAgo: 112 },
  { action: 'device.enroll', label: 'Device enrolled', actor: 'L. Fischer', detail: 'Mara Vogel · Galaxy S23 · Engineering', minutesAgo: 130 },
  { action: 'announcement.pin', label: 'Announcement pinned', actor: 'P. Raman', detail: 'Geneva office move: badge check from Monday', minutesAgo: 150 },
  { action: 'stepup.key.enroll', label: 'Browser key enrolled', actor: 'R. Okonkwo', detail: 'this browser · ECDSA P-256', minutesAgo: 175 },
  { action: 'device.revoke', label: 'Device revoked', actor: 'R. Okonkwo', detail: 'Old ThinkPad · Stockholm desk', minutesAgo: 200 },
  { action: 'settings.update', label: 'File limit changed', actor: 'R. Okonkwo', detail: 'maxFileBytes 25 MB → 50 MB', minutesAgo: 240 },
  { action: 'join.decline', label: 'Join request declined', actor: 'A. Diallo', detail: 'M. Petrov · no department match', minutesAgo: 288 },
].map((e, i) => ({ ...e, index: 12840 - i, at: new Date(NOW.getTime() - e.minutesAgo * 60_000) }));

const audit = {
  entries: auditEntries,
  total: 12840,
  verifiedAt: new Date('2026-10-28T14:02:00'),
  verifiedEntries: 12797,
  chainIntact: true,
};

const relayEntries = auditEntries.filter((e) => /device|join|admin|delegation|stepup/.test(e.action));

const announcements = [
  {
    id: 'ann-1',
    title: 'Geneva office move: badge check from Monday',
    body: 'From Monday 9 November, everyone entering the Rue du Rhône office uses the new badges. Keep the old badge for the underground car park until Friday. Facilities will run a drop-in desk on Thursday from 08:00 to 11:00.',
    author: 'Priya Raman',
    scope: 'All departments',
    pinned: true,
    minutesAgo: 150,
    audience: 128,
  },
  {
    id: 'ann-2',
    title: 'Field radios: channel 2 is dispatch only',
    body: 'Channel 2 is now reserved for dispatch across all sites. Use channel 3 for site talk and channel 4 for the north yard. The change is already programmed into the gateway; no device action is needed.',
    author: 'Yara Osei',
    scope: 'Field Operations',
    pinned: false,
    minutesAgo: 1290,
    audience: 17,
  },
  {
    id: 'ann-3',
    title: 'Directory audit: confirm your department by 14 November',
    body: 'Security is checking that every active device belongs to the right department and group. Department heads can confirm their list from the Organization page; anyone who has moved team should update their department before the audit closes.',
    author: 'Amara Diallo',
    scope: 'All departments',
    pinned: false,
    minutesAgo: 4320,
    audience: 128,
  },
  {
    id: 'ann-4',
    title: 'Files through the hub can now be up to 50 MB',
    body: 'The administrator limit for a single file through the hub is now 50 MB. Files above that size still travel directly between paired devices when they are nearby. Existing stored files are not affected.',
    author: 'R. Okonkwo',
    scope: 'All departments',
    pinned: false,
    minutesAgo: 8640,
    audience: 128,
  },
];

const administrators = [
  { name: 'Rita Okonkwo', role: 'Organization administrator', roleKey: 'org_admin', lastActive: 'Now', twoFactor: 'Authenticator', thisBrowser: true, initials: 'RO' },
  { name: 'Lena Fischer', role: 'Organization administrator', roleKey: 'org_admin', lastActive: '12 min ago', twoFactor: 'Authenticator', thisBrowser: false, initials: 'LF' },
  { name: 'Tobias Kern', role: 'Department administrator · Field Operations', roleKey: 'dept_admin', lastActive: 'Yesterday', twoFactor: 'Authenticator', thisBrowser: false, initials: 'TK' },
];

const sessions = [
  { id: 's-1', browser: 'Firefox on Windows', place: '10.20.4.18 · Geneva', started: '63 min ago', current: true },
  { id: 's-2', browser: 'Safari on iPhone', place: '10.20.8.42 · Geneva', started: 'Yesterday, 17:06', current: false },
  { id: 's-3', browser: 'Chrome on macOS', place: '10.20.6.11 · Zurich', started: '2 days ago', current: false },
];

const delegation = {
  status: 'ACTIVE',
  serial: '4A2C-91F0',
  issuedAt: '2026-10-17',
  expiresAt: '2026-11-14',
  daysLeft: 9,
  rootFingerprint: '9f2a 7c41 80bd 2e63 41a0 5c19 d8e7 3b42 6c55 0a91 47e8 b2d3 19fa 6c80 5b17 8e24',
  command: 'keytool issue-delegation \\\n  --hub hub.meridian.example \\\n  --days 90 --out meridian-2026-11.delegation',
};

const revocations = {
  listVersion: 3,
  revokedSerials: 4,
  revokedDevices: 4,
  unpublished: false,
  rows: [
    { serial: '4A2C-6F19', device: 'K. Mensah · Pixel 6a', at: '12 Oct 09:10', reason: 'Lost in transit' },
    { serial: '4A2C-3D88', device: 'Old ThinkPad · Stockholm desk', at: '2 Sep 16:44', reason: 'Returned to stock' },
    { serial: '4A2C-11C2', device: 'M. Petrov · Surface Go', at: '18 Aug 11:02', reason: 'Left the company' },
    { serial: '4A2C-08A4', device: 'Contractor tablet · Site 4', at: '4 Jul 14:20', reason: 'Contract ended' },
  ],
};

const files = {
  usedBytes: 2.1 * 1024 ** 3,
  limitBytes: 5 * 1024 ** 3,
  count: 37,
  limitCount: 50,
  retentionDays: 7,
  mailboxBytes: 214 * 1024 ** 2,
};

const settings = [
  { area: 'Access', label: 'Session lifetime', value: '30 minutes', hint: 'A console session ends after this idle time.', preview: 'An idle session that started at 09:15 ends at 09:45.' },
  { area: 'Access', label: 'Idle warning', value: '25 minutes', hint: 'The "Still there?" prompt appears five minutes before the end.', preview: 'The prompt appears at 09:40 for that session.' },
  { area: 'Access', label: 'Invite expiry', value: '7 days', hint: 'An unused invite stops being accepted after seven days.', preview: 'A code created today stops working on 12 November.' },
  { area: 'Access', label: 'Device certificate', value: '30 days', hint: 'Devices renew automatically 7 days before expiry.', preview: 'Renewal starts on day 23; the device keeps its identity.' },
  { area: 'Files', label: 'Maximum file through the hub', value: '50 MB', hint: 'Per-file limit. Direct transfers between paired devices are not affected.', preview: 'A 48 MB file is accepted; a 52 MB file is refused by name.' },
  { area: 'Files', label: 'Device file quota', value: '500 MB', hint: 'Unacknowledged stored files per device.', preview: 'Two 250 MB files fill one device’s quota.' },
  { area: 'Files', label: 'Hub file quota', value: '5.0 GB', hint: 'Total stored file data on this hub.', preview: '2.1 GB is in use now, 42% of the quota.' },
  { area: 'Files', label: 'Retention', value: '7 days', hint: 'Stored files are removed after this window.', preview: 'A file uploaded today is removed on 12 November.' },
  { area: 'Relay', label: 'Byte budget per device', value: '50 MB / day', hint: 'Relayed traffic counted per device, reset at 00:00 UTC.', preview: 'About 6,000 short messages fit in one device’s day.' },
  { area: 'Relay', label: 'Mailbox quarantine', value: '24 hours', hint: 'Messages that cannot be sealed are held aside, not delivered.', preview: 'Held messages are discarded after one day, never delivered.' },
  { area: 'Directory', label: 'Sync interval', value: '15 minutes', hint: 'Devices reconcile the directory in the background.', preview: 'A department change reaches a connected device within 15 minutes.' },
  { area: 'Directory', label: 'Offline grace', value: '30 days', hint: 'How long a device trusts its directory snapshot offline.', preview: 'A device offline for 31 days locks its directory until it syncs.' },
];

const health = [
  { label: 'Version', value: '2.1.0', detail: 'built 20 October 2026', copy: false },
  { label: 'Uptime', value: '6 d 4 h 10 m', detail: 'since 30 October, 05:35', copy: false },
  { label: 'Relay listener', value: '127.0.0.1:45822', detail: 'TLS, loopback only', copy: true },
  { label: 'Admin listener', value: '127.0.0.1:8443', detail: 'TLS, loopback only', copy: true },
  { label: 'Database', value: '48.2 MB', detail: 'SQLite · last backup 02:00 today', copy: false },
  { label: 'Audit chain', value: 'Intact', detail: '12,840 entries · verified 28 Oct', copy: false },
];

const shortcuts = [
  ['g then o', 'Open Overview'],
  ['g then d', 'Open Devices'],
  ['g then i', 'Open Invites'],
  ['g then r', 'Open Join requests'],
  ['g then a', 'Open Audit'],
  ['g then s', 'Open Security'],
  ['/', 'Focus the page search'],
  ['?', 'Show this list'],
  ['Esc', 'Close a dialog or drawer'],
  ['j / k', 'Next / previous table row'],
];

export const data = {
  hub,
  admin,
  devices,
  deviceCounts,
  departments,
  invites,
  invitesByState,
  requests,
  groups,
  relay,
  audit,
  relayEntries,
  announcements,
  administrators,
  sessions,
  delegation,
  revocations,
  files,
  settings,
  health,
  shortcuts,
  totals: {
    departments: departments.length,
    groups: groups.length,
    administrators: administrators.length,
    requests: requests.length,
  },
};

// Reconciliations the design is judged on.
console.assert(deviceCounts.active === 128, 'active devices must be 128');
console.assert(deviceCounts.revoked === 4, 'revoked devices must be 4');
console.assert(deviceCounts.unenrolled === 6, 'unenrolled devices must be 6');
console.assert(deviceCounts.active + deviceCounts.revoked + deviceCounts.unenrolled === devices.length, 'device statuses must add up');
console.assert(deviceCounts.online === 74, 'online devices must be 74');
console.assert(new Set(devices.map((d) => d.name)).size === devices.length, 'device names must be unique');
console.assert(departments.reduce((sum, d) => sum + d.members, 0) === 128, 'department members must add to the active devices');
console.assert(invitesByState.pending === 12 && invitesByState.used === 34 && invitesByState.expired === 3 && invitesByState.revoked === 1, 'invite states must be 12/34/3/1');
console.assert(invites.length === 50, 'invites must total 50');
console.assert(requests.length === 5, 'join requests must be 5');
console.assert(groups.length === 14, 'groups must be 14');
console.assert(perMinute.length === 60 && relay.deliveredLastHour === perMinute.reduce((a, b) => a + b, 0), 'the 60 bars must sum to the delivered figure');
console.assert(audit.entries.every((e, i, all) => i === 0 || all[i - 1].index === e.index + 1), 'audit indices must descend by one');
console.assert(delegation.daysLeft === 9, 'delegation must show 9 days left');
