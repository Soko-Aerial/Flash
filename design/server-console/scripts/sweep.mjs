import { chromium } from 'playwright';

const base = 'http://localhost:5182';
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const routes = [
  'overview', 'devices', 'invites', 'requests', 'organization', 'organization?tab=groups',
  'announcements', 'audit', 'security', 'security?tab=sessions', 'security?tab=delegation',
  'security?tab=revocations', 'security?tab=trust', 'settings', 'settings?tab=files',
  'settings?tab=health', 'settings?tab=about', 'login',
];
const widths = [[1440, 900], [768, 1024], [390, 844]];
const errors = [];
let pages = 0;

function parseColor(value) {
  const m = value.match(/rgba?\(([^)]+)\)/);
  if (!m) return null;
  const parts = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
  return [parts[0], parts[1], parts[2], parts[3] ?? 1];
}

function lum([r, g, b]) {
  const c = [r, g, b].map((v) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

function ratio(a, b) {
  const l1 = lum(a), l2 = lum(b);
  return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
}

for (const [width, height] of widths) {
  for (const route of routes) {
    pages += 1;
    const page = await browser.newPage({ viewport: { width, height }, colorScheme: 'dark' });
    page.on('pageerror', (error) => errors.push(`${width} ${route} pageerror: ${error.message}`));
    page.on('console', (msg) => { if (msg.type() === 'error' || msg.type() === 'assert') errors.push(`${width} ${route} ${msg.type()}: ${msg.text()}`); });
    await page.goto(`${base}/#/${route}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(250);
    const result = await page.evaluate(() => {
      const doc = document.documentElement;
      const vw = doc.clientWidth;
      const overflow = doc.scrollWidth - vw;
      const outside = [];
      if (overflow > 0) {
        for (const el of document.querySelectorAll('body *')) {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0 || r.right <= vw + 1) continue;
          let parent = el.parentElement, scroller = false;
          while (parent) {
            if (/(auto|scroll)/.test(getComputedStyle(parent).overflowX)) { scroller = true; break; }
            parent = parent.parentElement;
          }
          if (!scroller) outside.push(`${el.tagName}.${String(el.className).split(' ')[0]} right=${Math.round(r.right)}`);
        }
      }
      const unnamed = [...document.querySelectorAll('button, a[href]')].filter((el) => {
        if (el.getClientRects().length === 0) return false;
        const name = el.getAttribute('aria-label') || el.textContent.trim() || el.getAttribute('title');
        return !name;
      }).map((el) => `${el.tagName}.${String(el.className).split(' ')[0]}`);
      return { overflow, outside: outside.slice(0, 4), unnamed: unnamed.slice(0, 6) };
    });
    if (result.overflow > 0 || result.outside.length || result.unnamed.length) {
      console.log(`${width} ${route}`, result);
    }
    await page.close();
  }
}

// Contrast + touch audit at the three widths on the strongest screens.
const auditRoutes = ['overview', 'devices', 'invites', 'security?tab=delegation', 'security?tab=trust', 'settings?tab=access'];
for (const [width, height] of widths) {
  for (const route of auditRoutes) {
    const page = await browser.newPage({ viewport: { width, height }, colorScheme: 'dark' });
    await page.goto(`${base}/#/${route}`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(220);
    const issues = await page.evaluate(() => {
      const parse = (value) => {
        const m = value.match(/rgba?\(([^)]+)\)/);
        if (!m) return null;
        const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
        return [p[0], p[1], p[2], p[3] ?? 1];
      };
      const lum = ([r, g, b]) => {
        const c = [r, g, b].map((v) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
      };
      const ratio = (a, b) => {
        const l1 = lum(a), l2 = lum(b);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      };
      const backgrounds = (el) => {
        const layers = [];
        let node = el;
        while (node && node !== document.documentElement) {
          const bg = parse(getComputedStyle(node).backgroundColor);
          if (bg && bg[3] > 0) layers.push(bg);
          if (bg && bg[3] >= 0.99) break;
          node = node.parentElement;
        }
        let base = [16, 18, 24];
        for (let i = layers.length - 1; i >= 0; i -= 1) {
          const [r, g, b, a] = layers[i];
          base = [r * a + base[0] * (1 - a), g * a + base[1] * (1 - a), b * a + base[2] * (1 - a)];
        }
        return base;
      };
      const failed = [];
      const touch = [];
      const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      let node;
      while ((node = walker.nextNode())) {
        const text = node.textContent.trim();
        if (!text) continue;
        const parent = node.parentElement;
        if (!parent || parent.closest('.sr-only, [hidden], dialog:not([open])')) continue;
        const style = getComputedStyle(parent);
        if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) continue;
        const fg = parse(style.color);
        if (!fg) continue;
        const bg = backgrounds(parent);
        const cr = ratio(fg, bg);
        const size = parseFloat(style.fontSize);
        const bold = Number(style.fontWeight) >= 600;
        const large = size >= 24 || (size >= 18.66 && bold);
        if (cr < (large ? 3 : 4.5)) failed.push({ text: text.slice(0, 28), cr: Math.round(cr * 100) / 100, size, cls: String(parent.className).slice(0, 24) });
      }
      for (const el of document.querySelectorAll('button, a[href], input, select')) {
        if (el.getClientRects().length === 0) continue;
        if (el.closest('.bottom-nav, .sidebar, .skip-link')) continue;
        const r = el.getBoundingClientRect();
        if (r.height < 30 && r.width < 44) touch.push(`${el.tagName}.${String(el.className).split(' ')[0]} ${Math.round(r.width)}x${Math.round(r.height)}`);
      }
      return { failed: failed.slice(0, 12), touch: touch.slice(0, 8) };
    });
    if (issues.failed.length || issues.touch.length) console.log(`${width} ${route}`, issues);
    await page.close();
  }
}

console.log(`pages=${pages}`, errors.length ? `ERRORS\n${errors.join('\n')}` : 'No console or page errors.');
await browser.close();
