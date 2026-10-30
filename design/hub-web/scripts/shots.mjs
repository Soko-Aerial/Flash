/**
 * Screenshot harness. `npm run preview` must be serving :4173.
 * Screenshots land in shots/ (git-ignored). URL params pin the state:
 *   ?state=offline&theme=light&view=phone&segment=people&open=p3
 */
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = 'http://localhost:4173'
mkdirSync('shots', { recursive: true })

const SHOTS = [
  { name: 'page-fresh', url: '?view=both' },
  { name: 'page-fresh-light', url: '?view=both&theme=light' },
  { name: 'page-offline', url: '?view=both&state=offline' },
  { name: 'page-never', url: '?view=both&state=never' },
  { name: 'page-revoked', url: '?view=both&state=revoked' },
  { name: 'page-unenrolled', url: '?view=both&enrolled=0' },
  { name: 'page-person', url: '?view=both&open=p1' },
  { name: 'page-reader', url: '?view=both&open=a1' },
  { name: 'page-people-segment', url: '?view=both&segment=people' },
  { name: 'page-groups-segment', url: '?view=both&segment=groups' },
  { name: 'phone-chats', url: '?view=phone', click: 'text=Chats' },
  { name: 'phone-light', url: '?view=phone&theme=light' },
]

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1960, height: 1000 }, deviceScaleFactor: 2 })
const errors = []
page.on('pageerror', e => errors.push('pageerror: ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()) })

for (const s of SHOTS) {
  const phone = s.name.startsWith('phone-')
  await page.setViewportSize(phone ? { width: 480, height: 1000 } : { width: 1960, height: 1000 })
  await page.goto(BASE + s.url, { waitUntil: 'networkidle' })
  if (s.click) await page.click(s.click)
  await page.waitForTimeout(350)
  await page.screenshot({ path: `shots/${s.name}.png` })
}
console.log('done')
if (errors.length) {
  console.log('ERRORS:')
  for (const e of errors) console.log('  ' + e)
}
await browser.close()
