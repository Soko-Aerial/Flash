import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';

const base = 'http://localhost:5182';
const out = new URL('../shots/final/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
await mkdir(out, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const errors = [];

async function shot(name, route, viewport, { light = false, full = false, act = null } = {}) {
  const page = await browser.newPage({ viewport, colorScheme: 'dark' });
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'assert') errors.push(`${name}: ${m.text()}`); });
  await page.goto(`${base}/#/${route}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(260);
  if (light) await page.evaluate(() => { document.documentElement.dataset.theme = 'light'; });
  if (act) await act(page);
  await page.waitForTimeout(220);
  await page.screenshot({ path: `${out}${name}.png`, fullPage: full });
  console.log(name);
  await page.close();
}

const d = { width: 1440, height: 900 };
const t = { width: 768, height: 1024 };
const p = { width: 390, height: 844 };

await shot('overview-desktop-dark', 'overview', d);
await shot('overview-desktop-light', 'overview', d, { light: true });
await shot('overview-tablet-dark', 'overview', t);
await shot('overview-phone-dark', 'overview', p);
await shot('overview-phone-light', 'overview', p, { light: true });
await shot('devices-desktop-dark', 'devices', d);
await shot('devices-phone-dark', 'devices', p);
await shot('invites-desktop-dark', 'invites', d);
await shot('invites-phone-dark', 'invites', p);
await shot('requests-desktop-dark', 'requests', d);
await shot('requests-phone-dark', 'requests', p);
await shot('organization-groups-1440', 'organization?tab=groups', d);
await shot('announcements-desktop-dark', 'announcements', d);
await shot('announcements-phone-dark', 'announcements', p);
await shot('audit-desktop-dark', 'audit', d);
await shot('security-delegation-1440', 'security?tab=delegation', d);
await shot('security-delegation-390', 'security?tab=delegation', p);
await shot('security-trust-1440', 'security?tab=trust', d);
await shot('settings-desktop-dark', 'settings', d);
await shot('settings-files-1440', 'settings?tab=files', d);
await shot('settings-phone-dark', 'settings', p);
await shot('login-1440', 'login', d);
await shot('login-phone', 'login', p);
await shot('device-drawer-1440', 'devices', d, { act: async (page) => { await page.locator('table tbody tr').first().click(); } });
await shot('device-drawer-390', 'devices', p, { act: async (page) => { await page.locator('table tbody tr').first().click(); } });
await shot('approve-dialog-390', 'requests', p, { act: async (page) => { await page.locator('.request-actions .btn-secondary').first().click(); } });
await shot('invite-created-390', 'invites', p, { act: async (page) => {
  await page.getByRole('button', { name: 'New invite' }).click();
  const dialog = page.locator('dialog[open]');
  await dialog.getByLabel('Name').fill('Mara Vogel');
  await dialog.getByRole('button', { name: 'Create invite', exact: true }).click();
} });
await shot('more-sheet-390', 'security', p, { act: async (page) => { await page.getByRole('button', { name: 'More' }).click(); } });

console.log(errors.length ? `ERRORS\n${errors.join('\n')}` : 'No console or page errors.');
await browser.close();
