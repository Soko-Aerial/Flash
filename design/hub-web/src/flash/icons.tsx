/**
 * Flash icon system (UI-002) rendered for the web mock.
 *
 * Path data is copied 1:1 from the Compose vector drawables in
 * `ui/theme/src/commonMain/composeResources/drawable/flash_ic_*.xml`, so this page
 * and the app draw the same glyphs: 24dp grid, 2dp stroke, round caps and joins,
 * no fills. When the Kotlin port is written it imports the drawable directly.
 */
export type FlashIconName =
  | 'chat' | 'hub' | 'nearby' | 'settings' | 'transfer' | 'search'
  | 'group' | 'device' | 'connection' | 'back' | 'close' | 'pin'
  | 'announcement' | 'call' | 'bolt' | 'clock' | 'check' | 'read'

const D: Record<FlashIconName, string[]> = {
  chat: ['M21,11.5c0,4.14 -4.03,7.5 -9,7.5c-1.01,0 -1.98,-0.14 -2.88,-0.39L4.5,20.25l1.32,-3.63C4.13,15.27 3,13.48 3,11.5C3,7.36 7.03,4 12,4S21,7.36 21,11.5z'],
  hub: [
    'M5,21V4a1,1 0,0,1 1,-1h8a1,1 0,0,1 1,1v17',
    'M15,10h3a1,1 0,0,1 1,1v10',
    'M3,21h18',
    'M9,7h2', 'M9,11h2', 'M9,15h2',
  ],
  nearby: [
    'M12,12h.01',
    'M8.93,15.07a4.34,4.34 0,0 1,0 -6.14',
    'M15.07,8.93a4.34,4.34 0,0 1,0 6.14',
    'M6.48,17.52a7.8,7.8 0,0 1,0 -11.04',
    'M17.52,6.48a7.8,7.8 0,0 1,0 11.04',
  ],
  settings: [
    'M12,15a3,3 0,1 0,0 -6a3,3 0,0 0,0 6z',
    'M19.4,15a1.65,1.65 0,0 0,0.33 1.82l0.06,0.06a2,2 0,0 1,0 2.83a2,2 0,0 1,-2.83 0l-0.06,-0.06a1.65,1.65 0,0 0,-1.82 -0.33a1.65,1.65 0,0 0,-1 1.51V21a2,2 0,0 1,-2 2a2,2 0,0 1,-2 -2v-0.09A1.65,1.65 0,0 0,9 19.4a1.65,1.65 0,0 0,-1.82 0.33l-0.06,0.06a2,2 0,0 1,-2.83 0a2,2 0,0 1,0 -2.83l0.06,-0.06a1.65,1.65 0,0 0,0.33 -1.82a1.65,1.65 0,0 0,-1.51 -1H3a2,2 0,0 1,-2 -2a2,2 0,0 1,2 -2h0.09A1.65,1.65 0,0 0,4.6 9a1.65,1.65 0,0 0,-0.33 -1.82l-0.06,-0.06a2,2 0,0 1,0 -2.83a2,2 0,0 1,2.83 0l0.06,0.06a1.65,1.65 0,0 0,1.82 0.33H9a1.65,1.65 0,0 0,1 -1.51V3a2,2 0,0 1,2 -2a2,2 0,0 1,2 2v0.09a1.65,1.65 0,0 0,1 1.51a1.65,1.65 0,0 0,1.82 -0.33l0.06,-0.06a2,2 0,0 1,2.83 0a2,2 0,0 1,0 2.83l-0.06,0.06a1.65,1.65 0,0 0,-0.33 1.82V9a1.65,1.65 0,0 0,1.51 1H21a2,2 0,0 1,2 2a2,2 0,0 1,-2 2h-0.09a1.65,1.65 0,0 0,-1.51 1z',
  ],
  transfer: ['M3.5,8H19', 'M15.5,4.5L19,8l-3.5,3.5', 'M20.5,16H5', 'M8.5,12.5L5,16l3.5,3.5'],
  search: ['M11,11m-7,0a7,7 0,1,1 14,0a7,7 0,1,1 -14,0', 'M16.5,16.5 L21,21'],
  group: [
    'M17,21v-2a4,4 0,0,0 -4,-4H5a4,4 0,0,0 -4,4v2',
    'M9,7m-4,0a4,4 0,1,1 8,0a4,4 0,1,1 -8,0',
    'M23,21v-2a4,4 0,0,0 -3,-3.87 M16,3.13a4,4 0,0,1 0,7.75',
  ],
  device: ['M17,2H7C5.9,2 5,2.9 5,4v16c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V4C19,2.9 18.1,2 17,2z', 'M12,18h.01'],
  connection: ['M10,13a5,5 0,0,0 7.54.54l3,-3a5,5 0,0,0 -7.07,-7.07l-1.72,1.71 M14,11a5,5 0,0,0 -7.54,-.54l-3,3a5,5 0,0,0 7.07,7.07l1.71,-1.71'],
  back: ['M19,12 L5,12 M12,19 L5,12 L12,5'],
  close: ['M18,6 L6,18 M6,6 L18,18'],
  pin: ['M12,17v5 M9,3h6 M10,3v5.5L7.5,12A1.5,1.5 0,0,0 9,14.5h6a1.5,1.5 0,0,0 1.5,-2.5L14,8.5V3'],
  announcement: ['M4,10v4h3l7,4V6L7,10H4z', 'M17.5,9a4,4 0,0,1 0,6', 'M7.5,14.5l1.2,4.5h2.4l-1,-3.6'],
  call: ['M22,16.92v3a2,2 0,0,1 -2.18,2 19.79,19.79 0,0,1 -8.63,-3.07 19.5,19.5 0,0,1 -6,-6 19.79,19.79 0,0,1 -3.07,-8.67A2,2 0,0,1 4.11,2h3a2,2 0,0,1 2,1.72 12.84,12.84 0,0,0 .7,2.81 2,2 0,0,1 -.45,2.11L8.09,9.91a16,16 0,0,0 6,6l1.27,-1.27a2,2 0,0,1 2.11-.45 12.84,12.84 0,0,0 2.81.7A2,2 0,0,1 22,16.92z'],
  bolt: ['M13,2 L3,14 L12,14 L11,22 L21,10 L12,10 L13,2 Z'],
  clock: ['M12,12m-9,0a9,9 0,1,1 18,0a9,9 0,1,1 -18,0', 'M12,7v5l3,2'],
  check: ['M5,12 L10,17 L20,7'],
  read: ['M2,12 L7,17 L17,7', 'M8,12 L13,17 L23,7'],
}

export function FlashIcon({ name, size = 24, className }: {
  name: FlashIconName
  size?: number
  className?: string
}) {
  return (
    <svg
      width={size} height={size} viewBox="0 0 24 24" fill="none"
      className={className} aria-hidden="true"
      style={{ display: 'block' }}
    >
      {D[name].map((d, i) => (
        <path key={i} d={d} stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      ))}
    </svg>
  )
}
