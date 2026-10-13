// String lookup with {name} parameters and plural forms. A string is either text or {one, other, zero?, few?, many?, two?}.
// Missing keys return the key itself (a test fails the build of the console when a view uses a key that en.js lacks).

import en from './strings/en.js';

let table = en;
let locale = 'en';
let rules = null;

export function setLocale(tag, strings = en) {
  locale = tag;
  table = strings;
  rules = null;
}

export function getLocale() {
  try {
    return globalThis.navigator && globalThis.navigator.language ? globalThis.navigator.language : locale;
  } catch (e) {
    return locale;
  }
}

function lookup(key) {
  const v = table[key];
  return v === undefined ? en[key] : v;
}

function fill(text, params) {
  if (!params) return text;
  return text.replace(/\{([a-zA-Z0-9_]+)\}/g, (m, name) => (params[name] === undefined || params[name] === null ? m : String(params[name])));
}

/** Plain or plural lookup: `t('x')`, `t('x', {name})`, and for plural entries `t('x', {n: 3})` (the `n` parameter selects the form). */
export function t(key, params) {
  const v = lookup(key);
  if (v === undefined) return key;
  if (typeof v === 'string') return fill(v, params);
  const n = params && typeof params.n === 'number' ? params.n : 0;
  if (!rules) {
    try {
      rules = new Intl.PluralRules(locale);
    } catch (e) {
      rules = { select: (x) => (x === 1 ? 'one' : 'other') };
    }
  }
  const form = rules.select(n);
  const text = v[form] !== undefined ? v[form] : v.other;
  return fill(text, { ...params, n: new Intl.NumberFormat(getLocale()).format(n) });
}

export function hasKey(key) {
  return lookup(key) !== undefined;
}
