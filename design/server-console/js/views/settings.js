import { h } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { n, bytes } from '../format.js';
import { pageHead, tabBar, srHeading } from './common.js';
import { toast } from '../ui.js';

const TABS = [
  { id: 'access', label: 'Access' },
  { id: 'files', label: 'Files' },
  { id: 'relay', label: 'Relay' },
  { id: 'directory', label: 'Directory' },
  { id: 'health', label: 'Health' },
  { id: 'about', label: 'About' },
];

const AREA = { access: 'Access', files: 'Files', relay: 'Relay', directory: 'Directory' };

function settingsRow(item) {
  return h('div', { class: 'settings-row' },
    h('div', {},
      h('div', { class: 'settings-label', text: item.label }),
      h('div', { class: 'settings-hint', text: item.hint }),
    ),
    h('div', { class: 'settings-panel' },
      h('div', { class: 'settings-value', text: item.value }),
      h('div', { class: 'settings-value-sub', text: item.preview }),
    ),
  );
}

function usagePanel() {
  const f = data.files;
  const ratio = Math.round((f.usedBytes / f.limitBytes) * 100);
  return h('div', { class: 'settings-panel' },
    h('div', { class: 'settings-usage' },
      h('div', { class: 'settings-usage-cell' },
        h('div', { class: 'usage-line' }, h('strong', { text: bytes(f.usedBytes) }), h('span', { text: `of ${bytes(f.limitBytes)}` })),
        h('div', { class: 'usage-track', role: 'img', 'aria-label': `${ratio}% of the hub file quota used` }, h('div', { class: 'usage-fill', style: { width: `${ratio}%` } })),
      ),
      h('div', { class: 'settings-usage-cell' },
        h('div', { class: 'usage-line' }, h('strong', { text: `${f.count} of ${f.limitCount}` }), h('span', { text: 'stored files' })),
        h('div', { class: 'usage-track', 'aria-hidden': 'true' }, h('div', { class: 'usage-fill', style: { width: `${Math.round((f.count / f.limitCount) * 100)}%` } })),
      ),
    ),
  );
}

function healthRows() {
  return h('div', { class: 'health-table' },
    data.health.map((item) => h('div', { class: 'health-row' },
      h('div', { class: 'health-label-cell', text: item.label }),
      h('div', {},
        h('div', { class: 'health-value-cell', text: item.value }),
        h('div', { class: 'health-detail', text: item.detail }),
      ),
    )),
  );
}

function aboutBody() {
  return h('div', { class: 'stack', style: { gap: '20px' } },
    h('section', { class: 'panel about-card' },
      h('div', { class: 'about-mark' },
        h('span', { class: 'brand-mark', style: { width: '32px', height: '32px' } }, icon('bolt', { size: 24 })),
        h('div', {},
          h('div', { class: 'about-name', text: 'Flash Hub' }),
          h('div', { class: 'faint small', text: `${data.hub.org} · ${data.hub.site}` }),
        ),
      ),
      h('p', { class: 'about-text', text: 'The administration console for an on-premises Flash hub. It is served by the hub itself and loads no code, font, image or analytics from outside your network.' }),
      h('div', { class: 'inline' },
        h('span', { class: 'chip chip-neutral', text: `Version ${data.hub.version}` }),
        h('span', { class: 'chip chip-neutral', text: `Built ${data.hub.build}` }),
        h('span', { class: 'chip chip-neutral', text: 'No external requests' }),
      ),
    ),
    h('section', { class: 'panel', style: { padding: '20px' } },
      h('h2', { class: 'section-title', text: 'Keyboard shortcuts', style: { marginBottom: '8px' } }),
      h('div', { class: 'shortcut-table' },
        data.shortcuts.map(([keys, label]) => h('div', { class: 'shortcut-row' },
          h('div', { class: 'shortcut-keys' }, h('kbd', { class: 'kbd', text: keys })),
          h('div', { class: 'shortcut-label', text: label }),
        )),
      ),
    ),
  );
}

export function mountSettings(container, ctx) {
  const requested = ctx.query.get('tab');
  const active = TABS.some((t) => t.id === requested) ? requested : 'access';

  function selectTab(id) {
    ctx.setQuery(id === 'access' ? {} : { tab: id });
  }

  const head = pageHead({
    title: 'Settings',
    lede: `Read-only view of this hub’s configuration. Version ${data.hub.version}.`,
    actions: [
      h('button', {
        class: 'btn btn-secondary hide-phone', type: 'button',
        on: { click: () => exportConfig() },
      }, icon('download', { size: 15 }), 'Download config'),
    ],
  });

  const tabs = tabBar({ items: TABS, active, label: 'Settings areas', onSelect: selectTab });
  tabs.classList.add('tabs-wide');
  const tabSelect = h('select', {
    class: 'select tabs-select', 'aria-label': 'Settings area',
    on: { change: (event) => selectTab(event.target.value) },
  }, TABS.map((item) => h('option', { value: item.id, text: item.label })));
  tabSelect.value = active;
  const body = h('div');

  if (AREA[active]) {
    const area = AREA[active];
    const rows = data.settings.filter((s) => s.area === area);
    if (active === 'files') {
      body.append(h('div', { class: 'settings-row' },
        h('div', {}, h('div', { class: 'settings-label', text: 'Stored through the hub' }), h('div', { class: 'settings-hint', text: 'Sealed chunks waiting to be collected by their recipients.' })),
        usagePanel(),
      ));
    }
    body.append(
      h('p', { class: 'hint settings-readonly', text: 'Read-only. These values come from the server configuration; change them on the host, then restart the hub.' }),
      ...rows.map(settingsRow),
    );
  } else if (active === 'health') {
    body.append(
      h('p', { class: 'security-tab-intro', text: 'What the hub is running and where it is listening. The live relay numbers are on Overview.' }),
      h('div', { class: 'panel', style: { padding: '8px 20px' } }, healthRows()),
      h('div', { class: 'inline', style: { marginTop: '16px' } },
        h('span', { class: 'chip chip-ok', text: 'Loopback only' }),
        h('span', { class: 'faint small', text: 'Reach the console through an SSH tunnel or a reverse proxy on the server.' }),
      ),
    );
  } else {
    body.append(aboutBody());
  }

  container.append(head, srHeading(TABS.find((item) => item.id === active)?.label || 'Settings'), h('div', { class: 'screen-tabs' }, tabs, h('div', { class: 'tabs-select-wrap' }, tabSelect)), body);
}

function exportConfig() {
  const blob = new Blob([JSON.stringify({
    hub: data.hub,
    settings: data.settings,
    health: data.health,
    files: { usedBytes: data.files.usedBytes, limitBytes: data.files.limitBytes, count: data.files.count, limitCount: data.files.limitCount },
  }, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = h('a', { href: url, download: 'flash-hub-config.json' });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  toast('Configuration downloaded', 'ok');
}
