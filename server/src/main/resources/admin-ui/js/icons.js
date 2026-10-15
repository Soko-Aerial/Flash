// The console's glyphs: 24 x 24 grid, 1.75 px stroke, currentColor. Drawn for this product (no icon library). Each entry is the list of
// path `d` strings of one glyph; `fill` entries are solid shapes.

import { svg } from './dom.js';

const ICONS = {
  bolt: { fill: ['M13.5 2 5 13.2h5.6L9.7 22 19 10.4h-5.8z'] },
  overview: ['M4 13h6V4H4z', 'M14 20h6V4h-6z', 'M4 20h6v-3H4z'],
  devices: ['M7 3h10a1.5 1.5 0 0 1 1.5 1.5v15A1.5 1.5 0 0 1 17 21H7a1.5 1.5 0 0 1-1.5-1.5v-15A1.5 1.5 0 0 1 7 3z', 'M10.5 18h3'],
  invites: ['M4 6h16v12H4z', 'm4 7 8 6 8-6'],
  org: ['M12 3v5', 'M5 21v-5h14v5', 'M12 8H5v8', 'M12 8h7v8', 'M10 3h4v5h-4z'],
  audit: ['M6 3h9l4 4v14H6z', 'M15 3v4h4', 'M9 12h7', 'M9 16h7'],
  announcements: ['M3 11v2h4l5 5V4L7 9H3z', 'M16 8a5 5 0 0 1 0 8', 'M19 5a8 8 0 0 1 0 14'],
  pin: ['M16 12V4h1V2H7v2h1v8l-2 2v2h5v6l1 1 1-1v-6h5v-2z'],
  security: ['M12 3 5 6v5.5c0 4.5 3 8 7 9.5 4-1.5 7-5 7-9.5V6z', 'm9 12 2.2 2.2L15.5 10'],
  settings: ['M4 7h10', 'M18 7h2', 'M4 17h2', 'M10 17h10', 'M14 5v4h4V5z', 'M6 15v4h4v-4z'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'm20 20-4-4'],
  plus: ['M12 5v14', 'M5 12h14'],
  close: ['M6 6l12 12', 'M18 6 6 18'],
  check: ['m5 12.5 4.5 4.5L19 7.5'],
  copy: ['M9 9h10v11H9z', 'M5 15V4h10'],
  download: ['M12 4v11', 'm7.5 11 4.5 4.5 4.5-4.5', 'M5 20h14'],
  upload: ['M12 16V5', 'm7.5 9 4.5-4.5L16.5 9', 'M5 20h14'],
  print: ['M7 9V3h10v6', 'M7 17H4v-7h16v7h-3', 'M7 14h10v7H7z'],
  refresh: ['M20 11a8 8 0 0 0-14.3-4.3L4 8.5', 'M4 4v4.5h4.5', 'M4 13a8 8 0 0 0 14.3 4.3L20 15.5', 'M20 20v-4.5h-4.5'],
  chevronRight: ['m9 5 7 7-7 7'],
  chevronDown: ['m5 9 7 7 7-7'],
  chevronUp: ['m5 15 7-7 7 7'],
  sortAsc: ['M12 5v14', 'm6 11 6-6 6 6'],
  sortDesc: ['M12 5v14', 'm6 13 6 6 6-6'],
  menu: ['M4 7h16', 'M4 12h16', 'M4 17h16'],
  sun: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M12 3v2', 'M12 19v2', 'M3 12h2', 'M19 12h2', 'm5.6 5.6 1.4 1.4', 'm17 17 1.4 1.4', 'm18.4 5.6-1.4 1.4', 'M7 17l-1.4 1.4'],
  moon: ['M20 14.5A8.5 8.5 0 0 1 9.5 4a8.5 8.5 0 1 0 10.5 10.5z'],
  monitor: ['M3 5h18v11H3z', 'M8 20h8', 'M12 16v4'],
  warning: ['M12 3 2.5 20h19z', 'M12 10v4.5', 'M12 17.5v.5'],
  info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v6', 'M12 7.5v.5'],
  error: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'm9 9 6 6', 'm15 9-6 6'],
  success: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'm8 12.5 3 3 5-6'],
  lock: ['M6 11h12v9H6z', 'M8.5 11V8a3.5 3.5 0 0 1 7 0v3'],
  key: ['M8 15a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'm11 12 9 0', 'M17 12v3', 'M20 12v2'],
  logout: ['M10 4H5v16h5', 'M10 12h10', 'm16 8 4 4-4 4'],
  user: ['M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M4.5 20a7.5 7.5 0 0 1 15 0'],
  more: { fill: ['M12 7a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z', 'M12 13.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z', 'M12 20a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z'] },
  trash: ['M4 7h16', 'M9 7V4h6v3', 'M6 7l1 13h10l1-13'],
  edit: ['M4 20h4L19 9l-4-4L4 16z', 'm13.5 6.5 4 4'],
  link: ['M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1', 'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  filter: ['M4 5h16l-6 8v6l-4-2v-4z'],
  eye: ['M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z'],
  keyboard: ['M3 6h18v12H3z', 'M7 10h.5', 'M11 10h.5', 'M15 10h.5', 'M7 14h10'],
  inbox: ['M4 13 6.5 5h11L20 13', 'M4 13v6h16v-6h-5l-1 2h-4l-1-2z'],
  group: ['M9 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6z', 'M3 19a6 6 0 0 1 12 0', 'M16 5.5a3 3 0 0 1 0 5.5', 'M18 14.5a6 6 0 0 1 3 4.5'],
};

export const ICON_NAMES = Object.keys(ICONS);

/** An inline SVG icon element. Decorative by default (aria-hidden); pass `label` to make it an image with a name. */
export function icon(name, { size = 20, label = null, className = '' } = {}) {
  const def = ICONS[name];
  if (!def) throw new Error('Unknown icon: ' + name);
  const paths = Array.isArray(def) ? def : null;
  const solids = Array.isArray(def) ? [] : def.fill;
  const props = { viewBox: '0 0 24 24', width: size, height: size, class: ('icon ' + className).trim(), focusable: 'false' };
  if (label) {
    props.role = 'img';
    props['aria-label'] = label;
  } else {
    props['aria-hidden'] = 'true';
  }
  const root = svg('svg', props);
  for (const d of paths || []) root.appendChild(svg('path', { d, class: 'icon-stroke' }));
  for (const d of solids) root.appendChild(svg('path', { d, class: 'icon-solid' }));
  return root;
}
