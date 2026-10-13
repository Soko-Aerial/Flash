// A very small DOM, enough for dom.js and qr.js, so the view helpers run in node without a browser or any dependency.

class FakeNode {
  constructor() {
    this.childNodes = [];
    this.parentNode = null;
  }
  get firstChild() {
    return this.childNodes[0] ?? null;
  }
  appendChild(child) {
    if (child.parentNode) child.parentNode.removeChild(child);
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  }
  removeChild(child) {
    const i = this.childNodes.indexOf(child);
    if (i >= 0) this.childNodes.splice(i, 1);
    child.parentNode = null;
    return child;
  }
}

class FakeText extends FakeNode {
  constructor(data) {
    super();
    this.nodeType = 3;
    this.data = data;
  }
  get textContent() {
    return this.data;
  }
}

class FakeElement extends FakeNode {
  constructor(tag, ns) {
    super();
    this.nodeType = 1;
    this.tagName = tag;
    this.namespaceURI = ns ?? 'http://www.w3.org/1999/xhtml';
    this.attributes = new Map();
    this.listeners = new Map();
    this.className = '';
    const props = new Map();
    this.style = { setProperty: (k, v) => props.set(k, v), props };
  }
  setAttribute(k, v) {
    this.attributes.set(k, String(v));
  }
  getAttribute(k) {
    return this.attributes.get(k) ?? null;
  }
  addEventListener(type, fn) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  set textContent(value) {
    this.childNodes = [new FakeText(String(value))];
  }
  get textContent() {
    return this.childNodes.map((c) => c.textContent).join('');
  }
}

export function installFakeDocument() {
  globalThis.document = {
    createElement: (tag) => new FakeElement(tag),
    createElementNS: (ns, tag) => new FakeElement(tag, ns),
    createTextNode: (data) => new FakeText(data),
    createDocumentFragment: () => new FakeElement('#fragment'),
  };
}

/** Every element below [root] (depth first), root included. */
export function walk(root) {
  const out = [root];
  for (const c of root.childNodes) if (c.nodeType === 1) out.push(...walk(c));
  return out;
}
