// Announcements: broadcast messages to the entire organisation or specific departments.
// Plaintext on the hub (ADR-138): hub administrators can read all announcements; encryption is NEVER claimed.
// Exclusively uses textContent for rendering text (SEC-07, XSS prevention).

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api } from '../app.js';
import { ApiError } from '../api.js';
import { session } from '../state.js';
import { formatDateTime, formatNumber } from '../format.js';
import { pageHeader, button, badge, emptyState, textInput, selectInput, field, withBusy, banner } from '../ui/widgets.js';
import { createTable } from '../ui/table.js';
import { openDialog, openDrawer, confirmDialog } from '../ui/dialog.js';
import { toast, toastError } from '../ui/toast.js';
import { setActiveTable } from '../shortcuts.js';
import { icon } from '../icons.js';

function isOrgAdmin() {
  const role = (session.role || '').toLowerCase();
  return (role === 'admin' || role === 'org_admin') && (!session.departments || session.departments.length === 0);
}

export function mountAnnouncements(container, ctx) {
  let items = [];
  let departments = [];
  let searchTimer = 0;
  const f = {
    q: ctx.query.q || '',
    scope: ctx.query.scope || '',
    dept: ctx.query.dept || '',
  };

  const isHead = !isOrgAdmin();
  const headDepts = session.departments || [];

  const searchInput = textInput({
    type: 'search',
    value: f.q,
    maxlength: 64,
    placeholder: t('announcements.filter.search'),
  });
  searchInput.setAttribute('data-search', '');

  const scopeOptions = isHead
    ? [{ value: 'department', label: t('announcements.filter.scope.dept') }]
    : [
        { value: '', label: t('announcements.filter.scope.all') },
        { value: 'org', label: t('announcements.filter.scope.org') },
        { value: 'department', label: t('announcements.filter.scope.dept') },
      ];

  const scopeSelect = selectInput(scopeOptions, f.scope || (isHead ? 'department' : ''), {
    ariaLabel: t('announcements.filter.scope'),
  });

  const deptSelect = selectInput([{ value: '', label: t('announcements.filter.dept.all') }], f.dept, {
    ariaLabel: t('announcements.filter.dept'),
  });

  const searchField = field({ label: t('announcements.filter.search'), control: searchInput });
  const scopeField = field({ label: t('announcements.filter.scope'), control: scopeSelect });
  const deptField = field({ label: t('announcements.filter.dept'), control: deptSelect });

  const filterGrid = h(
    'div',
    { class: 'filter-grid', role: 'search', 'aria-label': t('announcements.table') },
    searchField.el,
    scopeField.el,
    deptField.el,
  );

  const resultLine = h('p', { class: 'result-line', role: 'status', 'aria-live': 'polite' });
  const tableHost = h('div', { class: 'table-host' });

  const newBtn = button({
    label: t('announcements.action.create'),
    icon: 'plus',
    variant: 'primary',
    on: { click: () => openComposer() },
  });

  const refreshBtn = button({
    label: t('common.refresh'),
    icon: 'refresh',
    on: { click: () => reload() },
  });

  const table = createTable({
    ariaLabel: t('announcements.table'),
    rowId: (it) => it.id,
    rowLabel: (it) => it.title,
    onOpen: (it) => showDetails(it),
    columns: [
      {
        key: 'scope',
        label: t('announcements.col.scope'),
        render: (it) => {
          if (it.scope === 'org') return badge(t('announcements.filter.scope.org'), 'neutral');
          const dName = departmentName(it.departmentId);
          return badge(dName, 'info');
        },
      },
      {
        key: 'title',
        label: t('announcements.col.title'),
        primary: true,
        render: (it) => h('span', { text: it.title }),
      },
      {
        key: 'author',
        label: t('announcements.col.author'),
        className: 'hide-sm',
        render: (it) => h('span', { text: it.authorName || it.authorRef }),
      },
      {
        key: 'created',
        label: t('announcements.col.created'),
        render: (it) => h('time', {
          datetime: new Date(it.createdAt).toISOString(),
          title: formatDateTime(it.createdAt),
          text: formatDateTime(it.createdAt),
        }),
      },
      {
        key: 'status',
        label: t('announcements.col.status'),
        className: 'hide-md',
        render: (it) => {
          if (it.expiresAt && Date.now() > it.expiresAt) {
            return badge(t('announcements.status.expired'), 'warn');
          }
          return badge(t('announcements.status.active'), 'ok');
        },
      },
      {
        key: 'pinned',
        label: t('announcements.col.pinned'),
        render: (it) => (it.pinned ? badge(t('announcements.status.pinned'), 'accent') : h('span', { class: 'muted', text: '–' })),
      },
    ],
  });

  mount(
    container,
    pageHeader({
      title: t('announcements.title'),
      subtitle: t('announcements.sub'),
      actions: [refreshBtn, newBtn],
    }),
    filterGrid,
    h('div', { class: 'toolbar-row' }, resultLine),
    tableHost,
  );

  setActiveTable(table);

  function departmentName(deptId) {
    if (!deptId) return t('announcements.filter.scope.org');
    const d = departments.find((x) => x.id === deptId);
    return d ? d.name : deptId;
  }

  function syncDeptOptions() {
    const opts = [{ value: '', label: t('announcements.filter.dept.all') }];
    const visibleDepts = isHead ? headDepts : departments;
    for (const d of visibleDepts) {
      opts.push({ value: d.id, label: d.name });
    }
    mount(deptSelect, opts.map((o) => h('option', { value: o.value, selected: o.value === f.dept }, o.label)));
  }

  searchInput.addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      f.q = searchInput.value.trim();
      ctx.replaceQuery({ q: f.q || undefined });
      reload();
    }, 250);
  });

  scopeSelect.addEventListener('change', () => {
    f.scope = scopeSelect.value;
    deptField.el.hidden = f.scope === 'org';
    ctx.replaceQuery({ scope: f.scope || undefined });
    reload();
  });

  deptSelect.addEventListener('change', () => {
    f.dept = deptSelect.value;
    ctx.replaceQuery({ dept: f.dept || undefined });
    reload();
  });

  async function reload() {
    await withBusy(tableHost, async () => {
      try {
        const params = new URLSearchParams();
        if (f.q) params.set('q', f.q);
        if (f.scope) params.set('scope', f.scope);
        if (f.dept) params.set('departmentId', f.dept);
        const queryStr = params.toString() ? '?' + params.toString() : '';
        const res = await api.get('/announcements' + queryStr);
        items = res.items || [];
        draw();
      } catch (e) {
        toastError(e);
      }
    });
  }

  function draw() {
    resultLine.textContent = items.length ? formatNumber(items.length) + ' announcement(s)' : '';
    mount(tableHost);
    if (!items.length) {
      tableHost.appendChild(
        emptyState({
          title: t('announcements.empty.title'),
          text: t('announcements.empty.text'),
          glyph: 'announcements',
        }),
      );
    } else {
      table.update({ rows: items });
      tableHost.appendChild(table.el);
    }
  }

  function showDetails(it) {
    const isAuthor = it.authorRef === session.user;
    const canManage = isOrgAdmin() || isAuthor;

    const editBtn = button({
      label: t('announcements.action.edit'),
      icon: 'edit',
      on: {
        click: () => {
          drawer.close();
          openComposer(it);
        },
      },
    });

    const pinBtn = button({
      label: it.pinned ? t('announcements.action.unpin') : t('announcements.action.pin'),
      icon: 'pin',
      on: {
        click: async () => {
          try {
            const action = it.pinned ? 'unpin' : 'pin';
            await api.post('/announcements/' + encodeURIComponent(it.id) + '/' + action);
            drawer.close();
            toast(t('common.done'), { kind: 'success' });
            reload();
          } catch (e) {
            if (e instanceof ApiError && e.code === 'ERR_PIN_LIMIT') {
              toast(t('announcements.pin.limit'), { kind: 'error' });
            } else {
              toastError(e);
            }
          }
        },
      },
    });

    const delBtn = button({
      label: t('announcements.action.delete'),
      icon: 'trash',
      variant: 'danger',
      on: {
        click: async () => {
          const ok = await confirmDialog({
            title: t('announcements.delete.title'),
            text: t('announcements.delete.text', { title: it.title }),
            confirmLabel: t('announcements.action.delete'),
            danger: true,
          });
          if (!ok) return;
          try {
            await api.del('/announcements/' + encodeURIComponent(it.id));
            drawer.close();
            toast(t('common.done'), { kind: 'success' });
            reload();
          } catch (e) {
            toastError(e);
          }
        },
      },
    });

    const actions = canManage ? [editBtn, pinBtn, delBtn] : [];

    const drawer = openDrawer({
      title: it.title,
      body: [
        h('div', { class: 'details-meta' },
          badge(it.scope === 'org' ? t('announcements.filter.scope.org') : departmentName(it.departmentId), it.scope === 'org' ? 'neutral' : 'info'),
          it.pinned ? badge(t('announcements.status.pinned'), 'accent') : null,
          h('span', { class: 'muted text-sm' }, formatDateTime(it.createdAt)),
        ),
        h('div', { class: 'announcement-body-view' },
          h('p', { class: 'plain-text-body', text: it.body }),
        ),
        banner({ tone: 'info', text: t('announcements.preview.honesty') }),
        h('div', { class: 'drawer-actions form-actions' }, actions),
      ],
    });
  }

  function openComposer(existing = null) {
    const isEdit = !!existing;
    const initialScope = existing ? (existing.scope === 'org' ? 'org' : 'dept:' + existing.departmentId) : (isHead ? 'dept:' + headDepts[0]?.id : 'org');

    const audOptions = [];
    if (!isHead) {
      audOptions.push({ value: 'org', label: t('announcements.field.scope.org') });
    }
    const targetDepts = isHead ? headDepts : departments;
    for (const d of targetDepts) {
      audOptions.push({ value: 'dept:' + d.id, label: d.name });
    }

    const audSelect = selectInput(audOptions, initialScope, { ariaLabel: t('announcements.field.scope') });
    if (isEdit) audSelect.disabled = true;

    const titleIn = textInput({
      value: existing ? existing.title : '',
      maxlength: 120,
      placeholder: t('announcements.field.title.placeholder'),
    });

    const titleCounter = h('span', { class: 'field-counter text-sm muted', text: '0/120' });
    const bodyIn = h('textarea', {
      class: 'input textarea-body',
      rows: 6,
      maxlength: 4000,
      placeholder: t('announcements.field.body.placeholder'),
      text: existing ? existing.body : '',
    });
    const bodyCounter = h('span', { class: 'field-counter text-sm muted', text: '0/4000' });

    const pinCheck = h('input', { type: 'checkbox', class: 'checkbox', checked: existing ? existing.pinned : false });
    const pinLabel = h('label', { class: 'checkbox-label' }, pinCheck, h('span', { text: t('announcements.field.pinned') }));

    // Live preview elements
    const prevAudBadge = badge(t('announcements.filter.scope.org'), 'neutral');
    const prevTitle = h('h3', { class: 'preview-title', text: '' });
    const prevBody = h('p', { class: 'preview-body', text: '' });
    const prevRecipient = h('span', { class: 'preview-recipients text-sm muted', text: '' });
    const prevLinksList = h('ul', { class: 'preview-links' });
    const prevLinksWrap = h('div', { class: 'preview-links-wrap', hidden: true },
      h('span', { class: 'text-sm strong', text: t('announcements.preview.links') }),
      prevLinksList,
    );
    const honestyNote = h('p', { class: 'honesty-note text-sm muted', text: t('announcements.preview.honesty') });

    const previewPanel = h(
      'div',
      { class: 'announcement-preview-panel' },
      h('div', { class: 'preview-header' }, prevAudBadge, prevRecipient),
      prevTitle,
      prevBody,
      prevLinksWrap,
      honestyNote,
    );

    let prevDebounce = 0;
    function updatePreview() {
      clearTimeout(prevDebounce);
      const curTitle = titleIn.value.trim();
      const curBody = bodyIn.value.trim();
      titleCounter.textContent = `${titleIn.value.length}/120`;
      bodyCounter.textContent = `${bodyIn.value.length}/4000`;

      prevTitle.textContent = curTitle || t('announcements.field.title');
      prevBody.textContent = curBody || t('announcements.field.body');

      if (!curTitle || !curBody) {
        prevRecipient.textContent = '';
        prevLinksWrap.hidden = true;
        return;
      }

      prevDebounce = setTimeout(async () => {
        try {
          const selVal = audSelect.value;
          const scope = selVal === 'org' ? 'org' : 'department';
          const deptId = selVal.startsWith('dept:') ? selVal.substring(5) : null;
          const prev = await api.post('/announcements/preview', {
            scope,
            departmentId: deptId,
            title: curTitle,
            body: curBody,
          });
          prevTitle.textContent = prev.title;
          prevBody.textContent = prev.body;
          prevAudBadge.textContent = prev.audienceScope;
          prevRecipient.textContent = t('announcements.preview.recipients', { count: prev.estimatedRecipients });

          mount(prevLinksList);
          if (prev.links && prev.links.length > 0) {
            prevLinksWrap.hidden = false;
            for (const link of prev.links) {
              prevLinksList.appendChild(h('li', { class: 'mono text-sm' }, h('span', { text: link })));
            }
          } else {
            prevLinksWrap.hidden = true;
          }
        } catch (_e) {
          // ignore transient preview validation errors while typing
        }
      }, 200);
    }

    titleIn.addEventListener('input', updatePreview);
    bodyIn.addEventListener('input', updatePreview);
    audSelect.addEventListener('change', updatePreview);

    updatePreview();

    const saveBtn = button({
      label: isEdit ? t('common.save') : t('announcements.action.create'),
      variant: 'primary',
      on: {
        click: async () => {
          const curTitle = titleIn.value.trim();
          const curBody = bodyIn.value.trim();
          if (!curTitle || !curBody) return;

          const selVal = audSelect.value;
          const scope = selVal === 'org' ? 'org' : 'department';
          const deptId = selVal.startsWith('dept:') ? selVal.substring(5) : null;

          try {
            if (isEdit) {
              await api.put('/announcements/' + encodeURIComponent(existing.id), {
                expectedSeq: existing.seq,
                title: curTitle,
                body: curBody,
                pinned: pinCheck.checked,
              });
            } else {
              await api.post('/announcements', {
                scope,
                departmentId: deptId,
                title: curTitle,
                body: curBody,
                pinned: pinCheck.checked,
              });
            }
            dlg.close();
            toast(t('common.done'), { kind: 'success' });
            reload();
          } catch (e) {
            if (e instanceof ApiError && e.status === 409) {
              const reloadConfirm = await confirmDialog({
                title: t('announcements.conflict.title'),
                text: t('announcements.conflict.text'),
                confirmLabel: t('announcements.conflict.reload'),
              });
              if (reloadConfirm) {
                dlg.close();
                reload();
              }
            } else if (e instanceof ApiError && e.code === 'ERR_PIN_LIMIT') {
              toast(t('announcements.pin.limit'), { kind: 'error' });
            } else {
              toastError(e);
            }
          }
        },
      },
    });

    const dlg = openDialog({
      title: isEdit ? t('announcements.edit.title') : t('announcements.create.title'),
      size: 'lg',
      body: [
        field({ label: t('announcements.field.scope'), control: audSelect }).el,
        h('div', { class: 'field-with-counter' },
          field({ label: t('announcements.field.title'), control: titleIn }).el,
          titleCounter,
        ),
        h('div', { class: 'field-with-counter' },
          h('div', { class: 'field' },
            h('label', { class: 'label', text: t('announcements.field.body') }),
            bodyIn,
          ),
          bodyCounter,
        ),
        h('div', { class: 'field' }, pinLabel),
        h('div', { class: 'preview-container' },
          h('h4', { class: 'preview-heading', text: t('announcements.preview.title') }),
          previewPanel,
        ),
      ],
      actions: [
        button({ label: t('common.cancel'), variant: 'ghost', on: { click: () => dlg.close() } }),
        saveBtn,
      ],
    });
  }

  // Initial load
  (async () => {
    try {
      departments = await api.get('/departments').catch(() => []);
      syncDeptOptions();
      await reload();
    } catch (e) {
      toastError(e);
    }
  })();

  return {
    destroy() {
      clearTimeout(searchTimer);
    },
  };
}
