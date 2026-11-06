/**
 * Screenshot harness. `npm run dev` (or preview) must be serving :5174 / :4174.
 * URL params pin a state: ?theme=dark&wallpaper=pulse&view=desk&motion=reduced
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = process.env.BASE ?? 'http://localhost:5174'
mkdirSync('shots', { recursive: true })

const PAGES = [
  { name: 'page-light', url: '/', width: 1440, height: 1000 },
  { name: 'page-dark', url: '/?theme=dark&wallpaper=pulse', width: 1440, height: 1000 },
  { name: 'page-tablet', url: '/', width: 768, height: 1100 },
  { name: 'page-phone', url: '/', width: 390, height: 844 },
  { name: 'page-desktop', url: '/?view=desk', width: 1440, height: 1000 },
  { name: 'page-draft', url: '/?wallpaper=draft', width: 1440, height: 1000 },
]

const PHONES = [
  'conversation', 'menu', 'profile',
  'group', 'group-info', 'group-settings',
  'invite', 'group-sent',
]

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2 })
const errors = []
page.on('pageerror', e => errors.push('pageerror: ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

for (const s of PAGES) {
  await page.setViewportSize({ width: s.width, height: s.height })
  await page.goto(BASE + s.url, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  await page.screenshot({ path: `shots/${s.name}.png` })
}

await page.setViewportSize({ width: 1440, height: 1000 })
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.waitForTimeout(400)
const phones = page.locator('.shot .f-phone')
for (let i = 0; i < PHONES.length; i++) {
  await phones.nth(i).scrollIntoViewIfNeeded()
  await page.waitForTimeout(150)
  await phones.nth(i).screenshot({ path: `shots/only-${PHONES[i]}.png` })
}
await page.locator('.sw-board').screenshot({ path: 'shots/only-swarm-board.png' })

// chat appearance, reached the way a person reaches it: menu -> Chat appearance
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.waitForTimeout(300)
await phones.nth(0).locator('button[aria-label="Conversation menu"]').click()
await page.getByRole('menuitem', { name: 'Chat appearance' }).first().click()
await page.waitForTimeout(250)
await phones.nth(0).screenshot({ path: 'shots/only-appearance.png' })

await page.goto(BASE + '/?view=desk', { waitUntil: 'networkidle' })
await page.waitForTimeout(300)
await page.locator('.f-window').screenshot({ path: 'shots/only-desktop.png' })

console.log('done -> shots/')
if (errors.length) {
  console.log('ERRORS:')
  for (const e of errors) console.log('  ' + e)
}
await browser.close()
