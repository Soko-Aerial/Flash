import { h, mount, copyText } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { state, createInvite, revokeInvite } from '../store.js';
import { n, initials, roleLabel } from '../format.js';
import { pageHead, tabBar, srHeading } from './common.js';
import { openDialog, confirmAction, toast, inviteChip, emptyState, copyButton } from '../ui.js';

const STATE_LABEL = { PENDING: 'Waiting', USED: 'Used', EXPIRED: 'Expired', REVOKED: 'Revoked' };

function stateCounts() {
  const counts = { PENDING: 0, USED: 0, EXPIRED: 0, REVOKED: 0 };
  for (const invite of state.invites) counts[invite.state] += 1;
  return counts;
}

function inviteRow(invite, onRevoked) {
  const when = invite.state === 'PENDING'
    ? invite.expiresDays > 0 ? `Expires in ${invite.expiresDays} days` : 'Expires today'
    : invite.state === 'USED' ? `Used by ${invite.usedBy || invite.name}` : invite.state === 'EXPIRED' ? 'Expired automatically' : 'Revoked by an administrator';
  return h('div', { class: 'invite-row' },
    h('span', { class: 'avatar', text: initials(invite.name) }),
    h('div', { class: 'invite-main' },
      h('div', { class: 'invite-name', text: invite.name }),
      h('div', { class: 'invite-sub', text: `${invite.department} · ${roleLabel(invite.role)}` }),
      h('div', { class: 'invite-sub invite-sub-when', text: when }),
    ),
    h('div', { class: 'invite-meta' },
      inviteChip(invite.state),
      h('span', { class: 'invite-when', text: when }),
    ),
    invite.state === 'PENDING'
      ? h('button', {
        class: 'btn btn-ghost btn-sm invite-revoke', type: 'button',
        on: {
          click: () => confirmAction({
            title: `Revoke the invite for ${invite.name}?`,
            message: 'The code stops working immediately. The person will need a new invite before their device can enrol.',
            confirmLabel: 'Revoke invite',
            danger: true,
            onConfirm: () => {
              revokeInvite(invite.id);
              onRevoked();
              toast('Invite revoked', 'ok');
            },
          }),
        },
      }, 'Revoke')
      : h('span', { class: 'invite-end' }),
  );
}

function createdDialog(invite) {
  const link = `${data.hub.address}/invite/${invite.code}`;
  const copyBtn = h('button', {
    class: 'btn btn-primary', type: 'button',
    on: { click: async () => { const ok = await copyText(invite.code); toast(ok ? 'Invite code copied' : 'Could not copy', ok ? 'ok' : 'bad'); } },
  }, icon('copy', { size: 15 }), 'Copy code');
  const dialog = openDialog({
    title: 'Invite created',
    body: [
      h('p', { class: 'dialog-message', text: `Send this code to ${invite.name}. It is shown once; the hub keeps only its hash.` }),
      h('div', { class: 'invite-secret' },
        h('span', { class: 'faint small', text: 'Invite code' }),
        h('span', { class: 'invite-code', text: invite.code }),
      ),
      h('div', { class: 'stack tight' },
        h('span', { class: 'faint small', text: 'Invite link' }),
        h('div', { class: 'copy-row' }, h('span', { class: 'copy-value', text: link }), copyButton(link, 'Copy invite link')),
      ),
      h('p', { class: 'hint', text: `Expires in ${invite.expiresDays} days. A code cannot be shown again after this dialog closes.` }),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Done'),
      copyBtn,
    ],
  });
  return dialog;
}

function narrowCreateDialog(onCreated) {
  const name = h('input', { class: 'input', placeholder: 'Name of the person joining', 'aria-label': 'Name' });
  const department = h('select', { class: 'select', 'aria-label': 'Department' }, data.departments.map((d) => h('option', { value: d.name, text: d.name })));
  const role = h('select', { class: 'select', 'aria-label': 'Role' },
    h('option', { value: 'member', text: 'Member' }),
    h('option', { value: 'dept_admin', text: 'Department admin' }),
  );
  const expiry = h('select', { class: 'select', 'aria-label': 'Expiry' },
    h('option', { value: '7', text: '7 days' }),
    h('option', { value: '14', text: '14 days' }),
    h('option', { value: '30', text: '30 days' }),
  );
  const dialog = openDialog({
    title: 'New invite',
    body: [
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Person' }), name),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Department' }), department),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Role' }), role),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Expires' }), expiry),
      h('p', { class: 'hint', text: 'The code is shown once. The hub stores only a hash of it.' }),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Cancel'),
      h('button', {
        class: 'btn btn-primary', type: 'button',
        on: {
          click: () => {
            if (!name.value.trim()) { name.focus(); toast('Add the name of the person joining', 'bad'); return; }
            const invite = createInvite({ name: name.value.trim(), department: department.value, role: role.value, days: Number(expiry.value) });
            dialog.close();
            onCreated(invite);
          },
        },
      }, 'Create invite'),
    ],
  });
  return dialog;
}

function bulkDialog(onCreated) {
  const lines = h('textarea', { class: 'textarea', placeholder: 'Elena Marchetti\nTomas Novak\nYara Osei', 'aria-label': 'Names, one per line' });
  const department = h('select', { class: 'select', 'aria-label': 'Department' },
    data.departments.map((d) => h('option', { value: d.name, text: d.name })),
  );
  const role = h('select', { class: 'select', 'aria-label': 'Role' },
    h('option', { value: 'member', text: 'Member' }),
    h('option', { value: 'dept_admin', text: 'Department admin' }),
  );
  const dialog = openDialog({
    title: 'Add a list of invites',
    body: [
      h('p', { class: 'dialog-message', text: 'Paste one person per line. Each person gets their own code; the codes are shown once after they are created.' }),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'People' }), lines),
      h('div', { class: 'form-grid' },
        h('label', { class: 'field' }, h('span', { class: 'label', text: 'Department' }), department),
        h('label', { class: 'field' }, h('span', { class: 'label', text: 'Role' }), role),
      ),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Cancel'),
      h('button', {
        class: 'btn btn-primary', type: 'button',
        on: {
          click: () => {
            const names = lines.value.split('\n').map((s) => s.trim()).filter(Boolean).slice(0, 20);
            if (!names.length) { toast('Add at least one name', 'bad'); return; }
            for (const name of names) createInvite({ name, department: department.value, role: role.value, days: 7 });
            dialog.close();
            onCreated(names.length);
          },
        },
      }, 'Create invites'),
    ],
  });
  return dialog;
}

export function mountInvites(container, ctx) {
  let activeState = 'PENDING';
  let searchText = '';
  let listHost;
  let tabsHost;
  let countNode;
  let lede;

  function counts() { return stateCounts(); }

  function filtered() {
    const text = searchText.trim().toLowerCase();
    return state.invites.filter((invite) => invite.state === activeState && (!text
      || [invite.name, invite.department, invite.code].some((v) => String(v).toLowerCase().includes(text))));
  }

  function renderList() {
    const rows = filtered();
    if (countNode) countNode.textContent = rows.length ? `${n(rows.length)} ${STATE_LABEL[activeState].toLowerCase()}` : '';
    mount(listHost);
    if (!rows.length) {
      listHost.append(emptyState({
        title: activeState === 'PENDING' ? 'No invites waiting' : `No ${STATE_LABEL[activeState].toLowerCase()} invites`,
        text: activeState === 'PENDING' ? 'Create one on the right and share the code with the person joining.' : 'Nothing has reached this state yet.',
      }));
      return;
    }
    listHost.append(h('div', { class: 'invite-list' }, rows.map((invite) => inviteRow(invite, () => rebuild()))));
  }

  function selectTab(id) {
    activeState = id;
    for (const btn of tabsHost.querySelectorAll('.tab')) btn.setAttribute('aria-pressed', btn.dataset.tab === id ? 'true' : 'false');
    renderList();
  }

  function buildTabs() {
    const c = counts();
    tabsHost = h('div', { class: 'screen-tabs' });
    const tabs = tabBar({
      items: [
        { id: 'PENDING', label: 'Waiting', count: c.PENDING },
        { id: 'USED', label: 'Used', count: c.USED },
        { id: 'EXPIRED', label: 'Expired', count: c.EXPIRED },
        { id: 'REVOKED', label: 'Revoked', count: c.REVOKED },
      ],
      active: activeState,
      label: 'Invite state',
      onSelect: selectTab,
    });
    mount(tabsHost, tabs);
  }

  function buildForm() {
    const nameInput = h('input', { class: 'input', placeholder: 'Name of the person joining', 'aria-label': 'Name' });
    const department = h('select', { class: 'select', 'aria-label': 'Department' }, data.departments.map((d) => h('option', { value: d.name, text: d.name })));
    const role = h('select', { class: 'select', 'aria-label': 'Role' },
      h('option', { value: 'member', text: 'Member' }),
      h('option', { value: 'dept_admin', text: 'Department admin' }),
    );
    const expiry = h('select', { class: 'select', 'aria-label': 'Expiry' },
      h('option', { value: '7', text: '7 days' }),
      h('option', { value: '14', text: '14 days' }),
      h('option', { value: '30', text: '30 days' }),
    );
    const form = h('form', {
      class: 'compose-form',
      on: {
        submit: (event) => {
          event.preventDefault();
          const name = nameInput.value.trim();
          if (!name) { nameInput.focus(); toast('Add the name of the person joining', 'bad'); return; }
          const invite = createInvite({ name, department: department.value, role: role.value, days: Number(expiry.value) });
          rebuild();
          createdDialog(invite);
        },
      },
    },
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Person' }), nameInput, h('span', { class: 'hint', text: 'The name is only a note, so you can tell codes apart.' })),
      h('div', { class: 'form-grid' },
        h('label', { class: 'field' }, h('span', { class: 'label', text: 'Department' }), department),
        h('label', { class: 'field' }, h('span', { class: 'label', text: 'Role' }), role),
      ),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Expires' }), expiry),
      h('button', { class: 'btn btn-primary btn-block', type: 'submit' }, icon('plus', { size: 15 }), 'Create invite'),
      h('p', { class: 'compose-note', text: 'The code is shown once and sent over a channel you trust. The hub stores only a hash of it.' }),
    );

    return h('section', { class: 'panel compose-panel' },
      h('div', { class: 'panel-head', style: { paddingBottom: '14px' } },
        h('div', {},
          h('h2', { class: 'panel-title', text: 'New invite' }),
          h('p', { class: 'panel-sub', text: 'One person, one code' }),
        ),
        h('button', {
          class: 'text-action', type: 'button',
          on: { click: () => bulkDialog((count) => { rebuild(); toast(`${count} invites created`, 'ok'); }) },
        }, 'Bulk add'),
      ),
      h('div', { class: 'panel-body tight' }, form),
    );
  }

  function legendPanel() {
    const states = [
      ['PENDING', 'Code issued; the device has not enrolled yet.'],
      ['USED', 'The person enrolled; the code cannot be used again.'],
      ['EXPIRED', 'The date passed before it was used; create a new one.'],
      ['REVOKED', 'An administrator cancelled it before it was used.'],
    ];
    return h('section', { class: 'panel invite-legend' },
      h('div', { class: 'panel-head', style: { paddingBottom: '4px' } },
        h('h2', { class: 'panel-title', text: 'What the states mean' }),
      ),
      h('div', { class: 'invite-legend-list' },
        states.map(([state, text]) => h('div', { class: 'invite-legend-row' },
          inviteChip(state),
          h('span', { class: 'invite-legend-text', text }),
        )),
      ),
    );
  }

  function rebuild() {
    container.replaceChildren();
    build();
  }

  function build() {
    const c = counts();
    const total = state.invites.length;
    lede = `${n(c.PENDING)} waiting, ${n(c.USED)} used. ${n(c.EXPIRED)} expired, ${n(c.REVOKED)} revoked, ${n(total)} total.`;

    const head = pageHead({
      title: 'Invites',
      lede,
      actions: [
        h('button', { class: 'btn btn-secondary hide-phone', type: 'button', on: { click: () => downloadCsv() } }, icon('download', { size: 15 }), 'Export CSV'),
        h('button', { class: 'btn btn-secondary', type: 'button', on: { click: () => bulkDialog((count) => { rebuild(); toast(`${count} invites created`, 'ok'); }) } }, icon('mail', { size: 15 }), 'Paste a list'),
        h('button', {
          class: 'btn btn-primary invite-create-narrow', type: 'button',
          on: { click: () => narrowCreateDialog((invite) => { rebuild(); createdDialog(invite); }) },
        }, icon('plus', { size: 15 }), 'New invite'),
      ],
    });

    buildTabs();
    const search = h('input', {
      class: 'input input-search', type: 'search', placeholder: 'Search invites', 'aria-label': 'Search invites',
      on: { input: (event) => { searchText = event.target.value; renderList(); } },
    });
    countNode = h('span', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
    listHost = h('div', { class: 'invite-list-host' });

    const listPanel = h('section', { class: 'panel invite-panel' },
      h('div', { class: 'invite-toolbar' },
        h('div', { class: 'search-wrap' }, icon('search', { size: 16, className: 'search-glyph' }), search),
        countNode,
      ),
      listHost,
    );

    container.append(
      head,
      srHeading(`Invitations, ${STATE_LABEL[activeState].toLowerCase()}`),
      tabsHost,
      h('div', { class: 'invite-grid' },
        listPanel,
        h('div', { class: 'stack invite-aside' }, buildForm(), legendPanel()),
      ),
    );
    renderList();
  }

  function downloadCsv() {
    const header = ['Name', 'Department', 'Role', 'State', 'Code', 'Expires in days'];
    const lines = state.invites.map((i) => [i.name, i.department, roleLabel(i.role), STATE_LABEL[i.state], i.code, i.expiresDays]
      .map((v) => `"${String(v).replaceAll('"', '""')}"`).join(','));
    const blob = new Blob([[header.join(','), ...lines].join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: 'flash-invites.csv' });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('Invites exported as CSV', 'ok');
  }

  build();
}
