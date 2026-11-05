/**
 * Screenshot harness. `npm run dev` (or preview) must be serving :5174 / :4174.
 * URL params pin a state:
 *   ?theme=dark&wallpaper=pulse&view=desk&sheet=1&motion=reduced
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = process.env.BASE ?? 'http://localhost:5174'
mkdirSync('shots', { recursive: true })

const SHOTS = [
  { name: 'page-light', url: '/?view=all', width: 1440, height: 1000 },
  { name: 'page-dark', url: '/?theme=dark&view=all', width: 1440, height: 1000 },
  { name: 'page-desktop', url: '/?view=desk', width: 1440, height: 1000, scroll: 1500 },
  { name: 'page-tablet', url: '/?view=all', width: 768, height: 1100 },
  { name: 'page-phone', url: '/?view=phone', width: 390, height: 844 },
  { name: 'phone-dark-pulse', url: '/?theme=dark&wallpaper=pulse&view=phone', width: 390, height: 844 },
  { name: 'phone-sheet', url: '/?wallpaper=signal&view=phone&sheet=1', width: 390, height: 844 },
  { name: 'phone-draft', url: '/?wallpaper=draft&view=phone', width: 390, height: 844 },
]

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 2 })
const errors = []
page.on('pageerror', e => errors.push('pageerror: ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

for (const s of SHOTS) {
  await page.setViewportSize({ width: s.width, height: s.height })
  await page.goto(BASE + s.url, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  if (s.scroll) await page.evaluate(y => window.scrollTo(0, y), s.scroll)
  await page.waitForTimeout(250)
  await page.screenshot({ path: `shots/${s.name}.png`, fullPage: !s.scroll ? false : false })
}

// close-ups of the app surfaces, without review chrome
await page.setViewportSize({ width: 1440, height: 1000 })
await page.goto(BASE + '/?view=all', { waitUntil: 'networkidle' })
await page.waitForTimeout(300)
const frames = page.locator('.f-phone')
await frames.nth(0).screenshot({ path: 'shots/only-conversation.png' })
await frames.nth(1).screenshot({ path: 'shots/only-appearance.png' })
await page.goto(BASE + '/?view=all&sheet=1', { waitUntil: 'networkidle' })
await page.waitForTimeout(300)
await page.locator('.f-phone').nth(0).screenshot({ path: 'shots/only-sheet.png' })
await page.goto(BASE + '/?view=desk', { waitUntil: 'networkidle' })
await page.waitForTimeout(300)
await page.locator('.f-window').screenshot({ path: 'shots/only-desktop.png' })

console.log('done â†’ shots/')
if (errors.length) {
  console.log('ERRORS:')
  for (const e of errors) console.log('  ' + e)
}
await browser.close()
