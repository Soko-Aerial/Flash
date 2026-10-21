// Settings and system: the hub's effective configuration (read only, it is set in the server's configuration, not here), the two invite
// defaults that an administrator may change (with step-up), health and counters, and "about" with the keyboard shortcuts.

import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import { api, guarded } from '../app.js';
import { formatNumber, formatBytes, formatDuration, formatDateTime } from '../format.js';
import { pageHeader, card, badge, banner, button, field, textInput, withBusy, dl, mono, tabs, copyButton, timeEl } from '../ui/widgets.js';
import { loadInto } from '../ui/boundary.js';
import { toast } from '../ui/toast.js';
import { openHelp } from '../shortcuts.js';
import { FILE_LIMITS, toDisplay, parseForm, combinationWarnings, changedKeys, topUsage } from '../filelimits.js';

const TABS = ['config', 'health', 'about'];
export const CONSOLE_VERSION = '1.0.0';
const DOCS = [
  ['docs/ui/hub-admin-console.md', 'settings.about.doc.console'],
  ['docs/protocol.md', 'settings.about.doc.protocol'],
  ['docs/security.md', 'settings.about.doc.security'],
  ['docs/decisions.md', 'settings.about.doc.decisions'],
  ['docs/audit/2026-10-12-server-hub-audit.md', 'settings.about.doc.audit'],
];

export function mountSettings(container, ctx) {
  let current = TABS.includes(ctx.query.tab) ? ctx.query.tab : 'config';
  let ready = false;
  const tabBar = tabs({
    label: t('settings.tabs'),
    selected: current,
    items: TABS.map((id) => ({ id, label: t('settings.tab.' + id) })),
    onSelect: (id) => {
      current = id;
      if (!ready) return;
      ctx.replaceQuery({ tab: id === 'config' ? '' : id });
      renderTab();
    },
  });
  const panel = tabBar.panel;
  mount(container, pageHeader({ title: t('settings.title'), subtitle: t('settings.sub') }), tabBar.el, panel);

  function renderTab() {
    ({ config: renderConfig, health: renderHealth, about: renderAbout })[current]();
  }

  // ------------------------------------------------------------------ configuration
  async function renderConfig() {
    const host = h('div', { class: 'stack' });
    mount(panel, host);
    await loadInto(host, {
      load: () => api.get('/settings'),
      render: (s) => [
        banner({ tone: 'info', text: t('settings.config.note') }),
        card(
          { title: t('settings.invite.title'), subtitle: t('settings.invite.sub'), id: 'set-invite' },
          inviteDefaults(s),
        ),
        card(
          { title: t('settings.files.title'), subtitle: t('settings.files.sub'), id: 'set-files' },
          fileLimitsForm(s),
          fileUsage(),
        ),
        card(
          { title: t('settings.hub.title'), id: 'set-hub' },
          dl([
            [t('settings.hub.name'), s.hubName],
            [t('settings.hub.url'), mono(s.externalUrl)],
            [t('settings.hub.quota'), h('span', { class: 'num', text: formatNumber(s.deviceQuota) })],
            [t('settings.hub.mailbox'), t('settings.hub.mailbox.v', { n: s.mailboxTtlDays })],
            [t('settings.hub.challenge'), formatDuration(s.challengeTimeoutMs)],
            [t('settings.hub.delegationFile'), mono(s.delegationFile)],
          ]),
        ),
        card(
          { title: t('settings.net.title'), subtitle: t('settings.net.sub'), id: 'set-net' },
          dl([
            [t('settings.net.tls'), badge(s.tlsMode, s.tlsMode === 'none' ? 'warn' : 'ok')],
            [t('settings.net.port'), mono(String(s.port))],
            [t('settings.net.adminPort'), mono(String(s.adminPort))],
            [t('settings.net.public'), s.adminOnPublicPort ? badge(t('settings.net.public.yes'), 'warn') : badge(t('settings.net.public.no'), 'ok')],
            [t('settings.net.proxies'), String(s.trustedProxies)],
            [t('settings.net.insecure'), s.allowInsecure ? badge(t('settings.net.insecure.yes'), 'bad') : badge(t('settings.net.insecure.no'), 'ok')],
          ]),
        ),
      ],
    });
  }

  function inviteDefaults(s) {
    const ttl = textInput({ type: 'number', value: String(s.inviteDefaultTtlMinutes), className: 'input-num' });
    ttl.min = '5';
    ttl.max = '43200';
    const uses = textInput({ type: 'number', value: String(s.inviteDefaultMaxUses), className: 'input-num' });
    uses.min = '1';
    const tf = field({ label: t('settings.invite.ttl'), control: ttl, hint: t('settings.invite.ttl.hint') });
    const uf = field({ label: t('settings.invite.uses'), control: uses, hint: t('settings.invite.uses.hint') });
    const save = button({ label: t('settings.invite.save'), variant: 'primary', type: 'submit' });
    const form = h('form', { class: 'stack', novalidate: true }, h('div', { class: 'inline-fields' }, tf.el, uf.el), h('div', { class: 'form-actions start' }, save));
    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      tf.clearError();
      uf.clearError();
      const ttlN = Number.parseInt(ttl.value, 10);
      const usesN = Number.parseInt(uses.value, 10);
      if (!(ttlN >= 5 && ttlN <= 43200)) return tf.setError(t('settings.invite.ttl.invalid'));
      if (!(usesN >= 1 && usesN <= 1000)) return uf.setError(t('settings.invite.uses.invalid'));
      return withBusy(save, async () => {
        const changes = [];
        if (ttlN !== s.inviteDefaultTtlMinutes) changes.push(['invite.defaultTtlMinutes', String(ttlN)]);
        if (usesN !== s.inviteDefaultMaxUses) changes.push(['invite.defaultMaxUses', String(usesN)]);
        if (!changes.length) return toast(t('settings.invite.unchanged'), { kind: 'info' });
        for (const [key, value] of changes) {
          const res = await guarded('settings.change', key + '=' + value, (id) => api.put('/settings', { key, value }, { stepUp: id }));
          if (!res) return undefined;
        }
        toast(t('settings.invite.saved'), { kind: 'success' });
        return renderConfig();
      });
    });
    return form;
  }

  // The four file limits (ADR-215). The hub validates every value again and clamps the combinations; this form explains a
  // mistake before the round trip and warns about a combination the hub will narrow. Each changed key is its own step-up.
  function fileLimitsForm(s) {
    const inputs = {};
    const fields = {};
    for (const l of FILE_LIMITS) {
      const input = textInput({ type: 'text', value: toDisplay(l.key, s[l.field]), className: 'input-num' });
      input.setAttribute('inputmode', 'numeric');
      inputs[l.key] = input;
      fields[l.key] = field({
        label: t('settings.files.' + l.key),
        control: input,
        hint: t('settings.files.' + l.key + '.hint', { min: formatNumber(l.min), max: formatNumber(l.max) }),
      });
    }
    const warnings = h('div', { class: 'stack' });
    const save = button({ label: t('settings.files.save'), variant: 'primary', type: 'submit' });
    const form = h('form', { class: 'stack', novalidate: true },
      h('div', { class: 'inline-fields' }, FILE_LIMITS.map((l) => fields[l.key].el)),
      warnings,
      h('div', { class: 'form-actions start' }, save));

    const read = () => parseForm(Object.fromEntries(FILE_LIMITS.map((l) => [l.key, inputs[l.key].value])));
    const showWarnings = () => {
      const { stored } = read();
      mount(warnings, ...combinationWarnings(stored).map((id) => banner({ tone: 'warn', text: t('settings.files.warn.' + id) })));
    };
    for (const l of FILE_LIMITS) inputs[l.key].addEventListener('input', showWarnings);
    showWarnings();

    form.addEventListener('submit', (ev) => {
      ev.preventDefault();
      const { errors, stored } = read();
      let anyBad = false;
      for (const l of FILE_LIMITS) {
        fields[l.key].clearError();
        if (errors[l.key]) {
          fields[l.key].setError(t('settings.files.error.' + errors[l.key], { min: formatNumber(l.min), max: formatNumber(l.max) }));
          anyBad = true;
        }
      }
      if (anyBad) return undefined;
      return withBusy(save, async () => {
        const changes = changedKeys(stored, s);
        if (!changes.length) return toast(t('settings.files.unchanged'), { kind: 'info' });
        for (const key of changes) {
          const value = String(stored[key]);
          const res = await guarded('settings.change', key + '=' + value, (id) => api.put('/settings', { key, value }, { stepUp: id }));
          if (!res) return undefined;
        }
        toast(t('settings.files.saved'), { kind: 'success' });
        return renderConfig();
      });
    });
    return form;
  }

  // What the hub holds right now: counts and bytes only, never a name or a recipient (ADR-215). It loads on its own so a
  // failure here cannot hide the limits above it.
  function fileUsage() {
    const host = h('div', { class: 'stack' });
    api.get('/files/stats').then(
      (st) => {
        const rows = topUsage(st.devices, 5);
        mount(host,
          h('h4', { text: t('settings.files.usage.title') }),
          dl([
            [t('settings.files.usage.ready'), t('settings.files.usage.line', { n: formatNumber(st.ready), size: formatBytes(st.readyBytes) })],
            [t('settings.files.usage.uploading'), t('settings.files.usage.line', { n: formatNumber(st.uploading), size: formatBytes(st.uploadingBytes) })],
          ]),
          rows.length
            ? h('div', { class: 'stack' },
              h('p', { class: 'muted small', text: t('settings.files.usage.top') }),
              dl(rows.map((r) => [r.deviceId.slice(0, 8), t('settings.files.usage.line', { n: formatNumber(r.files), size: formatBytes(r.bytes) })])))
            : h('p', { class: 'muted small', text: t('settings.files.usage.none') }));
      },
      () => mount(host, h('p', { class: 'muted small', text: t('settings.files.usage.unavailable') })),
    );
    return host;
  }

  // ------------------------------------------------------------------ health
  async function renderHealth() {
    const host = h('div', { class: 'stack' });
    mount(panel, h('div', { class: 'section-actions' }, button({ label: t('common.refresh'), icon: 'refresh', on: { click: () => renderHealth() } })), host);
    await loadInto(host, {
      load: async () => {
        const [o, m] = await Promise.all([api.get('/overview'), api.get('/metrics')]);
        return { o, m };
      },
      render: ({ o, m }) => {
        const checks = [
          [t('settings.health.delegation'), o.delegation.status === 'ACTIVE' ? badge(t('delegation.status.active'), 'ok') : o.delegation.status === 'AWAITING' ? badge(t('delegation.status.awaiting'), 'warn') : badge(t('delegation.status.expired'), 'bad')],
          [t('settings.health.audit'), badge(t('settings.health.audit.entries', { n: formatNumber(o.audit.entries) }), o.audit.entries ? 'ok' : 'neutral')],
          [t('settings.health.revocations'), o.revocations ? (o.revocations.unpublishedChanges ? badge(t('sec.rev.unpublished.yes'), 'warn') : badge(t('settings.health.revocations.ok'), 'ok')) : badge(t('settings.health.revocations.none'), 'neutral')],
          [t('settings.health.queue'), m.queueDepth > 1000 ? badge(t('settings.health.queue.deep', { n: formatNumber(m.queueDepth) }), 'warn') : badge(t('settings.health.queue.ok', { n: formatNumber(m.queueDepth) }), 'ok')],
          [t('settings.health.rate'), m.rateLimitHits ? badge(t('settings.health.rate.hits', { n: formatNumber(m.rateLimitHits) }), 'warn') : badge(t('settings.health.rate.none'), 'ok')],
        ];
        const counters = [
          ['uptime', formatDuration(m.uptimeSeconds * 1000)],
          ['onlineDevices', formatNumber(m.onlineDevices)],
          ['queueDepth', formatNumber(m.queueDepth)],
          ['queueBytes', formatBytes(m.queueBytes)],
          ['messagesDelivered', formatNumber(m.messagesDelivered)],
          ['messagesQueued', formatNumber(m.messagesQueued)],
          ['messagesRejected', formatNumber(m.messagesRejected)],
          ['redeliveries', formatNumber(m.redeliveries)],
          ['framesIn', formatNumber(m.framesIn)],
          ['framesOut', formatNumber(m.framesOut)],
          ['bytesIn', formatBytes(m.bytesIn)],
          ['bytesOut', formatBytes(m.bytesOut)],
          ['handshakesOk', formatNumber(m.handshakesOk)],
          ['handshakesRefused', formatNumber(m.handshakesRefused)],
          ['mailboxDelivered', formatNumber(m.mailboxDelivered)],
          ['mailboxExpired', formatNumber(m.mailboxExpired)],
          ['mailboxDropped', formatNumber(m.mailboxDropped)],
          ['mailboxQuarantined', formatNumber(m.mailboxQuarantined)],
        ];
        return [
          card({ title: t('settings.health.title'), id: 'set-health' }, dl(checks)),
          card({ title: t('settings.counters.title'), subtitle: t('settings.counters.sub'), id: 'set-counters' }, dl(counters.map(([k, v]) => [t('settings.counter.' + k), h('span', { class: 'num', text: v })]), 'dl-wide')),
          h('p', { class: 'muted small', text: t('settings.health.asOf', { time: formatDateTime(m.serverTimeMs) }) }),
        ];
      },
    });
  }

  // ------------------------------------------------------------------ about
  async function renderAbout() {
    const host = h('div', { class: 'stack' });
    mount(panel, host);
    await loadInto(host, {
      load: () => api.get('/overview'),
      render: (o) => [
        card(
          { title: t('settings.about.title'), id: 'set-about' },
          dl([
            [t('settings.about.hub'), o.hubName],
            [t('settings.about.serverId'), h('span', { class: 'inline' }, mono(o.serverId), copyButton({ text: o.serverId, label: t('common.copy') }))],
            [t('settings.about.console'), mono(CONSOLE_VERSION)],
            [t('settings.about.origin'), mono(globalThis.location.origin)],
            [t('settings.about.secure'), globalThis.isSecureContext ? badge(t('settings.about.secure.yes'), 'ok') : badge(t('settings.about.secure.no'), 'warn')],
          ]),
          h('p', { class: 'muted small', text: t('settings.about.offline') }),
        ),
        card(
          { title: t('settings.about.docs'), subtitle: t('settings.about.docs.sub'), id: 'set-docs' },
          h('ul', { class: 'plain-list' }, DOCS.map(([path, key]) => h('li', {}, mono(path), ' ', h('span', { class: 'muted', text: t(key) })))),
        ),
        card({ title: t('settings.about.keys'), id: 'set-keys' }, h('p', { class: 'muted', text: t('settings.about.keys.text') }), button({ label: t('settings.about.keys.open'), icon: 'keyboard', on: { click: () => openHelp() } })),
      ],
    });
  }

  ready = true;
  renderTab();
  return {
    destroy() {},
    onRoute(next) {
      const wanted = TABS.includes(next.query.tab) ? next.query.tab : 'config';
      if (wanted !== current) tabBar.select(wanted);
    },
  };
}
