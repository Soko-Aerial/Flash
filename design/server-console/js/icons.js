// One drawn outline set, 24 px grid, 1.6 px strokes, currentColor. The bolt uses the app's own path.

const paths = {
  bolt: 'M13 2 L3 14 L12 14 L11 22 L21 10 L12 10 L13 2 Z',
  overview: 'M3 12h4l2.6-6.2 5 12.4 2.6-6.2H21',
  devices: 'M4 5h16v10H4z M2.5 19h19 M8 15v4 M16 15v4',
  invites: 'M3 6h18v12H3z M3.5 6.6l8.5 6 8.5-6',
  requests: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M19 8v6 M16 11h6',
  org: 'M4 21V5l8-3v19 M12 21h8V9l-8-4 M8 7v.01 M8 11v.01 M8 15v.01 M8 19v.01 M16 11v.01 M16 15v.01 M16 19v.01',
  announcements: 'M3 10.5v3a2 2 0 0 0 2 2h1l2.2 4.5h2.4L9 15.5l12 4.5v-16L3 10.5z M9 8v7',
  audit: 'M5 3h14v18H5z M9 7h6 M9 11h6 M9 15h4',
  security: 'M12 3l7 2.8v6.1c0 4.5-2.9 7.1-7 9.1-4.1-2-7-4.6-7-9.1V5.8L12 3z M9.5 12l1.8 1.8 3.4-3.6',
  settings: 'M4 6h9 M17 6h3 M4 12h3 M11 12h9 M4 18h7 M15 18h5 M13 4v4 M7 10v4 M11 16v4',
  search: 'M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15z M16 16l5 5',
  plus: 'M12 5v14 M5 12h14',
  close: 'M6 6l12 12 M18 6L6 18',
  chevronRight: 'M9 5l7 7-7 7',
  chevronDown: 'M5 9l7 7 7-7',
  arrowRight: 'M4 12h16 M14 6l6 6-6 6',
  refresh: 'M20 6v5h-5 M4 18v-5h5 M19 11a7 7 0 0 0-12-4L4 11 M5 13a7 7 0 0 0 12 4l3-3',
  download: 'M12 3v12 M7 10l5 5 5-5 M4 19h16',
  copy: 'M9 9h11v12H9z M5 15H3V3h12v3',
  check: 'M4 12.5l5 5 11-11',
  alert: 'M12 3l10 18H2L12 3z M12 9v5 M12 17v.01',
  info: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 11v5 M12 8v.01',
  kebab: 'M6 12v.01 M12 12v.01 M18 12v.01',
  external: 'M14 4h6v6 M20 4l-9 9 M19 14v6H4V5h6',
  printer: 'M7 8V3h10v5 M7 17h10v4H7z M4 8h16v9H4z M17 12h.01',
  key: 'M14 10a5 5 0 1 1-4.8-5 5 5 0 0 1 4.8 5z M13 13l8 8 M17 17l2-2 M19 19l2 2',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M12 7v5l3 2',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5L19 19 M19 5l-1.5 1.5 M6.5 17.5L5 19',
  moon: 'M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5z',
  monitor: 'M3 4h18v12H3z M8 20h8 M12 16v4',
  help: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z M9.5 9a2.5 2.5 0 1 1 3.2 2.4c-.7.3-.7.8-.7 1.6 M12 17v.01',
  logout: 'M10 4H5v16h5 M14 8l4 4-4 4 M18 12H9',
  filter: 'M4 5h16l-6 7v6l-4 2v-8L4 5z',
  link: 'M10 14a4 4 0 0 0 6 0l3-3a4 4 0 1 0-6-6l-1.5 1.5 M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 1 0 6 6l1.5-1.5',
  lock: 'M5 10h14v11H5z M8 10V7a4 4 0 0 1 8 0v3',
  user: 'M18 21v-2a5 5 0 0 0-5-5h-2a5 5 0 0 0-5 5v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8',
  mail: 'M3 6h18v12H3z M3.5 6.5l8.5 6 8.5-6',
  wifi: 'M2.5 9a14 14 0 0 1 19 0 M5.5 12.5a9.5 9.5 0 0 1 13 0 M8.5 16a5 5 0 0 1 7 0 M12 19v.01',
  relay: 'M5 12h3l2-5 4 10 2-5h3',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  more: 'M4 6h16 M4 12h16 M4 18h16',
  pin: 'M9 4h6l-1 6 3 2v2H7v-2l3-2-1-6z M12 14v7',
  print: 'M7 8V3h10v5 M7 17h10v4H7z M4 8h16v9H4z M17 12h.01',
  eye: 'M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
};

export function icon(name, { size = 18, className = '' } = {}) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  el.setAttribute('viewBox', '0 0 24 24');
  el.setAttribute('width', String(size));
  el.setAttribute('height', String(size));
  el.setAttribute('aria-hidden', 'true');
  el.setAttribute('focusable', 'false');
  el.setAttribute('class', `icon icon-stroke ${className}`.trim());
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', paths[name] || paths.info);
  path.setAttribute('d', paths[name] || paths.info);
  el.append(path);
  return el;
}

export function boltPath() {
  return paths.bolt;
}
