import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { state, publishAnnouncement, recordAudit } from '../store.js';
import { n, relative, initials } from '../format.js';
import { pageHead, tabBar, srHeading } from './common.js';
import { openDialog, toast } from '../ui.js';

function reader(announcement, onChanged) {
  const pinBtn = h('button', {
    class: 'btn btn-secondary', type: 'button',
    on: {
      click: () => {
        announcement.pinned = !announcement.pinned;
        recordAudit({
          action: announcement.pinned ? 'announcement.pin' : 'announcement.unpin',
          label: announcement.pinned ? 'Announcement pinned' : 'Announcement unpinned',
          detail: announcement.title,
        });
        dialog.close();
        onChanged();
        toast(announcement.pinned ? 'Pinned to the top of every feed' : 'Pin removed', 'ok');
      },
    },
  }, icon('pin', { size: 14 }), announcement.pinned ? 'Remove pin' : 'Pin to top');
  const dialog = openDialog({
    title: announcement.title,
    size: 'dialog-md',
    body: [
      h('div', { class: 'inline' },
        announcement.pinned ? h('span', { class: 'chip chip-neutral' }, icon('pin', { size: 12 }), 'Pinned') : null,
        h('span', { class: 'chip chip-neutral', text: announcement.scope }),
        h('span', { class: 'faint small', text: `${announcement.author} · ${relative(announcement.minutesAgo)}` }),
      ),
      h('p', { class: 'reader-body', text: announcement.body }),
      h('div', { class: 'banner banner-info' },
        h('span', { class: 'banner-icon' }, icon('info', { size: 16 })),
        h('div', {}, h('div', { class: 'banner-title', text: 'Plain text on the hub' }), h('div', { class: 'banner-text', text: 'The hub stores this update unencrypted. Every enrolled device addressed by the audience can read it.' })),
      ),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Close'),
      pinBtn,
    ],
  });
  return dialog;
}

function compose() {
  const title = h('input', { class: 'input', placeholder: 'A short, specific title', 'aria-label': 'Announcement title' });
  const body = h('textarea', { class: 'textarea', rows: '6', placeholder: 'What changed, who it affects, and what to do next.', 'aria-label': 'Announcement body' });
  const audience = h('select', { class: 'select', 'aria-label': 'Audience' },
    h('option', { value: 'All departments', text: 'All departments · 128 devices' }),
    data.departments.map((d) => h('option', { value: d.name, text: `${d.name} · ${d.members} devices` })),
  );
  const form = h('form', {
    class: 'compose-form',
    on: {
      submit: (event) => {
        event.preventDefault();
        if (!title.value.trim()) { title.focus(); toast('Add a title', 'bad'); return; }
        if (!body.value.trim()) { body.focus(); toast('Add the update text', 'bad'); return; }
        publishAnnouncement({ title: title.value.trim(), body: body.value.trim(), scope: audience.value });
        build();
        toast('Update published', 'ok');
      },
    },
  },
    h('label', { class: 'field' }, h('span', { class: 'label', text: 'Title' }), title),
    h('label', { class: 'field' }, h('span', { class: 'label', text: 'Update' }), body),
    h('label', { class: 'field' }, h('span', { class: 'label', text: 'Audience' }), audience),
    h('button', { class: 'btn btn-primary btn-block', type: 'submit' }, icon('announcements', { size: 15 }), 'Publish update'),
    h('p', { class: 'compose-note', text: 'Published updates are recorded in the audit log and cannot be unsent from a device that already synced.' }),
  );

  return h('section', { class: 'panel compose-panel' },
    h('div', { class: 'panel-head', style: { paddingBottom: '14px' } },
      h('div', {},
        h('h2', { class: 'panel-title', text: 'Share an update' }),
        h('p', { class: 'panel-sub', text: 'One update, one audience' }),
      ),
    ),
    h('div', { class: 'panel-body tight' }, form),
  );
}

function narrowComposeDialog(onPublished) {
  const title = h('input', { class: 'input', placeholder: 'A short, specific title', 'aria-label': 'Announcement title' });
  const body = h('textarea', { class: 'textarea', rows: '5', placeholder: 'What changed, who it affects, and what to do next.', 'aria-label': 'Announcement body' });
  const audience = h('select', { class: 'select', 'aria-label': 'Audience' },
    h('option', { value: 'All departments', text: 'All departments · 128 devices' }),
    data.departments.map((d) => h('option', { value: d.name, text: `${d.name} · ${d.members} devices` })),
  );
  const dialog = openDialog({
    title: 'Share an update',
    body: [
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Title' }), title),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Update' }), body),
      h('label', { class: 'field' }, h('span', { class: 'label', text: 'Audience' }), audience),
      h('p', { class: 'hint', text: 'Stored in plain text on the hub; every enrolled device in the audience can read it.' }),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Cancel'),
      h('button', {
        class: 'btn btn-primary', type: 'button',
        on: {
          click: () => {
            if (!title.value.trim()) { title.focus(); toast('Add a title', 'bad'); return; }
            if (!body.value.trim()) { body.focus(); toast('Add the update text', 'bad'); return; }
            publishAnnouncement({ title: title.value.trim(), body: body.value.trim(), scope: audience.value });
            dialog.close();
            onPublished();
            toast('Update published', 'ok');
          },
        },
      }, 'Publish update'),
    ],
  });
  return dialog;
}

export function mountAnnouncements(container, ctx) {
  let filter = 'all';
  let feedHost;
  let tabsHost;

  function visible() {
    return [...state.announcements]
      .sort((a, b) => (Number(b.pinned) - Number(a.pinned)) || (a.minutesAgo - b.minutesAgo))
      .filter((a) => filter !== 'pinned' || a.pinned);
  }

  function renderFeed() {
    const items = visible();
    mount(feedHost);
    if (!items.length) {
      feedHost.append(h('div', { class: 'empty' },
        h('div', { class: 'empty-title', text: 'Nothing pinned yet' }),
        h('p', { class: 'empty-text', text: 'Pin an update from the list to keep it at the top of every device.' }),
      ));
      return;
    }
    feedHost.append(h('div', { class: 'announce-feed' }, items.map((announcement) => h('button', {
      class: 'announce-row', type: 'button', on: { click: () => reader(announcement, build) },
    },
      h('div', { class: 'announce-title-line' },
        h('span', { class: 'announce-title', text: announcement.title }),
        announcement.pinned ? h('span', { class: 'chip chip-neutral' }, icon('pin', { size: 11 }), 'Pinned') : null,
      ),
      h('p', { class: 'announce-body', text: announcement.body }),
      h('div', { class: 'announce-meta' },
        h('span', { text: announcement.author }),
        h('span', { class: 'pipe', 'aria-hidden': 'true', text: '·' }),
        h('span', { text: announcement.scope }),
        h('span', { class: 'pipe', 'aria-hidden': 'true', text: '·' }),
        h('span', { text: relative(announcement.minutesAgo) }),
      ),
    ))));
  }

  function build() {
    container.replaceChildren();
    const pinned = state.announcements.filter((a) => a.pinned).length;
    const head = pageHead({
      title: 'Announcements',
      lede: `${n(state.announcements.length)} updates, ${n(pinned)} pinned. Org-wide and named department audiences.`,
      actions: [
        h('button', {
          class: 'btn btn-primary announce-create-narrow', type: 'button',
          on: { click: () => narrowComposeDialog(() => build()) },
        }, icon('announcements', { size: 15 }), 'Share an update'),
      ],
    });

    const notice = h('div', { class: 'banner banner-info', role: 'note' },
      h('span', { class: 'banner-icon' }, icon('info', { size: 17 })),
      h('div', { class: 'grow' },
        h('div', { class: 'banner-title', text: 'Stored in plain text on this hub' }),
        h('div', { class: 'banner-text', text: 'Announcements are readable by the hub and by every enrolled device addressed by the audience. Do not put secrets in them.' }),
      ),
    );

    tabsHost = h('div', { class: 'screen-tabs' });
    const tabs = tabBar({
      items: [
        { id: 'all', label: 'All updates', count: state.announcements.length },
        { id: 'pinned', label: 'Pinned', count: pinned },
      ],
      active: filter,
      label: 'Announcement filters',
      onSelect: (id) => {
        filter = id;
        for (const btn of tabsHost.querySelectorAll('.tab')) btn.setAttribute('aria-pressed', btn.dataset.tab === id ? 'true' : 'false');
        renderFeed();
      },
    });
    mount(tabsHost, tabs);

    feedHost = h('div');
    const feedPanel = h('section', { class: 'panel announce-feed-panel' }, feedHost);

    container.append(
      head,
      notice,
      srHeading('Update feed'),
      h('div', { class: 'announce-grid' },
        h('div', { class: 'stack', style: { gap: '0' } }, tabsHost, feedPanel),
        compose(),
      ),
    );
    renderFeed();
  }

  build();
}
