import { h } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { state, approveRequest, declineRequest } from '../store.js';
import { initials } from '../format.js';
import { pageHead, srHeading } from './common.js';
import { openDialog, confirmAction, emptyState, toast } from '../ui.js';

function approveDialog(request, onDone) {
  const confirmed = h('input', { class: 'check', type: 'checkbox' });
  const approveBtn = h('button', { class: 'btn btn-primary', type: 'button', disabled: true }, 'Approve request');
  confirmed.addEventListener('change', () => { approveBtn.disabled = !confirmed.checked; });
  approveBtn.addEventListener('click', () => {
    approveRequest(request.id);
    dialog.close();
    onDone();
    toast('Join request approved', 'ok');
  });
  const dialog = openDialog({
    title: `Approve ${request.name}?`,
    body: [
      h('p', { class: 'dialog-message', text: `${request.name} joins ${request.department} as a member. The hub adds this device to the directory and signs its certificate.` }),
      h('div', { class: 'stack tight' },
        h('span', { class: 'label', text: 'Confirm on your approver phone' }),
        h('div', { class: 'stepup-code', text: '482 915', 'aria-label': 'Approval code 482 915' }),
        h('p', { class: 'hint', text: 'Open Flash on the approver phone and check that it shows the same six digits. If they differ, do not approve.' }),
        h('label', { class: 'check-row' }, confirmed, h('span', { class: 'small', text: 'The approver phone shows the same six digits' })),
      ),
      h('div', { class: 'banner banner-info' },
        h('span', { class: 'banner-icon', style: { color: 'var(--accent-ink)' } }, icon('info', { size: 16 })),
        h('div', {}, h('div', { class: 'banner-title', text: 'This browser signs the decision' }), h('div', { class: 'banner-text', text: 'The approval is bound to this session and recorded in the audit log.' })),
      ),
    ],
    footer: [
      h('button', { class: 'btn btn-ghost', type: 'button', on: { click: () => dialog.close() } }, 'Cancel'),
      approveBtn,
    ],
  });
  return dialog;
}

function declineDialog(request, onDone) {
  confirmAction({
    title: `Decline ${request.name}?`,
    message: 'The request is removed and the person is told that an administrator declined it. They need a new invite to try again.',
    confirmLabel: 'Decline request',
    danger: true,
    onConfirm: () => {
      declineRequest(request.id);
      onDone();
      toast('Join request declined', 'info');
    },
  });
}

export function mountRequests(container, ctx) {
  function build() {
    container.replaceChildren();
    const requests = [...state.requests].sort((a, b) => b.askedMinutes - a.askedMinutes);
    const oldest = requests.length ? requests[0].asked : '—';

    const head = pageHead({
      title: 'Join requests',
      lede: [h('span', { class: 'lede-sentence' },
        h('span', { class: `dot dot-${requests.length ? 'warn' : 'ok'}` }),
        ' ',
        requests.length
          ? `${requests.length} people waiting, oldest ${oldest} ago. Approving adds the device to the directory.`
          : 'No one is waiting. New requests appear here when an invite is used.',
      )],
      actions: [
        h('button', {
          class: 'btn btn-secondary', type: 'button',
          on: { click: () => exportCsv(requests) },
        }, icon('download', { size: 15 }), 'Export list'),
      ],
    });

    const rows = requests.map((request) => h('div', { class: 'request-row' },
      h('span', { class: 'avatar', text: initials(request.name) }),
      h('div', { class: 'request-main' },
        h('div', { class: 'request-name-line' },
          h('span', { class: 'request-name', text: request.name }),
          request.unpaired ? h('span', { class: 'chip chip-neutral', text: 'Not paired' }) : null,
        ),
        h('div', { class: 'request-meta', text: `${request.platform} · ${request.department} · asked ${request.asked}` }),
        h('p', { class: 'request-message', text: request.message }),
      ),
      h('div', { class: 'request-actions' },
        h('button', { class: 'btn btn-secondary btn-sm', type: 'button', on: { click: () => approveDialog(request, build) } }, icon('check', { size: 14 }), 'Approve'),
        h('button', { class: 'btn btn-ghost btn-sm', type: 'button', on: { click: () => declineDialog(request, build) } }, 'Decline'),
      ),
    ));

    const panel = requests.length
      ? h('section', { class: 'panel request-panel', 'aria-label': 'Join requests' }, rows)
      : h('section', { class: 'panel' }, emptyState({
        title: 'No one is waiting',
        text: 'When someone uses an invite, their request appears here with the device and department they chose.',
        action: h('a', { class: 'btn btn-secondary', href: '#/invites' }, 'Create an invite'),
      }));

    container.append(
      head,
      srHeading('Join request queue'),
      panel,
      h('p', { class: 'hint', style: { marginTop: '12px' }, text: 'Not paired means no existing member vouches for the device; an administrator is approving it directly.' }),
    );
  }

  function exportCsv(requests) {
    const header = ['Name', 'Platform', 'Department', 'Asked', 'Message'];
    const lines = requests.map((r) => [r.name, r.platform, r.department, r.asked, r.message]
      .map((v) => `"${String(v).replaceAll('"', '""')}"`).join(','));
    const blob = new Blob([[header.join(','), ...lines].join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: 'flash-join-requests.csv' });
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast('Join requests exported as CSV', 'ok');
  }

  container.replaceChildren();
  build();
}
