/**
 * Flash icon set — the chat half, redrawn.
 *
 * The shipped set (ui/theme/.../FlashIcons.kt + flash_ic_*.xml) is already custom:
 * a 24dp grid at a 2dp stroke. This revision keeps the grid and the names — so the
 * Compose port only replaces path data — and changes the drawing:
 *
 *   stroke   2dp → 1.75dp, so a 24dp glyph sits optically with 16/22 text instead of
 *            over it; at 20dp inside the composer the old set read heavy.
 *   joins    linejoin round, caps round, but every glyph now has one square or
 *            asymmetric detail so it is recognisably Flash, not a Lucide default.
 *   fills    only the play triangle and the "more" dots are filled; the rest is line.
 *
 * Every name here already exists in FlashIcons, except `chevronRight` which maps to
 * the existing flash_ic_chevron_down rotated, and `file` which reuses flash_ic_upload.
 */
import type { JSX } from 'react'

export type IconName =
  | 'send' | 'attach' | 'mic' | 'camera' | 'call' | 'video' | 'more' | 'back'
  | 'close' | 'lock' | 'reply' | 'heart' | 'thumb' | 'play' | 'pause' | 'clock'
  | 'check' | 'read' | 'failed' | 'download' | 'search' | 'pin' | 'bolt'
  | 'wifi' | 'chat' | 'hub' | 'nearby' | 'settings' | 'transfer' | 'chevron'
  | 'file' | 'plus'

type Stroke = string[]

const STROKE: Record<string, Stroke> = {
  /* A dart, not the Material paper plane: the tip is cut square and the fold stops
     short of the nose, which is what makes it read at 20px. */
  send: ['M20.6 3.6 L3.6 10.4 L10.7 13.7 L14 20.8 Z', 'M10.7 13.7 L20.6 3.6'],

  /* One continuous clip with the outer loop squared at the top. */
  attach: [
    'M20.4 11.6 L11.8 20.2 a5 5 0 0 1 -7.1 -7.1 L13.5 4.3 a3.6 3.6 0 0 1 5.1 5.1 L10 18 a2.2 2.2 0 0 1 -3.1 -3.1 L14.6 7.2',
  ],

  /* Tapered capsule, plain stem, no crossbar tick. */
  mic: [
    'M12 3.4 a2.7 2.7 0 0 1 2.7 2.7 v5 a2.7 2.7 0 0 1 -5.4 0 v-5 A2.7 2.7 0 0 1 12 3.4 Z',
    'M6.7 10.6 v.9 a5.3 5.3 0 0 0 10.6 0 v-.9',
    'M12 17 v3.4 M9.1 20.4 h5.8',
  ],

  /* Body with a flat top deck; the lens carries an aperture glint. */
  camera: [
    'M3.9 8.5 h3 L8.5 6.2 h7 L17.1 8.5 h3 a1.1 1.1 0 0 1 1.1 1.1 v8.6 a1.1 1.1 0 0 1 -1.1 1.1 H3.9 a1.1 1.1 0 0 1 -1.1 -1.1 V9.6 a1.1 1.1 0 0 1 1.1 -1.1 Z',
    'M12 13.2 m-3.4 0 a3.4 3.4 0 1 0 6.8 0 a3.4 3.4 0 1 0 -6.8 0',
    'M10.3 12.1 a2.1 2.1 0 0 1 1.5 -1.2',
  ],

  /* Handset with a squared mouthpiece. */
  call: [
    'M6.6 3.4 h2.6 a1.5 1.5 0 0 1 1.5 1.3 c.1 .9 .3 1.8 .6 2.6 a1.5 1.5 0 0 1 -.4 1.6 L9.5 10.3 a13.4 13.4 0 0 0 4.2 4.2 l1.4 -1.4 a1.5 1.5 0 0 1 1.6 -.4 c.8 .3 1.7 .5 2.6 .6 a1.5 1.5 0 0 1 1.3 1.5 v2.6 a1.7 1.7 0 0 1 -1.9 1.7 A17.4 17.4 0 0 1 4.9 5.3 a1.7 1.7 0 0 1 1.7 -1.9 Z',
  ],

  /* The lens is its own shape overlapping the body — the current glyph welds them. */
  video: [
    'M3.9 6.2 h9.4 a2.2 2.2 0 0 1 2.2 2.2 v7.2 a2.2 2.2 0 0 1 -2.2 2.2 H3.9 a2.2 2.2 0 0 1 -2.2 -2.2 V8.4 a2.2 2.2 0 0 1 2.2 -2.2 Z',
    'M15.5 10.4 l5.1 -3.2 v9.6 l-5.1 -3.2',
  ],

  back: ['M20 12 H4.4 M10.8 5 L3.8 12 L10.8 19'],
  close: ['M18.4 5.6 L5.6 18.4 M5.6 5.6 L18.4 18.4'],
  reply: ['M9 5 L3.6 10.4 L9 15.8', 'M3.6 10.4 h7.9 a6.5 6.5 0 0 1 6.5 6.5 v1.7'],
  plus: ['M12 5 V19', 'M5 12 H19'],
  download: ['M12 3.6 v11 M7.6 10.4 L12 14.8 L16.4 10.4 M4.6 19.4 h14.8'],
  search: ['M11 4.4 a6.6 6.6 0 1 0 0 13.2 a6.6 6.6 0 1 0 0 -13.2', 'M16 16 L20.4 20.4'],
  pin: [
    'M12 16.6 V21 M8.9 3.4 h6.2 M10 3.4 v5.2 L7.5 11.8 A1.7 1.7 0 0 0 9 14.4 h6 a1.7 1.7 0 0 0 1.5 -2.6 L14 8.6 V3.4',
  ],
  clock: ['M12 3.4 a8.6 8.6 0 1 0 0 17.2 a8.6 8.6 0 1 0 0 -17.2', 'M12 7.4 v4.9 l3.2 1.9'],
  check: ['M4.6 12.4 L9.6 17.2 L19.6 6.8'],
  read: ['M2.4 12.2 L6.9 16.9 L16.3 7.1', 'M8.4 12.2 L12.9 16.9 L22.3 7.1'],
  failed: ['M12 3.4 a8.6 8.6 0 1 0 0 17.2 a8.6 8.6 0 1 0 0 -17.2', 'M12 7.6 v5', 'M12 16.1 h.01'],
  lock: [
    'M7.7 10.4 V8 a4.3 4.3 0 0 1 8.6 0 v2.4',
    'M6.4 10.4 h11.2 a1.4 1.4 0 0 1 1.4 1.4 v7.4 a1.4 1.4 0 0 1 -1.4 1.4 H6.4 a1.4 1.4 0 0 1 -1.4 -1.4 v-7.4 a1.4 1.4 0 0 1 1.4 -1.4 Z',
    'M12 14.3 v1.9',
  ],
  heart: [
    'M12 20.2 C7 16.4 3.4 13.3 3.4 9.6 A4.4 4.4 0 0 1 12 7.6 A4.4 4.4 0 0 1 20.6 9.6 C20.6 13.3 17 16.4 12 20.2 Z',
  ],
  thumb: [
    'M7.4 10.4 v10.2 M7.4 10.4 l3.6 -6.6 a1.9 1.9 0 0 1 3.4 1.4 l-.8 3.4 h5.1 a1.8 1.8 0 0 1 1.8 2.1 l-1.2 6.6 a1.8 1.8 0 0 1 -1.8 1.5 H7.4',
  ],
  pause: ['M8.6 5.4 v13.2 M15.4 5.4 v13.2'],
  wifi: [
    'M3.4 9.4 a12.6 12.6 0 0 1 17.2 0',
    'M6.6 12.6 a8 8 0 0 1 10.8 0',
    'M9.8 15.8 a3.4 3.4 0 0 1 4.4 0',
    'M12 19 h.01',
  ],
  chevron: ['M9 5.5 L15.5 12 L9 18.5'],
  file: [
    'M6.4 3.4 h7.4 l4.2 4.2 v13 a1.4 1.4 0 0 1 -1.4 1.4 H6.4 a1.4 1.4 0 0 1 -1.4 -1.4 V4.8 a1.4 1.4 0 0 1 1.4 -1.4 Z',
    'M13.6 3.6 v4.2 h4.2',
  ],
}

const FILLED: Partial<Record<IconName, string[]>> = {
  play: ['M8.4 5.4 L19.2 12 L8.4 18.6 Z'],
}

const DOTS: Partial<Record<IconName, Array<[number, number, number]>>> = {
  more: [[5.4, 12, 1.25], [12, 12, 1.25], [18.6, 12, 1.25]],
}

/** Navigation glyphs keep their shipped 2dp drawing; only chat glyphs were redrawn. */
const NAV: Record<'chat' | 'hub' | 'nearby' | 'settings' | 'transfer' | 'bolt', string[]> = {
  chat: ['M21,11.5c0,4.14 -4.03,7.5 -9,7.5c-1.01,0 -1.98,-0.14 -2.88,-0.39L4.5,20.25l1.32,-3.63C4.13,15.27 3,13.48 3,11.5C3,7.36 7.03,4 12,4S21,7.36 21,11.5z'],
  hub: [
    'M5,21V4a1,1 0,0,1 1,-1h8a1,1 0,0,1 1,1v17',
    'M15,10h3a1,1 0,0,1 1,1v10', 'M3,21h18', 'M9,7h2', 'M9,11h2', 'M9,15h2',
  ],
  nearby: [
    'M12,12h.01', 'M8.93,15.07a4.34,4.34 0,0 1,0 -6.14', 'M15.07,8.93a4.34,4.34 0,0 1,0 6.14',
    'M6.48,17.52a7.8,7.8 0,0 1,0 -11.04', 'M17.52,6.48a7.8,7.8 0,0 1,0 11.04',
  ],
  settings: [
    'M12,15a3,3 0,1 0,0 -6a3,3 0,0 0,0 6z',
    'M19.4,15a1.65,1.65 0,0 0,0.33 1.82l0.06,0.06a2,2 0,0 1,0 2.83a2,2 0,0 1,-2.83 0l-0.06,-0.06a1.65,1.65 0,0 0,-1.82 -0.33a1.65,1.65 0,0 0,-1 1.51V21a2,2 0,0 1,-2 2a2,2 0,0 1,-2 -2v-0.09A1.65,1.65 0,0 0,9 19.4a1.65,1.65 0,0 0,-1.82 0.33l-0.06,0.06a2,2 0,0 1,-2.83 0a2,2 0,0 1,0 -2.83l0.06,-0.06a1.65,1.65 0,0 0,0.33 -1.82a1.65,1.65 0,0 0,-1.51 -1H3a2,2 0,0 1,-2 -2a2,2 0,0 1,2 -2h0.09A1.65,1.65 0,0 0,4.6 9a1.65,1.65 0,0 0,-0.33 -1.82l-0.06,-0.06a2,2 0,0 1,0 -2.83a2,2 0,0 1,2.83 0l0.06,0.06a1.65,1.65 0,0 0,1.82 0.33H9a1.65,1.65 0,0 0,1 -1.51V3a2,2 0,0 1,2 -2a2,2 0,0 1,2 2v0.09a1.65,1.65 0,0 0,1 1.51a1.65,1.65 0,0 0,1.82 -0.33l0.06,-0.06a2,2 0,0 1,2.83 0a2,2 0,0 1,0 2.83l-0.06,0.06a1.65,1.65 0,0 0,-0.33 1.82V9a1.65,1.65 0,0 0,1.51 1H21a2,2 0,0 1,2 2a2,2 0,0 1,-2 2h-0.09a1.65,1.65 0,0 0,-1.51 1z',
  ],
  transfer: ['M3.5,8H19', 'M15.5,4.5L19,8l-3.5,3.5', 'M20.5,16H5', 'M8.5,12.5L5,16l3.5,3.5'],
  bolt: ['M13.2,2.6 L4,13.6 h7 L10.8,21.4 L20,10.4 h-7 Z'],
}

export function Icon({ name, size = 24, strokeWidth, className }: {
  name: IconName | 'chat' | 'hub' | 'nearby' | 'settings' | 'transfer' | 'bolt'
  size?: number
  strokeWidth?: number
  className?: string
}): JSX.Element {
  const nav = NAV[name as keyof typeof NAV]
  const paths = nav ?? STROKE[name as string] ?? []
  const filled = FILLED[name as IconName]
  const dots = DOTS[name as IconName]
  const sw = strokeWidth ?? (nav ? 1.9 : 1.75)

  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      className={className} aria-hidden="true" focusable="false"
      style={{ display: 'block', flex: 'none' }}>
      {paths.map((d, i) => (
        <path key={i} d={d} stroke="currentColor" strokeWidth={sw}
          strokeLinecap="round" strokeLinejoin="round" />
      ))}
      {filled?.map((d, i) => <path key={'f' + i} d={d} fill="currentColor" />)}
      {dots?.map(([cx, cy, r], i) => <circle key={'d' + i} cx={cx} cy={cy} r={r} fill="currentColor" />)}
    </svg>
  )
}

/** The redrawn set, in the order the icon sheet shows it. */
export const ICON_SHEET: Array<{ name: IconName; label: string; note: string }> = [
  { name: 'send', label: 'Send', note: 'cut nose, short fold' },
  { name: 'attach', label: 'Attach', note: 'squared outer loop' },
  { name: 'mic', label: 'Voice', note: 'tapered capsule' },
  { name: 'camera', label: 'Camera', note: 'aperture glint' },
  { name: 'call', label: 'Call', note: 'flat mouthpiece' },
  { name: 'video', label: 'Video', note: 'lens overlaps body' },
  { name: 'more', label: 'More', note: 'wider dot pitch' },
  { name: 'back', label: 'Back', note: 'short tail' },
  { name: 'reply', label: 'Reply', note: 'one hinge' },
  { name: 'lock', label: 'Encrypted', note: 'keyhole, no ring' },
  { name: 'clock', label: 'Sending', note: '9 o’clock hand' },
  { name: 'check', label: 'Sent', note: 'long tail' },
  { name: 'read', label: 'Read', note: 'double, offset' },
  { name: 'file', label: 'File', note: 'folded corner' },
  { name: 'pin', label: 'Pin', note: 'short tack' },
  { name: 'search', label: 'Search', note: 'long handle' },
]
