// Small reusable widgets. Every one builds DOM with h()/svg(); none takes markup. Visible text comes from t() at the call site.

import { h, svg, uid, toggleClass } from '../dom.js';
import { icon } from '../icons.js';
import { t } from '../i18n.js';
import { relativeTime, formatDateTime } from '../format.js';

export function button({ label, icon: iconName, variant = 'secondary', type = 'button', on, disabled = false, title, size, ariaLabel, className = '' } = {}) {
  return h(
    'button',
    {
      class: ['btn', 'btn-' + variant, size ? 'btn-' + size : '', className],
      type,
      disabled,
      title,
      'aria-label': ariaLabel,
      on,
    },
    iconName ? icon(iconName, { size: 18 }) : null,
    label ? h('span', { class: 'btn-label', text: label }) : null,
  );
}

export function iconButton({ icon: iconName, label, on, variant = 'ghost', pressed, disabled = false, className = '' }) {
  return h(
    'button',
    { class: ['icon-btn', 'icon-btn-' + variant, className], type: 'button', 'aria-label': label, title: label, 'aria-pressed': pressed === undefined ? null : String(pressed), disabled, on },
    icon(iconName),
  );
}

/** Runs [fn] with the button disabled and aria-busy; re-enables it afterwards even on error. */
export async function withBusy(btn, fn) {
  if (btn.disabled) return undefined;
  btn.disabled = true;
  btn.setAttribute('aria-busy', 'true');
  btn.classList.add('is-busy');
  try {
    return await fn();
  } finally {
    btn.disabled = false;
    btn.removeAttribute('aria-busy');
    btn.classList.remove('is-busy');
  }
}

const TONE_GLYPH = { ok: 'success', warn: 'warning', bad: 'error', accent: 'info', neutral: null };

/** A status pill. Never colour alone: a glyph and the word. */
export function badge(text, tone = 'neutral', { title } = {}) {
  const glyph = TONE_GLYPH[tone];
  return h('span', { class: 'badge badge-' + tone, title }, glyph ? icon(glyph, { size: 14 }) : null, h('span', { text }));
}

export function card({ title, subtitle, actions, className = '', headingLevel = 2, id } = {}, ...children) {
  const head =
    title || actions
      ? h(
          'div',
          { class: 'card-head' },
          h('div', { class: 'card-titles' }, title ? h('h' + headingLevel, { class: 'card-title', id, text: title }) : null, subtitle ? h('p', { class: 'card-sub', text: subtitle }) : null),
          actions ? h('div', { class: 'card-actions' }, actions) : null,
        )
      : null;
  return h('section', { class: ['card', className], 'aria-labelledby': title && id ? id : null }, head, h('div', { class: 'card-body' }, children));
}

export function stat({ label, value, hint, tone, href }) {
  const body = [h('div', { class: 'stat-label', text: label }), h('div', { class: 'stat-value num', text: value }), hint ? h('div', { class: 'stat-hint', text: hint }) : null];
  return href
    ? h('a', { class: ['stat', tone ? 'stat-' + tone : ''], href }, body)
    : h('div', { class: ['stat', tone ? 'stat-' + tone : ''] }, body);
}

export function pageHeader({ title, subtitle, actions }) {
  return h(
    'div',
    { class: 'page-head' },
    h('div', { class: 'page-titles' }, h('h1', { class: 'page-title', id: 'page-title', tabindex: '-1', text: title }), subtitle ? h('p', { class: 'page-sub', text: subtitle }) : null),
    actions ? h('div', { class: 'page-actions' }, actions) : null,
  );
}

export function emptyState({ title, text, action, glyph = 'inbox' }) {
  return h('div', { class: 'empty' }, h('div', { class: 'empty-icon' }, icon(glyph, { size: 28 })), h('h3', { class: 'empty-title', text: title }), text ? h('p', { class: 'empty-text', text }) : null, action ? h('div', { class: 'empty-action' }, action) : null);
}

/** A failed section: one sentence, Try again, and the reference for support. */
export function errorState(err, retry) {
  const message = err && err.message ? err.message : t('error.generic');
  let hint = '';
  if (err && err.kind === 'network') hint = t('error.network.hint');
  else if (err && err.kind === 'timeout') hint = t('error.timeout.hint');
  else if (err && err.status === 429) hint = err.retryAfter ? t('error.rate.wait', { seconds: err.retryAfter }) : t('error.rate.hint');
  return h(
    'div',
    { class: 'error-state', role: 'alert' },
    h('div', { class: 'empty-icon bad' }, icon('error', { size: 28 })),
    h('h3', { class: 'empty-title', text: t('error.title') }),
    h('p', { class: 'empty-text', text: message }),
    hint ? h('p', { class: 'empty-text muted', text: hint }) : null,
    err && err.correlationId ? h('p', { class: 'ref', text: t('error.reference', { id: err.correlationId }) }) : null,
    retry ? h('div', { class: 'empty-action' }, button({ label: t('common.tryAgain'), icon: 'refresh', on: { click: retry } })) : null,
  );
}

/** A banner. tone: info | ok | warn | bad. */
export function banner({ tone = 'info', title, text, actions, role }) {
  const glyph = { info: 'info', ok: 'success', warn: 'warning', bad: 'error' }[tone];
  return h(
    'div',
    { class: 'banner banner-' + tone, role: role || (tone === 'bad' || tone === 'warn' ? 'alert' : 'status') },
    h('span', { class: 'banner-icon' }, icon(glyph)),
    h('div', { class: 'banner-body' }, title ? h('strong', { class: 'banner-title', text: title }) : null, text ? h('div', { class: 'banner-text', text }) : null),
    actions ? h('div', { class: 'banner-actions' }, actions) : null,
  );
}

/** Skeleton blocks that stand in for content while it loads (no spinner-only screens, no layout shift). */
export function skeleton(kind = 'lines', n = 3) {
  const wrap = h('div', { class: 'skeleton-wrap', 'aria-hidden': 'true' });
  if (kind === 'tiles') {
    wrap.classList.add('skeleton-tiles');
    for (let i = 0; i < n; i += 1) wrap.appendChild(h('div', { class: 'skeleton skeleton-tile' }));
  } else if (kind === 'table') {
    for (let i = 0; i < n; i += 1) wrap.appendChild(h('div', { class: 'skeleton skeleton-row' }));
  } else {
    for (let i = 0; i < n; i += 1) wrap.appendChild(h('div', { class: ['skeleton', 'skeleton-line', i === n - 1 ? 'short' : ''] }));
  }
  return h('div', { class: 'skeleton-host', 'aria-busy': 'true', role: 'status', 'aria-label': t('common.loading') }, wrap);
}

/** A label + control + hint + error wired together for assistive technology. Returns {el, input, setError, clearError}. */
export function field({ label, control, hint, required = false }) {
  const id = control.id || uid('f');
  control.id = id;
  const hintId = hint ? id + '-hint' : null;
  const errId = id + '-err';
  const err = h('div', { class: 'field-error', id: errId, role: 'alert', hidden: true });
  const describedBy = [hintId, errId].filter(Boolean).join(' ');
  control.setAttribute('aria-describedby', describedBy);
  if (required) control.setAttribute('aria-required', 'true');
  const el = h(
    'div',
    { class: 'field' },
    h('label', { class: 'label', for: id }, label, required ? h('span', { class: 'req', 'aria-hidden': 'true', text: ' *' }) : null),
    control,
    hint ? h('div', { class: 'hint', id: hintId, text: hint }) : null,
    err,
  );
  return {
    el,
    input: control,
    setError(message) {
      err.textContent = message;
      err.hidden = false;
      control.setAttribute('aria-invalid', 'true');
    },
    clearError() {
      err.textContent = '';
      err.hidden = true;
      control.removeAttribute('aria-invalid');
    },
  };
}

export function textInput({ type = 'text', value = '', placeholder, autocomplete = 'off', inputmode, maxlength, name, className = '' } = {}) {
  return h('input', { class: ['input', className], type, value, placeholder, autocomplete, inputmode, maxlength, name, spellcheck: 'false' });
}

export function selectInput(options, value = '', { className = '', ariaLabel } = {}) {
  const sel = h(
    'select',
    { class: ['input', 'select', className], 'aria-label': ariaLabel },
    options.map((o) => h('option', { value: o.value, text: o.label })),
  );
  sel.value = value;
  return sel;
}

/** A switch (role=switch checkbox). */
export function switchInput({ label, checked = false, on }) {
  const id = uid('sw');
  const input = h('input', { type: 'checkbox', id, role: 'switch', class: 'switch-input', checked, on });
  return h('label', { class: 'switch', for: id }, input, h('span', { class: 'switch-track', 'aria-hidden': 'true' }, h('span', { class: 'switch-thumb' })), h('span', { class: 'switch-label', text: label }));
}

export async function copyText(text) {
  try {
    if (globalThis.navigator && globalThis.navigator.clipboard && globalThis.isSecureContext) {
      await globalThis.navigator.clipboard.writeText(text);
      return true;
    }
  } catch (e) {
    /* fall through to the legacy path */
  }
  try {
    const doc = globalThis.document;
    const ta = h('textarea', { class: 'sr-only', 'aria-hidden': 'true', tabindex: '-1', readonly: true, text });
    doc.body.appendChild(ta);
    ta.select();
    const ok = doc.execCommand('copy');
    ta.remove();
    return ok;
  } catch (e) {
    return false;
  }
}

/** A copy button that says "Copied" for two seconds (and tells screen readers). */
export function copyButton({ text, label, getText, variant = 'secondary', small = true }) {
  const btn = button({ label: label || t('common.copy'), icon: 'copy', variant, size: small ? 'sm' : undefined });
  const labelEl = btn.querySelector('.btn-label');
  btn.addEventListener('click', async () => {
    const ok = await copyText(getText ? getText() : text);
    const original = label || t('common.copy');
    labelEl.textContent = ok ? t('common.copied') : t('common.copyFailed');
    btn.setAttribute('aria-live', 'polite');
    setTimeout(() => {
      labelEl.textContent = original;
    }, 2000);
  });
  return btn;
}

/** A <time> with a relative text and the absolute time as tooltip. */
export function timeEl(ms, { now = Date.now(), empty = '–' } = {}) {
  if (ms === null || ms === undefined) return h('span', { class: 'muted', text: empty });
  return h('time', { datetime: new Date(ms).toISOString(), title: formatDateTime(ms), text: relativeTime(ms, now) });
}

/** A monospace identifier that can be selected whole by one click. */
export function mono(text, className = '') {
  return h('code', { class: ['mono', className], text });
}

/** An inline sparkline from numeric [values]. */
export function sparkline(values, { width = 160, height = 40, label } = {}) {
  const root = svg('svg', { viewBox: '0 0 ' + width + ' ' + height, class: 'spark', role: 'img', 'aria-label': label || t('common.chart'), focusable: 'false' });
  if (values.length < 2) {
    root.appendChild(svg('polyline', { points: '0,' + (height - 1) + ' ' + width + ',' + (height - 1), class: 'spark-base', fill: 'none' }));
    return root;
  }
  const max = Math.max(...values, 1);
  const step = width / (values.length - 1);
  const pts = values.map((v, i) => (i * step).toFixed(1) + ',' + (height - 2 - (v / max) * (height - 6)).toFixed(1));
  root.appendChild(svg('polyline', { points: pts.join(' '), class: 'spark-line', fill: 'none' }));
  root.appendChild(svg('polygon', { points: '0,' + height + ' ' + pts.join(' ') + ' ' + width + ',' + height, class: 'spark-area' }));
  return root;
}

/** A horizontal segmented bar: parts = [{label, value, tone}]. Widths are set as custom properties (CSSOM). */
export function segmentedBar(parts, total) {
  const sum = total ?? parts.reduce((a, p) => a + p.value, 0);
  const bar = h('div', { class: 'segbar', role: 'img', 'aria-label': parts.map((p) => p.label + ' ' + p.value).join(', ') });
  for (const p of parts) {
    if (p.value <= 0 || sum <= 0) continue;
    const seg = h('span', { class: 'seg seg-' + p.tone, title: p.label + ': ' + p.value });
    seg.style.setProperty('--w', ((p.value / sum) * 100).toFixed(3) + '%');
    bar.appendChild(seg);
  }
  if (!bar.firstChild) bar.appendChild(h('span', { class: 'seg seg-none' }));
  return bar;
}

/** Tabs with roving tabindex and arrow-key navigation. The caller renders panels. */
export function tabs({ items, selected, onSelect, label }) {
  const id = uid('tabs');
  const list = h('div', { class: 'tabs', role: 'tablist', 'aria-label': label });
  const buttons = new Map();
  function select(tabId, { focus = false } = {}) {
    for (const [k, b] of buttons) {
      const on = k === tabId;
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
      toggleClass(b, 'is-selected', on);
    }
    if (focus) buttons.get(tabId).focus();
    onSelect(tabId);
  }
  for (const item of items) {
    const b = h(
      'button',
      { class: 'tab', type: 'button', role: 'tab', id: id + '-' + item.id, 'aria-controls': id + '-panel', 'aria-selected': 'false', tabindex: '-1', on: { click: () => select(item.id) } },
      h('span', { text: item.label }),
      item.badge ? h('span', { class: 'tab-badge num', text: String(item.badge) }) : null,
    );
    buttons.set(item.id, b);
    list.appendChild(b);
  }
  list.addEventListener('keydown', (ev) => {
    const ids = items.map((i) => i.id);
    const cur = ids.findIndex((k) => buttons.get(k).getAttribute('aria-selected') === 'true');
    let next = -1;
    if (ev.key === 'ArrowRight') next = (cur + 1) % ids.length;
    else if (ev.key === 'ArrowLeft') next = (cur - 1 + ids.length) % ids.length;
    else if (ev.key === 'Home') next = 0;
    else if (ev.key === 'End') next = ids.length - 1;
    if (next >= 0) {
      ev.preventDefault();
      select(ids[next], { focus: true });
    }
  });
  select(selected ?? items[0].id);
  const panel = h('div', { class: 'tabpanel', role: 'tabpanel', id: id + '-panel', tabindex: '0' });
  return {
    el: list,
    panel,
    select,
    setBadge(tabId, value) {
      const b = buttons.get(tabId);
      let el = b.querySelector('.tab-badge');
      if (!value) return el && el.remove();
      if (!el) {
        el = h('span', { class: 'tab-badge num' });
        b.appendChild(el);
      }
      el.textContent = String(value);
      return undefined;
    },
  };
}

/** A definition list from [[term, node-or-text], ...]. */
export function dl(rows, className = '') {
  const el = h('dl', { class: ['dl', className] });
  for (const [term, value] of rows) {
    if (value === null || value === undefined) continue;
    el.appendChild(h('dt', { text: term }));
    el.appendChild(h('dd', {}, value));
  }
  return el;
}

/** A keyboard key chip. */
export function kbd(text) {
  return h('kbd', { class: 'kbd', text });
}

/** A code block with a copy button. */
export function codeBlock(text, { label } = {}) {
  return h(
    'div',
    { class: 'codeblock' },
    h('pre', { class: 'code', tabindex: '0', 'aria-label': label || null }, h('code', { text })),
    h('div', { class: 'codeblock-actions' }, copyButton({ text })),
  );
}
