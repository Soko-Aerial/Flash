// Safe DOM construction. Every string that came from the server is untrusted: it only ever reaches the page as a text node or as a
// value from an allow-list of attributes. There is no HTML-string insertion, no inline style attribute and no inline event handler anywhere.

const SVG_NS = 'http://www.w3.org/2000/svg';

// Attributes a view may set. `style` is deliberately absent (use classes, `setVars`, or `css`); `on*` strings are refused (use `on`).
const ATTR_ALLOW = new RegExp(
  '^(?:aria-[a-z]+|data-[a-z0-9-]+|role|id|type|name|value|placeholder|href|for|title|tabindex|disabled|checked|readonly|required|' +
    'autocomplete|inputmode|min|max|step|maxlength|minlength|rows|cols|colspan|rowspan|scope|rel|download|hidden|open|selected|' +
    'datetime|novalidate|multiple|accept|spellcheck|autofocus|lang|dir|pattern|list|alt|width|height|viewBox|d|fill|stroke|stroke-width|' +
    'stroke-linecap|stroke-linejoin|x|y|cx|cy|r|rx|ry|points|transform|opacity|focusable|autocapitalize|enterkeyhint|inert|translate)$',
);

/** True for a link target the console may emit: an in-page anchor, a same-origin absolute path, or a blob URL it made itself. */
export function isSafeHref(value) {
  const v = String(value);
  if (v.startsWith('#')) return true;
  if (v.startsWith('/') && !v.startsWith('//') && !v.includes('\\')) return true;
  return v.startsWith('blob:');
}

function doc() {
  return globalThis.document;
}

function applyProps(el, props, isSvg) {
  if (!props) return;
  for (const [key, value] of Object.entries(props)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') {
      const cls = Array.isArray(value) ? value.filter(Boolean).join(' ') : String(value);
      if (isSvg) el.setAttribute('class', cls);
      else el.className = cls;
    } else if (key === 'text') {
      el.textContent = String(value);
    } else if (key === 'on') {
      for (const [type, fn] of Object.entries(value)) {
        if (typeof fn !== 'function') throw new Error('Event handler must be a function: ' + type);
        el.addEventListener(type, fn);
      }
    } else if (key === 'vars') {
      setVars(el, value);
    } else if (key === 'ref') {
      value(el);
    } else if (key === 'style' || /^on/i.test(key)) {
      throw new Error('Attribute not allowed: ' + key);
    } else if (!ATTR_ALLOW.test(key)) {
      throw new Error('Attribute not on the allow-list: ' + key);
    } else {
      if (key === 'href' && !isSafeHref(value)) throw new Error('Unsafe href');
      el.setAttribute(key, value === true ? '' : String(value));
    }
  }
}

/** Sets CSS custom properties through the CSSOM (allowed by the CSP, unlike a style attribute). Values are numbers or strings. */
export function setVars(el, vars) {
  for (const [name, value] of Object.entries(vars)) {
    el.style.setProperty(name.startsWith('--') ? name : '--' + name, String(value));
  }
}

function appendChildren(el, children) {
  for (const child of children) {
    if (child === null || child === undefined || child === false || child === true) continue;
    if (Array.isArray(child)) appendChildren(el, child);
    else if (typeof child === 'string' || typeof child === 'number' || typeof child === 'bigint') el.appendChild(doc().createTextNode(String(child)));
    else el.appendChild(child);
  }
}

/** Creates an HTML element. `h('p', {class: 'x'}, 'text', child)`. Strings become text nodes, never markup. */
export function h(tag, props, ...children) {
  const el = doc().createElement(tag);
  applyProps(el, props, false);
  appendChildren(el, children);
  return el;
}

/** Creates an SVG element in the SVG namespace. */
export function svg(tag, props, ...children) {
  const el = doc().createElementNS(SVG_NS, tag);
  applyProps(el, props, true);
  appendChildren(el, children);
  return el;
}

/** Removes every child of [el] and appends [children]. */
export function mount(el, ...children) {
  while (el.firstChild) el.removeChild(el.firstChild);
  appendChildren(el, children);
  return el;
}

/** A document fragment holding [children]. */
export function frag(...children) {
  const f = doc().createDocumentFragment();
  appendChildren(f, children);
  return f;
}

/** Toggles a class and returns the element, for chaining. */
export function toggleClass(el, name, on) {
  if (on) el.classList.add(name);
  else el.classList.remove(name);
  return el;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** The elements inside [root] that Tab can reach, in document order. */
export function focusables(root) {
  return Array.from(root.querySelectorAll(FOCUSABLE)).filter((el) => !el.hasAttribute('hidden') && !el.closest('[hidden]'));
}

/** A DOM id that is unique for the page, for wiring labels and descriptions. */
let idCounter = 0;
export function uid(prefix = 'id') {
  idCounter += 1;
  return prefix + '-' + idCounter;
}
