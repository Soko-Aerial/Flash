// Picking devices by name: a search box with live results (server-side search, 8 at a time), and a cached id -> name map for showing
// names where the API only returns ids (group members and administrators).

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { shortId } from '../format.js';
import { textInput, badge } from '../ui/widgets.js';
import { icon } from '../icons.js';

let cache = null;
let cacheAt = 0;

/** Every device (id -> {name, status, department}), refreshed at most once a minute unless [force]. */
export async function loadDeviceNames(force = false) {
  if (!force && cache && Date.now() - cacheAt < 60_000) return cache;
  const map = new Map();
  for (let offset = 0; offset < 20000; offset += 500) {
    const page = await api.get('/devices', { query: { limit: 500, offset } });
    for (const d of page) map.set(d.deviceId, { name: d.name, status: d.status, department: d.department, role: d.role });
    if (page.length < 500) break;
  }
  cache = map;
  cacheAt = Date.now();
  return map;
}

export function deviceLabel(map, id) {
  const d = map && map.get(id);
  return d && d.name ? d.name : shortId(id);
}

/**
 * A search-and-pick control.
 * @param {{onPick: (device: any) => void, exclude?: () => Set<string>, label: string, status?: string}} o
 */
export function devicePicker({ onPick, exclude = () => new Set(), label, status = 'ACTIVE' }) {
  const input = textInput({ type: 'search', placeholder: t('picker.placeholder'), className: 'input-search' });
  input.setAttribute('aria-label', label);
  const list = h('ul', { class: 'picker-results', 'aria-label': t('picker.results') });
  const note = h('p', { class: 'muted small', role: 'status', 'aria-live': 'polite' });
  let timer = 0;
  let token = 0;
  async function run() {
    const mine = ++token;
    const q = input.value.trim();
    if (q.length < 1) {
      mount(list);
      note.textContent = t('picker.hint');
      return;
    }
    try {
      const rows = await api.get('/devices', { query: { search: q, status, limit: 8 } });
      if (mine !== token) return;
      const skip = exclude();
      const fresh = rows.filter((d) => !skip.has(d.deviceId));
      note.textContent = fresh.length ? '' : t('picker.none');
      mount(
        list,
        fresh.map((d) =>
          h(
            'li',
            {},
            h(
              'button',
              { class: 'picker-item', type: 'button', on: { click: () => { onPick(d); input.value = ''; mount(list); note.textContent = ''; input.focus(); } } },
              icon('plus', { size: 16 }),
              h('span', { class: 'picker-name', text: d.name || shortId(d.deviceId) }),
              h('span', { class: 'picker-sub muted', text: [d.department, t('role.' + d.role)].filter(Boolean).join(' · ') }),
              d.online ? badge(t('devices.online'), 'ok') : null,
            ),
          ),
        ),
      );
    } catch (e) {
      if (mine === token) note.textContent = e.message || t('error.generic');
    }
  }
  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(run, 220);
  });
  note.textContent = t('picker.hint');
  return { el: h('div', { class: 'picker' }, input, note, list), input };
}
