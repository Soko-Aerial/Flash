// Light / dark / system theme. Only the choice is stored (localStorage, guarded): never anything else.

const KEY = 'flash.hub.theme';

export function getTheme() {
  try {
    const v = globalThis.localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch (e) {
    return 'system';
  }
}

export function setTheme(mode) {
  const root = globalThis.document.documentElement;
  if (mode === 'light' || mode === 'dark') root.setAttribute('data-theme', mode);
  else root.removeAttribute('data-theme');
  try {
    if (mode === 'light' || mode === 'dark') globalThis.localStorage.setItem(KEY, mode);
    else globalThis.localStorage.removeItem(KEY);
  } catch (e) {
    /* storage blocked: the choice lasts until the page closes */
  }
}

export function cycleTheme() {
  const next = { system: 'light', light: 'dark', dark: 'system' }[getTheme()];
  setTheme(next);
  return next;
}
