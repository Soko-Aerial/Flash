// Tiny safe DOM builder. No innerHTML, no eval, no inline handlers.

export function h(tag, attrs = null, ...children) {
  const node = document.createElement(tag);
  apply(node, attrs);
  append(node, children);
  return node;
}

export function apply(node, attrs) {
  if (!attrs) return;
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = String(value);
    else if (key === 'on') {
      for (const [event, handler] of Object.entries(value || {})) {
        if (typeof handler === 'function') node.addEventListener(event, handler);
      }
    } else if (key === 'dataset') {
      Object.assign(node.dataset, value);
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(node.style, value);
    } else if (key === 'value' && 'value' in node) {
      node.value = value;
    } else if (key === 'checked' && 'checked' in node) {
      node.checked = Boolean(value);
    } else if (value === true) {
      node.setAttribute(key, '');
    } else {
      node.setAttribute(key, String(value));
    }
  }
}

export function append(node, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

export function mount(parent, ...children) {
  parent.replaceChildren();
  append(parent, children);
  return parent;
}

export function clear(node) {
  node.replaceChildren();
}

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.append(area);
    area.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { ok = false; }
    area.remove();
    return ok;
  }
}
