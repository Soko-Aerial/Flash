import { h } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { state, deviceStatus } from '../store.js';
import { n, initials } from '../format.js';
import { pageHead, tabBar, rovingRows, srHeading } from './common.js';
import { openDrawer } from '../ui.js';

function departmentDrawer(department) {
  const members = data.devices.filter((d) => d.department === department.name && deviceStatus(d) === 'ACTIVE');
  const drawer = openDrawer({
    title: department.name,
    body: [
      h('div', { class: 'inline' },
        h('span', { class: 'chip chip-neutral', text: `${department.members} devices` }),
        h('span', { class: 'chip chip-neutral', text: `${department.groups} groups` }),
      ),
      h('dl', { class: 'definition-list' },
        h('dt', { text: 'Head' }),
        h('dd', { text: department.head }),
        h('dt', { text: 'Directory scope' }),
        h('dd', { text: 'Own department, plus named cross-department groups' }),
        h('dt', { text: 'Sync' }),
        h('dd', { text: 'Every 15 minutes · last applied 6 minutes ago' }),
      ),
      h('div', { class: 'stack tight' },
        h('h3', { text: 'Devices' }),
        h('div', { class: 'stack tight' }, members.slice(0, 6).map((device) => h('div', { class: 'inline between' },
          h('span', { class: 'inline' }, h('span', { class: 'avatar', text: initials(device.name) }), h('span', { class: 'small', text: device.name })),
          h('span', { class: 'faint small', text: device.platform }),
        ))),
        members.length > 6 ? h('span', { class: 'hint', text: `${members.length - 6} more devices are not shown here.` }) : null,
      ),
    ],
    footer: [h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => drawer.close() } }, 'Close')],
  });
  return drawer;
}

export function mountOrganization(container, ctx) {
  const active = ctx.query.get('tab') === 'groups' ? 'groups' : 'departments';
  const waiting = state.requests.length;
  const departments = data.departments.map((department) => ({
    ...department,
    members: data.devices.filter((device) => device.department === department.name && deviceStatus(device) === 'ACTIVE').length,
  }));

  function selectTab(id) {
    ctx.setQuery(id === 'groups' ? { tab: 'groups' } : {});
  }

  const head = pageHead({
    title: 'Organization',
    lede: `${n(departments.length)} departments, ${n(data.groups.length)} groups. ${waiting} ${waiting === 1 ? 'request' : 'requests'} waiting.`,
    actions: [
      h('a', { class: 'btn btn-secondary', href: '#/requests' }, icon('requests', { size: 15 }), `Review ${waiting} requests`),
    ],
  });

  const tabs = tabBar({
    items: [
      { id: 'departments', label: 'Departments', count: data.departments.length },
      { id: 'groups', label: 'Groups', count: data.groups.length },
    ],
    active,
    label: 'Organization views',
    onSelect: selectTab,
  });

  let body;
  if (active === 'departments') {
    body = h('div', { class: 'table-wrap' },
      h('div', { class: 'table-scroll' },
        h('table', { class: 'table org-table', 'aria-label': 'Departments' },
          h('thead', {}, h('tr', {},
            h('th', { scope: 'col', text: 'Department' }),
            h('th', { scope: 'col', class: 'hide-sm', text: 'Head' }),
            h('th', { scope: 'col', class: 'num', text: 'Devices' }),
            h('th', { scope: 'col', class: 'num', text: 'Groups' }),
          )),
          h('tbody', {}, departments.map((department) => h('tr', {
            class: 'row', tabindex: '0', 'aria-label': `Open ${department.name}`,
            on: { click: () => departmentDrawer(department), keydown: (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); departmentDrawer(department); } } },
          },
            h('td', {}, h('span', { class: 'cell-stack' },
              h('span', { class: 'cell-title', text: department.name }),
              h('span', { class: 'cell-sub head-sub', text: `Head: ${department.head}` }),
            )),
            h('td', { class: 'hide-sm' }, h('span', { class: 'dept-head' }, h('span', { class: 'avatar', text: initials(department.head) }), department.head)),
            h('td', { class: 'num', text: n(department.members) }),
            h('td', { class: 'num', text: n(department.groups) }),
          )))),
      ),
      h('div', { class: 'table-foot' },
        h('span', { text: `${n(departments.length)} departments · ${n(departments.reduce((s, d) => s + d.members, 0))} active devices` }),
        h('span', { text: 'Departments are scoped to this hub' }),
      ),
    );
  } else {
    body = h('div', { class: 'table-wrap' },
      h('div', { class: 'table-scroll' },
        h('table', { class: 'table org-table', 'aria-label': 'Groups' },
          h('thead', {}, h('tr', {},
            h('th', { scope: 'col', text: 'Group' }),
            h('th', { scope: 'col', class: 'num', text: 'Members' }),
            h('th', { scope: 'col', text: 'Access' }),
            h('th', { scope: 'col', class: 'hide-sm', text: 'Owner' }),
            h('th', { scope: 'col', class: 'hide-sm', text: 'Last activity' }),
          )),
          h('tbody', {}, data.groups.map((group) => h('tr', {
            class: 'row', tabindex: '0', 'aria-label': `Open ${group.name}`,
            on: {
              click: () => openGroup(group),
              keydown: (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); openGroup(group); } },
            },
          },
            h('td', {}, h('span', { class: 'cell-stack' },
              h('span', { class: 'cell-title', text: group.name }),
              h('span', { class: 'cell-sub', text: `Owner: ${group.owner}` }),
            )),
            h('td', { class: 'num', text: n(group.members) }),
            h('td', {}, h('span', { class: `group-access${group.access === 'Open' ? ' is-open' : ''}` }, h('span', { class: 'dot' }), group.access)),
            h('td', { class: 'hide-sm' }, group.owner),
            h('td', { class: 'hide-sm muted' }, group.last),
          )))),
      ),
      h('div', { class: 'table-foot' },
        h('span', { text: `${n(data.groups.length)} groups · members overlap` }),
        h('span', { text: 'Group access is signed by the owner' }),
      ),
    );
  }

  function openGroup(group) {
    openDrawer({
      title: group.name,
      body: [
        h('div', { class: 'inline' },
          h('span', { class: `chip ${group.access === 'Open' ? 'chip-ok' : 'chip-neutral'}`, text: group.access }),
          h('span', { class: 'chip chip-neutral', text: `${group.members} members` }),
        ),
        h('dl', { class: 'definition-list' },
          h('dt', { text: 'Owner' }),
          h('dd', { text: group.owner }),
          h('dt', { text: 'Last activity' }),
          h('dd', { text: group.last }),
          h('dt', { text: 'Serving' }),
          h('dd', { text: 'Members may share files through the group when nearby' }),
        ),
        h('p', { class: 'hint', text: 'The roster is signed by the group owner; a change reaches every member the next time they are connected.' }),
      ],
      footer: [h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => { document.querySelector('dialog.drawer[open]')?.close(); } } }, 'Close')],
    });
  }

  container.append(head, srHeading(active === 'departments' ? 'Departments' : 'Groups'), h('div', { class: 'screen-tabs' }, tabs), body);
  rovingRows(body);
}
