// Organization: departments, groups (visibility, join mode, administrators, members) and the join-request inbox. A pending request is the
// one thing an administrator must not miss, so the tab carries a count and so does the navigation.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { formatNumber, shortId, slugify } from '../format.js';
import { pageHeader, button, badge, banner, emptyState, textInput, selectInput, field, withBusy, timeEl, tabs, skeleton, errorState, mono, dl } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDialog, openDrawer, confirmDialog } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { setActiveTable } from '../shortcuts.js';
import { devicePicker, loadDeviceNames, deviceLabel } from './devicepicker.js';
import { statusBadge } from './devices.js';
import { session } from '../state.js';
import { icon } from '../icons.js';
import { iconButton } from '../ui/widgets.js';

/**
 * Only an organisation administrator may move a device between departments (`PUT /devices/{id}` refuses a
 * department head with "Only an organisation administrator can move a device."), so the Members section is
 * shown to them alone. Same test as `announcements.js`.
 */
function isOrgAdmin() {
  const role = (session.role || '').toLowerCase();
  return (role === 'admin' || role === 'org_admin') && (!session.departments || session.departments.length === 0);
}

function joinModeBadge(mode) {
  return badge(t('org.join.' + mode), mode === 'OPEN_IN_SCOPE' ? 'ok' : mode === 'ADMIN_ONLY' ? 'neutral' : 'accent');
}

/** Chips of devices with a remove button each. */
function deviceChips(ids, names, onRemove, label) {
  if (!ids.length) return h('p', { class: 'muted small', text: t('org.noDevices') });
  return h(
    'ul',
    { class: 'chip-list', 'aria-label': label },
    ids.map((id) => h('li', { class: 'chip' }, h('span', { text: deviceLabel(names, id) }), iconButton({ icon: 'close', label: t('org.remove', { name: deviceLabel(names, id) }), on: { click: () => onRemove(id) } }))),
  );
}

export function mountOrganization(container, ctx) {
  let stopped = false;
  let departments = [];
  let groups = [];
  let requests = [];
  let names = new Map();
  let ready = false;
  let current = ['departments', 'groups', 'requests'].includes(ctx.query.tab) ? ctx.query.tab : 'departments';

  const tabBar = tabs({
    label: t('org.tabs'),
    selected: current,
    items: [
      { id: 'departments', label: t('org.tab.departments') },
      { id: 'groups', label: t('org.tab.groups') },
      { id: 'requests', label: t('org.tab.requests') },
    ],
    onSelect: (id) => {
      current = id;
      if (!ready) return;
      ctx.replaceQuery({ tab: id === 'departments' ? '' : id });
      renderTab();
    },
  });
  const panel = tabBar.panel;
  mount(container, pageHeader({ title: t('org.title'), subtitle: t('org.sub') }), tabBar.el, panel);

  function renderTab() {
    setActiveTable(null);
    ({ departments: renderDepartments, groups: renderGroups, requests: renderRequests })[current]();
  }

  async function loadAll() {
    mount(panel, skeleton('table', 5));
    try {
      [departments, groups, requests, names] = await Promise.all([api.get('/departments'), api.get('/groups'), api.get('/join-requests'), loadDeviceNames().catch(() => new Map())]);
      if (stopped) return;
      tabBar.setBadge('requests', requests.length);
      ctx.setBadge('organization', requests.length);
      ready = true;
      renderTab();
    } catch (e) {
      if (!stopped) mount(panel, errorState(e, loadAll));
    }
  }

  // ------------------------------------------------------------------ departments
  function renderDepartments() {
    const tableHost = h('div', { class: 'table-host' });
    mount(panel, h('div', { class: 'section-actions' }, button({ label: t('org.dept.create'), icon: 'plus', variant: 'primary', on: { click: () => editDepartment(null) } })), tableHost);
    if (!departments.length) {
      mount(tableHost, emptyState({ title: t('org.dept.empty.title'), text: t('org.dept.empty.text'), glyph: 'org', action: button({ label: t('org.dept.create'), icon: 'plus', variant: 'primary', on: { click: () => editDepartment(null) } }) }));
      return;
    }
    const table = createTable({
      ariaLabel: t('org.dept.table'),
      rowId: (d) => d.id,
      rowLabel: (d) => d.name,
      onOpen: (d) => openDepartment(d),
      columns: [
        { key: 'name', label: t('org.col.name'), primary: true, render: (d) => h('span', { class: 'cell-stack' }, h('span', { class: 'cell-title', text: d.name }), h('span', { class: 'cell-sub mono', text: d.id })) },
        { key: 'hidden', label: t('org.dept.hidden'), className: 'hide-sm', render: (d) => (d.hidden ? badge(t('org.vis.HIDDEN'), 'accent') : h('span', { class: 'muted', text: '—' })) },
        { key: 'members', label: t('org.col.members'), className: 'num', render: (d) => formatNumber(d.memberCount) },
        { key: 'groups', label: t('org.col.groups'), className: 'num', render: (d) => formatNumber(d.groupCount) },
        { key: 'admins', label: t('org.col.admins'), className: 'hide-sm', render: (d) => (d.adminDeviceIds.length ? d.adminDeviceIds.map((id) => deviceLabel(names, id)).join(', ') : h('span', { class: 'muted', text: t('org.noAdmins') })) },
      ],
    });
    table.update({ rows: departments });
    tableHost.appendChild(table.el);
    setActiveTable(table);
  }

  /**
   * What a row click shows: the department as it is now (summary, administrators, the devices that carry it, its groups).
   * Changing anything is one click further (Edit), so reading a department never risks changing it.
   */
  function openDepartment(dept) {
    const body = h('div', { class: 'stack' });
    const edit = button({ label: t('org.dept.details.edit'), icon: 'edit', variant: 'primary' });
    const drawer = openDrawer({
      title: dept.name,
      body,
      actions: [button({ label: t('common.close'), variant: 'ghost', on: { click: () => drawer.close() } }), edit],
    });
    edit.addEventListener('click', () => {
      drawer.close();
      editDepartment(dept);
    });

    const deptGroups = groups.filter((g) => g.departmentId === dept.id);
    const summary = dl([
      [t('org.dept.details.id'), mono(dept.id)],
      [t('org.dept.details.visibility'), dept.hidden ? badge(t('org.vis.HIDDEN'), 'accent') : badge(t('org.vis.LISTED'), 'neutral')],
      [t('org.col.members'), formatNumber(dept.memberCount)],
      [t('org.col.groups'), formatNumber(dept.groupCount)],
    ]);
    const admins = dept.adminDeviceIds.length
      ? h('ul', { class: 'compact-list' }, dept.adminDeviceIds.map((id) => h('li', { text: deviceLabel(names, id) })))
      : h('p', { class: 'muted small', text: t('org.noAdmins') });
    const groupList = deptGroups.length
      ? h(
          'ul',
          { class: 'compact-list' },
          deptGroups.map((g) =>
            h('li', {}, h('span', { text: g.name }), ' ', joinModeBadge(g.joinMode), ' ', h('span', { class: 'muted small', text: t('org.dept.details.groupMembers', { n: formatNumber(g.memberDeviceIds.length) }) })),
          ),
        )
      : h('p', { class: 'muted small', text: t('org.dept.details.noGroups') });
    const membersHost = h('div', { 'aria-live': 'polite' }, skeleton('table', 3));
    mount(body, summary, h('h3', { class: 'subhead', text: t('org.dept.admins') }), admins, h('h3', { class: 'subhead', text: t('org.dept.members') }), membersHost, h('h3', { class: 'subhead', text: t('org.dept.details.groups') }), groupList);

    api
      .get('/devices', { query: { department: dept.id, limit: 500 } })
      .then((rows) => {
        if (stopped) return;
        if (!rows.length) return mount(membersHost, h('p', { class: 'muted small', text: t('org.dept.members.none') }));
        const sorted = [...rows].sort((a, b) => (a.name || a.deviceId).localeCompare(b.name || b.deviceId));
        const revoked = sorted.filter((d) => d.status === 'REVOKED').length;
        return mount(
          membersHost,
          h(
            'ul',
            { class: 'compact-list' },
            sorted.map((d) =>
              h('li', {}, h('span', { text: d.name || shortId(d.deviceId) }), ' ', statusBadge(d.status), ' ', h('span', { class: 'muted small', text: t('role.' + d.role) }), ' ', d.online ? h('span', { class: 'online' }, h('span', { class: 'dot', 'aria-hidden': 'true' }), t('devices.online')) : d.lastSeenAt ? timeEl(d.lastSeenAt) : null),
            ),
          ),
          revoked ? h('p', { class: 'muted small', text: t('org.dept.details.revokedNote', { n: formatNumber(revoked) }) }) : null,
        );
      })
      .catch((e) => {
        if (!stopped) mount(membersHost, banner({ tone: 'bad', text: e.message || t('error.generic') }));
      });
  }

  function editDepartment(dept) {
    const isNew = !dept;
    const name = textInput({ value: dept ? dept.name : '', maxlength: 128 });
    const nf = field({ label: t('org.dept.name'), control: name, required: true });
    const hiddenBox = h('input', { type: 'checkbox', class: 'check' });
    if (dept && dept.hidden) hiddenBox.checked = true;
    const hiddenField = field({ label: t('org.dept.hidden'), control: hiddenBox, hint: t('org.dept.hidden.help') });
    hiddenField.el.appendChild(h('p', { class: 'muted small', text: t('org.dept.hidden.note') }));
    let admins = dept ? [...dept.adminDeviceIds] : [];
    const adminHost = h('div');
    const draw = () => mount(adminHost, deviceChips(admins, names, (id) => { admins = admins.filter((x) => x !== id); draw(); }, t('org.dept.admins')));
    draw();
    const picker = devicePicker({ label: t('org.dept.addAdmin'), exclude: () => new Set(admins), onPick: (d) => { names.set(d.deviceId, { name: d.name }); admins.push(d.deviceId); draw(); } });
    const message = h('div', { 'aria-live': 'polite' });
    // Members: an organisation administrator moves devices in and out of the department here as well as on
    // Devices. A colleague an invite left without a department is added exactly this way, so the control also
    // lives where an administrator looks for it. A department head does not get it — moving a device is
    // organisation-administrator work and the API refuses it.
    const canMoveMembers = !isNew && isOrgAdmin();
    let memberRows = [];
    const memberHost = h('div');
    const memberMessage = h('div', { 'aria-live': 'polite' });
    const memberName = (d) => d.name || shortId(d.deviceId);
    const drawMembers = () =>
      mount(
        memberHost,
        memberRows.length
          ? h(
              'ul',
              { class: 'chip-list', 'aria-label': t('org.dept.members') },
              memberRows.map((d) =>
                h(
                  'li',
                  { class: 'chip' },
                  h('span', { text: memberName(d) }),
                  iconButton({ icon: 'close', label: t('org.dept.removeMember', { name: memberName(d) }), on: { click: () => removeMember(d) } }),
                ),
              ),
            )
          : h('p', { class: 'muted small', text: t('org.dept.members.none') }),
      );
    const bumpMemberCount = (delta) => {
      const row = departments.find((x) => x.id === dept.id);
      if (row) row.memberCount = Math.max(0, (row.memberCount || 0) + delta);
      if (!stopped && current === 'departments') renderDepartments();
    };
    async function removeMember(d) {
      try {
        await api.put('/devices/' + encodeURIComponent(d.deviceId), { department: null, moveToDepartment: true });
        memberRows = memberRows.filter((x) => x.deviceId !== d.deviceId);
        drawMembers();
        bumpMemberCount(-1);
        toast(t('org.dept.memberRemoved', { name: memberName(d) }), { kind: 'success' });
      } catch (e) {
        mount(memberMessage, banner({ tone: 'bad', text: e.message || t('error.generic') }));
      }
    }
    const memberPicker = canMoveMembers
      ? devicePicker({
          label: t('org.dept.addMember'),
          exclude: () => new Set(memberRows.map((d) => d.deviceId)),
          onPick: async (d) => {
            try {
              await api.put('/devices/' + encodeURIComponent(d.deviceId), { department: dept.id, moveToDepartment: true });
              memberRows = [...memberRows, d].sort((a, b) => memberName(a).localeCompare(memberName(b)));
              drawMembers();
              bumpMemberCount(1);
              toast(t('org.dept.memberAdded', { name: memberName(d) }), { kind: 'success' });
            } catch (e) {
              mount(memberMessage, banner({ tone: 'bad', text: e.message || t('error.generic') }));
            }
          },
        })
      : null;
    const memberSection = canMoveMembers
      ? [h('h3', { class: 'subhead', text: t('org.dept.members') }), h('p', { class: 'muted small', text: t('org.dept.members.hint') }), memberHost, memberPicker.el, memberMessage]
      : [];
    if (canMoveMembers) {
      drawMembers();
      api
        .get('/devices', { query: { department: dept.id, limit: 500 } })
        .then((rows) => {
          if (stopped) return;
          memberRows = rows;
          drawMembers();
        })
        .catch((e) => mount(memberMessage, banner({ tone: 'bad', text: e.message || t('error.generic') })));
    }
    const save = button({ label: isNew ? t('org.dept.create.submit') : t('common.save'), variant: 'primary' });
    const del = !isNew ? button({ label: t('org.dept.delete'), icon: 'trash', variant: 'danger', on: { click: () => deleteDepartment(dept, dlg) } }) : null;
    const dlg = openDialog({
      title: isNew ? t('org.dept.create') : t('org.dept.edit', { name: dept.name }),
      body: [nf.el, hiddenField.el, h('h3', { class: 'subhead', text: t('org.dept.admins') }), h('p', { class: 'muted small', text: t('org.dept.admins.hint') }), adminHost, picker.el, ...memberSection, message],
      actions: [del, button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), save],
    });
    save.addEventListener('click', () =>
      withBusy(save, async () => {
        nf.clearError();
        if (!name.value.trim()) {
          nf.setError(t('org.name.required'));
          return name.focus();
        }
        const wantHidden = hiddenBox.checked;
        const hiddenChanged = dept ? wantHidden !== !!dept.hidden : wantHidden;
        const proceed = !hiddenChanged || (await confirmDialog({ title: t('org.dept.hidden.confirm.title'), message: t('org.dept.hidden.confirm.text'), detail: t('org.dept.hidden.help'), confirmLabel: t('org.dept.hidden.confirm.ok'), danger: false }));
        if (!proceed) return undefined;
        try {
          if (isNew) {
            await createWithUniqueId((id) => api.post('/departments', { id, name: name.value.trim(), adminDeviceIds: admins, hidden: wantHidden }), name.value);
          } else {
            await api.put('/departments/' + encodeURIComponent(dept.id), { name: name.value.trim(), adminDeviceIds: admins, hidden: wantHidden });
          }
          dlg.close();
          toast(t(isNew ? 'org.dept.created' : 'org.dept.saved'), { kind: 'success' });
          await loadAll();
        } catch (e) {
          mount(message, banner({ tone: 'bad', text: e.message || t('error.generic'), actions: e.correlationId ? h('span', { class: 'ref', text: t('error.reference', { id: e.correlationId }) }) : null }));
        }
        return undefined;
      }),
    );
  }

  /** Creates with an id made from the name; on a clash it tries again with a short suffix. */
  async function createWithUniqueId(send, displayName) {
    const base = slugify(displayName);
    for (let i = 0; i < 4; i += 1) {
      const id = i === 0 ? base : base + '-' + Math.random().toString(36).slice(2, 6);
      try {
        return await send(id);
      } catch (e) {
        if (!(e && e.status === 409) || i === 3) throw e;
      }
    }
    return undefined;
  }

  async function deleteDepartment(dept, dlg) {
    const ok = await confirmDialog({ title: t('org.dept.delete.title', { name: dept.name }), message: t('org.dept.delete.text'), detail: dept.memberCount || dept.groupCount ? t('org.dept.delete.notEmpty') : undefined, confirmLabel: t('org.dept.delete'), danger: true, confirmText: dept.name });
    if (!ok) return;
    try {
      await api.del('/departments/' + encodeURIComponent(dept.id));
      dlg.close();
      toast(t('org.dept.deleted'), { kind: 'success' });
      await loadAll();
    } catch (e) {
      toastError(e);
    }
  }

  // ------------------------------------------------------------------ groups
  function renderGroups() {
    const tableHost = h('div', { class: 'table-host' });
    mount(panel, h('div', { class: 'section-actions' }, button({ label: t('org.group.create'), icon: 'plus', variant: 'primary', on: { click: () => createGroup() } })), tableHost);
    if (!groups.length) {
      mount(tableHost, emptyState({ title: t('org.group.empty.title'), text: t('org.group.empty.text'), glyph: 'group', action: button({ label: t('org.group.create'), icon: 'plus', variant: 'primary', on: { click: () => createGroup() } }) }));
      return;
    }
    const deptName = (id) => (departments.find((d) => d.id === id) || {}).name || t('org.group.orgWide');
    const table = createTable({
      ariaLabel: t('org.group.table'),
      rowId: (g) => g.id,
      rowLabel: (g) => g.name,
      onOpen: (g) => openGroup(g.id),
      columns: [
        { key: 'name', label: t('org.col.name'), primary: true, render: (g) => h('span', { class: 'cell-stack' }, h('span', { class: 'cell-title', text: g.name }), h('span', { class: 'cell-sub mono', text: g.id })) },
        { key: 'department', label: t('org.col.department'), className: 'hide-sm', render: (g) => deptName(g.departmentId) },
        { key: 'visibility', label: t('org.col.visibility'), className: 'hide-md', render: (g) => badge(t('org.vis.' + g.visibility), g.visibility === 'LISTED' ? 'neutral' : 'accent') },
        { key: 'join', label: t('org.col.join'), render: (g) => joinModeBadge(g.joinMode) },
        { key: 'members', label: t('org.col.members'), className: 'num', render: (g) => formatNumber(g.memberDeviceIds.length) },
        { key: 'pending', label: t('org.col.pending'), className: 'num hide-sm', render: (g) => (g.pendingCount ? badge(String(g.pendingCount), 'warn') : '0') },
      ],
    });
    table.update({ rows: groups });
    tableHost.appendChild(table.el);
    setActiveTable(table);
  }

  function createGroup() {
    const name = textInput({ maxlength: 128 });
    const nf = field({ label: t('org.group.name'), control: name, required: true });
    const dept = selectInput([{ value: '', label: t('org.group.orgWide') }, ...departments.map((d) => ({ value: d.id, label: d.name }))], '', { ariaLabel: t('org.col.department') });
    const df = field({ label: t('org.col.department'), control: dept, hint: t('org.group.department.hint') });
    const vis = selectInput([{ value: 'LISTED', label: t('org.vis.LISTED') }, { value: 'HIDDEN', label: t('org.vis.HIDDEN') }], 'LISTED', { ariaLabel: t('org.col.visibility') });
    const vf = field({ label: t('org.col.visibility'), control: vis, hint: t('org.vis.hint') });
    const join = selectInput(['REQUEST_APPROVAL', 'OPEN_IN_SCOPE', 'ADMIN_ONLY'].map((m) => ({ value: m, label: t('org.join.' + m) })), 'REQUEST_APPROVAL', { ariaLabel: t('org.col.join') });
    const jf = field({ label: t('org.col.join'), control: join, hint: t('org.join.hint') });
    const message = h('div', { 'aria-live': 'polite' });
    const save = button({ label: t('org.group.create.submit'), variant: 'primary' });
    const dlg = openDialog({ title: t('org.group.create'), body: [nf.el, df.el, vf.el, jf.el, message], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), save] });
    save.addEventListener('click', () =>
      withBusy(save, async () => {
        nf.clearError();
        if (!name.value.trim()) {
          nf.setError(t('org.name.required'));
          return name.focus();
        }
        try {
          await createWithUniqueId((id) => api.post('/groups', { id, name: name.value.trim(), departmentId: dept.value, visibility: vis.value, joinMode: join.value }), name.value);
          dlg.close();
          toast(t('org.group.created'), { kind: 'success' });
          await loadAll();
        } catch (e) {
          mount(message, banner({ tone: 'bad', text: e.message || t('error.generic'), actions: e.correlationId ? h('span', { class: 'ref', text: t('error.reference', { id: e.correlationId }) }) : null }));
        }
        return undefined;
      }),
    );
  }

  function openGroup(id) {
    let group = groups.find((g) => g.id === id);
    if (!group) return;
    const body = h('div', { class: 'stack' });
    const drawer = openDrawer({ title: group.name, body });
    const replace = (updated) => {
      group = updated;
      const i = groups.findIndex((g) => g.id === updated.id);
      if (i >= 0) groups[i] = updated;
      draw();
    };
    function draw() {
      const name = textInput({ value: group.name, maxlength: 128 });
      const nf = field({ label: t('org.group.name'), control: name });
      const vis = selectInput([{ value: 'LISTED', label: t('org.vis.LISTED') }, { value: 'HIDDEN', label: t('org.vis.HIDDEN') }], group.visibility, { ariaLabel: t('org.col.visibility') });
      const vf = field({ label: t('org.col.visibility'), control: vis, hint: t('org.vis.hint') });
      const join = selectInput(['REQUEST_APPROVAL', 'OPEN_IN_SCOPE', 'ADMIN_ONLY'].map((m) => ({ value: m, label: t('org.join.' + m) })), group.joinMode, { ariaLabel: t('org.col.join') });
      const jf = field({ label: t('org.col.join'), control: join, hint: t('org.join.hint') });
      const save = button({ label: t('common.save'), variant: 'secondary', size: 'sm' });
      save.addEventListener('click', () =>
        withBusy(save, async () => {
          try {
            replace(await api.put('/groups/' + encodeURIComponent(group.id), { name: name.value.trim(), visibility: vis.value, joinMode: join.value }));
            toast(t('org.group.saved'), { kind: 'success' });
          } catch (e) {
            toastError(e);
          }
        }),
      );
      const admins = h('div');
      const drawAdmins = () => mount(admins, deviceChips(group.adminDeviceIds, names, removeAdmin, t('org.group.admins')));
      async function removeAdmin(devId) {
        try {
          replace(await api.put('/groups/' + encodeURIComponent(group.id), { adminDeviceIds: group.adminDeviceIds.filter((x) => x !== devId) }));
        } catch (e) {
          toastError(e);
        }
      }
      drawAdmins();
      const adminPicker = devicePicker({
        label: t('org.group.addAdmin'),
        exclude: () => new Set(group.adminDeviceIds),
        onPick: async (d) => {
          names.set(d.deviceId, { name: d.name });
          try {
            replace(await api.put('/groups/' + encodeURIComponent(group.id), { adminDeviceIds: [...group.adminDeviceIds, d.deviceId] }));
          } catch (e) {
            toastError(e);
          }
        },
      });
      const members = h('div');
      const drawMembers = () => mount(members, deviceChips(group.memberDeviceIds, names, removeMember, t('org.group.members')));
      async function removeMember(devId) {
        try {
          replace(await api.del('/groups/' + encodeURIComponent(group.id) + '/members/' + encodeURIComponent(devId)));
        } catch (e) {
          toastError(e);
        }
      }
      drawMembers();
      const memberPicker = devicePicker({
        label: t('org.group.addMember'),
        exclude: () => new Set(group.memberDeviceIds),
        onPick: async (d) => {
          names.set(d.deviceId, { name: d.name });
          try {
            replace(await api.post('/groups/' + encodeURIComponent(group.id) + '/members', { deviceId: d.deviceId }));
          } catch (e) {
            toastError(e);
          }
        },
      });
      const del = button({ label: t('org.group.delete'), icon: 'trash', variant: 'danger', on: { click: () => deleteGroup() } });
      async function deleteGroup() {
        const ok = await confirmDialog({ title: t('org.group.delete.title', { name: group.name }), message: t('org.group.delete.text'), confirmLabel: t('org.group.delete'), danger: true, confirmText: group.name });
        if (!ok) return;
        try {
          await api.del('/groups/' + encodeURIComponent(group.id));
          drawer.close();
          toast(t('org.group.deleted'), { kind: 'success' });
          await loadAll();
        } catch (e) {
          toastError(e);
        }
      }
      mount(
        body,
        h('div', { class: 'drawer-status' }, joinModeBadge(group.joinMode), badge(t('org.vis.' + group.visibility), 'neutral'), group.pendingCount ? badge(t('org.group.pending', { n: group.pendingCount }), 'warn') : null),
        nf.el, vf.el, jf.el, h('div', { class: 'form-actions start' }, save),
        h('h3', { class: 'subhead', text: t('org.group.admins') }), admins, adminPicker.el,
        h('h3', { class: 'subhead', text: t('org.group.members') + ' (' + group.memberDeviceIds.length + ')' }), members, memberPicker.el,
        h('details', { class: 'tech' }, h('summary', { text: t('devices.tech') }), mono(group.id, 'wrap')),
        h('div', { class: 'form-actions start danger-zone' }, del),
      );
    }
    draw();
  }

  // ------------------------------------------------------------------ requests
  function renderRequests() {
    if (!requests.length) {
      mount(panel, emptyState({ title: t('org.req.empty.title'), text: t('org.req.empty.text'), glyph: 'inbox' }));
      return;
    }
    const groupName = (id) => (groups.find((g) => g.id === id) || {}).name || shortId(id);
    mount(
      panel,
      h('p', { class: 'muted', role: 'status', text: t('org.req.count', { n: requests.length }) }),
      h(
        'ul',
        { class: 'request-list' },
        requests.map((r) => {
          const approve = button({ label: t('org.req.approve'), icon: 'check', variant: 'primary', size: 'sm', ariaLabel: t('org.req.approve.for', { name: r.displayName }) });
          const decline = button({ label: t('org.req.decline'), icon: 'close', variant: 'secondary', size: 'sm', ariaLabel: t('org.req.decline.for', { name: r.displayName }) });
          approve.addEventListener('click', () => decide(r, true, approve));
          decline.addEventListener('click', () => decide(r, false, decline));
          return h('li', { class: 'request' }, h('div', { class: 'request-main' }, h('div', { class: 'request-name', text: r.displayName || shortId(r.deviceId) }), h('div', { class: 'muted small' }, t('org.req.wants', { group: groupName(r.groupId) }), ' · ', timeEl(r.requestedAt)), r.message ? h('p', { class: 'request-message', text: r.message }) : null), h('div', { class: 'request-actions' }, decline, approve));
        }),
      ),
    );
  }

  async function decide(r, approve, btn) {
    if (approve) {
      await withBusy(btn, async () => {
        try {
          await api.post('/join-requests/' + encodeURIComponent(r.requestId) + '/approve', {});
          toast(t('org.req.approved', { name: r.displayName }), { kind: 'success' });
          await refreshRequests();
        } catch (e) {
          toastError(e);
        }
      });
      return;
    }
    const reason = textInput({ maxlength: 200 });
    reason.setAttribute('data-autofocus', '');
    const rf = field({ label: t('org.req.reason'), control: reason, hint: t('org.req.reason.hint') });
    const go = button({ label: t('org.req.decline'), variant: 'danger' });
    const dlg = openDialog({ title: t('org.req.decline.title', { name: r.displayName }), size: 'sm', body: [h('p', { text: t('org.req.decline.text') }), rf.el], actions: [button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }), go] });
    go.addEventListener('click', () =>
      withBusy(go, async () => {
        try {
          await api.post('/join-requests/' + encodeURIComponent(r.requestId) + '/reject', reason.value.trim() ? { reason: reason.value.trim() } : {});
          dlg.close();
          toast(t('org.req.declined', { name: r.displayName }), { kind: 'success' });
          await refreshRequests();
        } catch (e) {
          toastError(e);
        }
      }),
    );
  }

  async function refreshRequests() {
    try {
      [requests, groups] = await Promise.all([api.get('/join-requests'), api.get('/groups')]);
      tabBar.setBadge('requests', requests.length);
      ctx.setBadge('organization', requests.length);
      renderTab();
    } catch (e) {
      toastError(e);
    }
  }

  loadAll();
  return {
    destroy() {
      stopped = true;
      setActiveTable(null);
    },
    onRoute(next) {
      ctx = { ...ctx, ...next };
      const wanted = ['departments', 'groups', 'requests'].includes(next.query.tab) ? next.query.tab : 'departments';
      if (wanted !== current) tabBar.select(wanted);
    },
  };
}
