// The page-state helper: skeleton while loading, content when ready, a plain error with Try again when it fails. A newer call on the same
// container supersedes an older one (a slow answer never overwrites a fresher one).

import { mount } from '../dom.js';
import { skeleton, errorState } from './widgets.js';

const tokens = new WeakMap();

/**
 * @param {Element} container
 * @param {{load: () => Promise<any>, render: (data:any) => Node|Node[]|null, skeleton?: 'lines'|'tiles'|'table', rows?: number, quiet?: boolean}} o
 * `quiet`: keep the current content while reloading (no skeleton), used for refreshes.
 */
export async function loadInto(container, { load, render, skeleton: kind = 'lines', rows = 3, quiet = false }) {
  const token = (tokens.get(container) || 0) + 1;
  tokens.set(container, token);
  if (!quiet || !container.firstChild) mount(container, skeleton(kind, rows));
  container.setAttribute('aria-busy', 'true');
  try {
    const data = await load();
    if (tokens.get(container) !== token) return undefined;
    mount(container, render(data));
    container.removeAttribute('aria-busy');
    return data;
  } catch (err) {
    if (tokens.get(container) !== token) return undefined;
    if (err && err.kind === 'aborted') return undefined;
    mount(container, errorState(err, () => loadInto(container, { load, render, skeleton: kind, rows, quiet })));
    container.removeAttribute('aria-busy');
    return undefined;
  }
}
