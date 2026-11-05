import { h, mount } from '../dom.js';
import { icon } from '../icons.js';
import { data } from '../data.js';
import { n, relative } from '../format.js';
import { state } from '../store.js';
import { pageHead, tabBar, srHeading } from './common.js';
import { confirmAction, fingerprintBlock, copyButton, toast } from '../ui.js';

function delegationPanel() {
  const d = data.delegation;
  const command = h('div', { class: 'code-block', text: d.command });
  return h('section', { class: 'panel delegation-card' },
    h('div', { class: 'inline between' },
      h('h2', { class: 'panel-title', text: 'Delegation' }),
      h('span', { class: 'chip chip-warn', text: 'Expires soon' }),
    ),
    h('div', { class: 'delegation-days' }, h('strong', { text: String(d.daysLeft) }), h('span', { text: 'days left' })),
    h('p', { class: 'panel-sub', text: `The hub may sign directory statements and device certificates until ${d.expiresAt}.` }),
    h('dl', { class: 'definition-list delegation-details' },
      h('dt', { text: 'Serial' }),
      h('dd', {}, h('span', { class: 'mono', text: d.serial })),
      h('dt', { text: 'Issued' }),
      h('dd', { text: d.issuedAt }),
      h('dt', { text: 'Expires' }),
      h('dd', { text: d.expiresAt }),
      h('dt', { text: 'Signed by' }),
      h('dd', { text: 'Root key · offline' }),
    ),
    h('div', { class: 'stack tight', style: { marginTop: '20px' } },
      h('div', { class: 'inline between' },
        h('span', { class: 'label', text: 'Issue the next delegation' }),
        copyButton(d.command, 'Copy command'),
      ),
      command,
      h('p', { class: 'hint', text: 'Run this on the offline machine that holds the root key. Install the result under Security before the current delegation expires.' }),
    ),
  );
}

function trustChainPanel() {
  const chain = [
    { label: 'Root key', detail: 'Offline · never changes', value: data.delegation.rootFingerprint.split(' ').slice(0, 3).join(' ') + '…', tone: 'chip-ok', role: 'Pinned' },
    { label: 'Hub delegation', detail: `Valid to ${data.delegation.expiresAt}`, value: data.delegation.serial, tone: 'chip-warn', role: 'Expiring' },
    { label: 'Audit signer', detail: `${n(state.auditEntries)} entries · chain intact`, value: 'audit-v2', tone: 'chip-neutral', role: 'Signed' },
  ];
  return h('section', { class: 'panel trust-card' },
    h('h2', { class: 'panel-title', text: 'Trust chain' }),
    h('p', { class: 'panel-sub', text: 'Every statement is checked from the root down. A missing link refuses the statement.' }),
    h('div', { class: 'chain-list' }, chain.map((item) => h('div', { class: 'chain-row' },
      h('span', { class: `chip ${item.tone}`, text: item.role }),
      h('div', { class: 'chain-main' },
        h('div', { class: 'chain-title', text: item.label }),
        h('div', { class: 'chain-detail', text: item.detail }),
      ),
      h('span', { class: 'mono faint', text: item.value }),
    ))),
    h('p', { class: 'hint', text: 'The hub can prove what it signed, but not that the offline root key stayed offline. That is a process control, not a technical one.' }),
  );
}

function trustPanel() {
  const d = data.delegation;
  return h('section', { class: 'panel trust-card' },
    h('h2', { class: 'panel-title', text: 'Root identity' }),
    h('p', { class: 'panel-sub', text: 'Every device pins this key when it enrols. It does not change.' }),
    h('div', { class: 'fingerprint-row' },
      fingerprintBlock(d.rootFingerprint),
      h('div', { class: 'stack tight' },
        h('span', { class: 'faint small', text: 'SHA-256 fingerprint' }),
        h('div', { class: 'copy-row' }, h('span', { class: 'copy-value', text: d.rootFingerprint }), copyButton(d.rootFingerprint, 'Copy root fingerprint')),
      ),
    ),
    h('p', { class: 'hint', text: 'Compare this fingerprint with the one printed when the hub was created. If they differ, stop and contact the operator of the offline root key.' }),
    h('div', { class: 'inline' },
      h('span', { class: 'chip chip-ok', text: 'Pinned' }),
      h('span', { class: 'chip chip-neutral', text: 'Delegation key' }),
      h('span', { class: 'chip chip-neutral', text: 'Audit signer' }),
    ),
  );
}

export function mountSecurity(container, ctx) {
  const tabsDef = [
    { id: 'administrators', label: 'Administrators', count: data.administrators.length },
    { id: 'sessions', label: 'Sessions', count: data.sessions.length },
    { id: 'delegation', label: 'Delegation' },
    { id: 'revocations', label: 'Revocations', count: data.revocations.rows.length },
    { id: 'trust', label: 'Trust' },
  ];
  const tabIds = tabsDef.map((t) => t.id);
  const requested = ctx.query.get('tab');
  const active = tabIds.includes(requested) ? requested : 'administrators';

  function selectTab(id) {
    ctx.setQuery(id === 'administrators' ? {} : { tab: id });
  }

  const head = pageHead({
    title: 'Security',
    lede: `${n(data.administrators.length)} administrators, at least two recommended. Revocation list v${data.revocations.listVersion}.`,
  });

  const tabs = tabBar({ items: tabsDef, active, label: 'Security views', onSelect: selectTab });
  tabs.classList.add('tabs-wide');
  const tabSelect = h('select', {
    class: 'select tabs-select', 'aria-label': 'Security view',
    on: { change: (event) => selectTab(event.target.value) },
  }, tabsDef.map((item) => h('option', { value: item.id, text: item.count !== undefined ? `${item.label} (${item.count})` : item.label })));
  tabSelect.value = active;
  const body = h('div');

  if (active === 'administrators') {
    body.append(
      h('div', { class: 'security-summary' },
        h('span', { class: 'wait-icon' }, icon('key', { size: 18 })),
        h('div', { class: 'security-summary-main' },
          h('div', { class: 'security-summary-title', text: 'The delegation is valid for 9 more days' }),
          h('div', { class: 'security-summary-text', text: 'Issue the next delegation from the offline root key before 14 November.' }),
        ),
        h('div', { class: 'security-summary-value', text: '9 days' }),
        h('a', { class: 'btn btn-secondary btn-sm', href: '#/security?tab=delegation' }, 'Open', icon('chevronRight', { size: 14 })),
      ),
      h('div', { class: 'table-wrap' },
        h('div', { class: 'table-scroll' },
          h('table', { class: 'table', 'aria-label': 'Administrators' },
            h('thead', {}, h('tr', {},
              h('th', { scope: 'col', text: 'Administrator' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Role' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Last active' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Second factor' }),
              h('th', { scope: 'col', class: 'hide-md', text: 'Session' }),
            )),
            h('tbody', {}, data.administrators.map((person) => h('tr', { class: 'row' },
              h('td', {}, h('span', { class: 'identity' },
                h('span', { class: 'avatar', text: person.initials }),
                h('span', { class: 'cell-stack' },
                  h('span', { class: 'cell-title-line' },
                    h('span', { class: 'cell-title', text: person.name }),
                    h('span', { class: 'chip chip-ok admin-2fa-sub', text: person.twoFactor }),
                  ),
                  person.thisBrowser ? h('span', { class: 'cell-sub', text: 'This browser' }) : null,
                  h('span', { class: 'cell-sub admin-role-sub', text: person.roleKey === 'org_admin' ? 'Organization admin · All departments' : 'Department admin · Field Operations' }),
                ),
              )),
              h('td', { class: 'hide-sm' }, h('span', { class: 'cell-stack' },
                h('span', { text: person.roleKey === 'org_admin' ? 'Organization admin' : 'Department admin' }),
                h('span', { class: 'cell-sub', text: person.roleKey === 'dept_admin' ? 'Field Operations' : 'All departments' }),
              )),
              h('td', { class: 'hide-sm muted' }, person.lastActive),
              h('td', { class: 'hide-sm' }, h('span', { class: 'chip chip-ok', text: person.twoFactor })),
              h('td', { class: 'hide-md' }, person.thisBrowser ? h('span', { class: 'chip chip-neutral', text: 'Current' }) : h('span', { class: 'muted', text: '—' })),
            ))),
          ),
        ),
        h('div', { class: 'table-foot' },
          h('span', { text: `${n(data.administrators.length)} administrators · at least two recommended` }),
          h('span', { text: 'Department admins are scoped to their own department' }),
        ),
      ),
      h('div', { class: 'security-lower' },
        h('section', { class: 'panel' },
          h('div', { class: 'panel-head', style: { paddingBottom: '6px' } },
            h('h2', { class: 'panel-title', text: 'Recent administrator activity' }),
            h('a', { class: 'text-action', href: '#/audit' }, 'Audit'),
          ),
          h('div', { class: 'security-lower-list' },
            data.audit.entries
              .filter((entry) => /admin|stepup|delegation|settings/.test(entry.action))
              .slice(0, 3)
              .map((entry) => h('div', { class: 'security-lower-row' },
                h('div', { class: 'security-lower-main' },
                  h('div', { class: 'security-lower-title', text: entry.label }),
                  h('div', { class: 'security-lower-sub', text: entry.detail }),
                ),
                h('span', { class: 'faint small nowrap', text: `${relative(entry.minutesAgo)} ago` }),
              )),
          ),
        ),
        h('section', { class: 'panel' },
          h('div', { class: 'panel-head', style: { paddingBottom: '6px' } },
            h('h2', { class: 'panel-title', text: 'Sign-in policy' }),
          ),
          h('dl', { class: 'definition-list policy-list' },
            h('dt', { text: 'Sign in' }), h('dd', { text: 'Password + authenticator' }),
            h('dt', { text: 'Session' }), h('dd', { text: '30 minutes idle' }),
            h('dt', { text: 'Lockout' }), h('dd', { text: '5 attempts, then 15 minutes' }),
            h('dt', { text: 'Dangerous actions' }), h('dd', { text: 'Approver phone + this browser' }),
            h('dt', { text: 'Recovery' }), h('dd', { text: 'Single-use codes' }),
          ),
        ),
      ),
    );
  }

  if (active === 'sessions') {
    body.append(h('div', { class: 'panel' },
      h('div', { class: 'session-list' }, data.sessions.map((session) => h('div', { class: 'session-row-item' },
        h('span', { class: 'avatar', text: session.current ? 'RO' : '·' }),
        h('div', { class: 'session-row-main' },
          h('div', { class: 'session-row-title', text: session.browser }),
          h('div', { class: 'session-row-sub', text: `${session.place} · started ${session.started}` }),
        ),
        session.current
          ? h('span', { class: 'chip chip-neutral', text: 'This browser' })
          : h('button', {
            class: 'btn btn-danger btn-sm', type: 'button',
            on: {
              click: () => confirmAction({
                title: `Sign out ${session.browser}?`,
                message: 'That browser loses access immediately. Any unsaved form contents there are lost.',
                confirmLabel: 'Sign out session',
                danger: true,
                onConfirm: () => {
                  const idx = data.sessions.findIndex((s) => s.id === session.id);
                  if (idx >= 0) data.sessions.splice(idx, 1);
                  container.replaceChildren();
                  mountSecurity(container, ctx);
                  toast('Session signed out', 'ok');
                },
              }),
            },
          }, 'Sign out'),
      ))),
    ));
  }

  if (active === 'delegation') {
    body.append(
      h('p', { class: 'security-tab-intro', text: 'The hub signs with a delegation, never with the root key. The root key stays offline; the hub holds only the signed delegation and its own key.' }),
      h('div', { class: 'security-grid' }, delegationPanel(), trustPanel()),
    );
  }

  if (active === 'revocations') {
    body.append(
      h('div', { class: 'security-summary' },
        h('span', { class: 'wait-icon' }, icon('shield', { size: 18 })),
        h('div', { class: 'security-summary-main' },
          h('div', { class: 'security-summary-title', text: `Revocation list v${data.revocations.listVersion} is published` }),
          h('div', { class: 'security-summary-text', text: `${data.revocations.revokedSerials} serials and ${data.revocations.revokedDevices} device ids are refused at every connection.` }),
        ),
        h('span', { class: 'chip chip-ok', text: 'Published' }),
      ),
      h('div', { class: 'table-wrap' },
        h('div', { class: 'table-scroll' },
          h('table', { class: 'table', 'aria-label': 'Revoked devices' },
            h('thead', {}, h('tr', {},
              h('th', { scope: 'col', text: 'Serial' }),
              h('th', { scope: 'col', text: 'Device' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Revoked' }),
              h('th', { scope: 'col', class: 'hide-sm', text: 'Reason' }),
            )),
            h('tbody', {}, data.revocations.rows.map((row) => h('tr', { class: 'row' },
              h('td', {}, h('span', { class: 'mono', text: row.serial })),
              h('td', {}, row.device),
              h('td', { class: 'hide-sm muted' }, row.at),
              h('td', { class: 'hide-sm muted' }, row.reason),
            ))),
          ),
        ),
        h('div', { class: 'table-foot' },
          h('span', { text: `List version ${data.revocations.listVersion} · no unpublished changes` }),
          h('span', { text: 'Refused at the relay and at enrolment' }),
        ),
      ),
    );
  }

  if (active === 'trust') {
    body.append(
      h('p', { class: 'security-tab-intro', text: 'Identity is proved with keys, not names. These are the keys this hub answers for; a device that does not match is refused before any message is read.' }),
      h('div', { class: 'security-grid' }, trustPanel(), trustChainPanel()),
    );
  }

  container.append(head, srHeading(tabsDef.find((item) => item.id === active)?.label || 'Security'), h('div', { class: 'screen-tabs' }, tabs, h('div', { class: 'tabs-select-wrap' }, tabSelect)), body);
}
