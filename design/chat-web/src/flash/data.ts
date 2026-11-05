/**
 * One believable conversation and the chat list around it.
 *
 * The thread is a real job, not filler: Ama is surveying a tiled arcade on her
 * phone and sending the 4K pass to Kwame's desktop over the office LAN, which is
 * the exact thing Flash exists for. File sizes, percentages and the checksum line
 * agree with each other: 1.2 of 1.8 GB is 68%, and 0.6 GB left at 12.4 MB/s is 48s.
 */

export type WallpaperId = 'graphite' | 'signal' | 'blueprint' | 'draft' | 'pulse' | 'custom'

export interface Wallpaper {
  id: WallpaperId
  name: string
  note: string
}

export const WALLPAPERS: Wallpaper[] = [
  { id: 'graphite', name: 'Graphite', note: 'Flat. The shipped surface.' },
  { id: 'signal', name: 'Signal', note: 'Dot grid, one pulse in the corner.' },
  { id: 'blueprint', name: 'Blueprint', note: 'Surveyor’s hatching.' },
  { id: 'draft', name: 'Draft', note: 'Graph paper for long reads.' },
  { id: 'pulse', name: 'Pulse', note: 'Rings from the corner.' },
]

/** Flat colours for "your own colour", named. Muted so bubbles keep the contrast. */
export const CUSTOM_COLOURS: Array<{ hex: string; name: string }> = [
  { hex: '#e7e9ec', name: 'Stone' },
  { hex: '#e4ede3', name: 'Sage' },
  { hex: '#f2e8d8', name: 'Sand' },
  { hex: '#f0e1dc', name: 'Clay' },
  { hex: '#dff1ec', name: 'Mint' },
  { hex: '#dee9f2', name: 'Sky' },
]

export interface Reaction { glyph: string; count: number; mine?: boolean }

export type Message =
  | {
    k: 'text'; id: string; from: 'in' | 'out'; time: string; text: string
    reply?: { name: string; text: string }
    status?: 'sent' | 'read'
    reactions?: Reaction[]
  }
  | {
    k: 'photo'; id: string; from: 'in' | 'out'; time: string
    caption: string; alt: string; src: string; size: string
  }
  | {
    k: 'file'; id: string; from: 'in' | 'out'; time: string
    name: string; meta: string; progress: number; detail: string
    verified?: boolean; status?: 'sent' | 'read'
  }
  | {
    k: 'voice'; id: string; from: 'in' | 'out'; time: string
    duration: string; amplitudes: number[]; played?: number
    reactions?: Reaction[]
  }

/** A tile-able amplitude strip: real speech is symmetric around a quiet floor. */
const VOICE = [
  18, 26, 40, 62, 78, 54, 36, 30, 46, 70, 88, 64, 42, 30, 24, 34, 52, 74,
  92, 68, 44, 32, 26, 38, 58, 80, 60, 40, 28, 22, 30, 44, 66, 84, 56, 34,
]

export const THREAD: Message[] = [
  {
    k: 'photo', id: 'm1', from: 'in', time: '10:02',
    src: 'east-arcade.jpg',
    alt: 'Tiled arcade of a stone medrese, lit from the courtyard',
    caption: 'East arcade, 40 minutes before the light goes.',
    size: 'Photo · 2.4 MB',
  },
  {
    k: 'text', id: 'm2', from: 'in', time: '10:02',
    text: 'The 4K pass is three clips, 1.8 GB in all. Sending them direct.',
  },
  {
    k: 'text', id: 'm3', from: 'out', time: '10:03', status: 'read',
    text: 'No upload? We are both on the office LAN.',
  },
  {
    k: 'text', id: 'm4', from: 'in', time: '10:03',
    text: 'Direct. 5 GHz, 12.4 MB/s and holding.',
  },
  {
    k: 'file', id: 'm5', from: 'in', time: '10:04',
    name: 'Ceremony-east-arcade-01.mp4', meta: 'MP4 · 1.8 GB',
    progress: 0.68, detail: '1.2 of 1.8 GB · 12.4 MB/s · 0:48 left',
  },
  {
    k: 'text', id: 'm6', from: 'out', time: '10:04', status: 'read',
    text: 'That tile pattern is the one we measured. Send the rest.',
    reply: { name: 'Ama Boateng', text: 'East arcade, 40 minutes before the light goes.' },
  },
  {
    k: 'voice', id: 'm7', from: 'in', time: '10:06',
    duration: '0:14', amplitudes: VOICE,
  },
  {
    k: 'file', id: 'm8', from: 'in', time: '10:11',
    name: 'Ceremony-east-arcade-02.mp4', meta: 'MP4 · 1.8 GB',
    progress: 1, detail: '1.8 GB · 2 m 26 s', verified: true,
  },
  {
    k: 'text', id: 'm9', from: 'in', time: '10:11',
    text: 'Checksum matches here too. Archive both before Friday.',
  },
]

export const UNREAD_AT = 7

/* ------------------------------------------------------------------ chat list */

export interface ChatRow {
  id: string
  name: string
  initials: string
  preview: string
  time: string
  unread?: number
  online?: boolean
  group?: boolean
  pinned?: boolean
}

export const CHATS: ChatRow[] = [
  {
    id: 'ama', name: 'Ama Boateng', initials: 'AB',
    preview: 'Checksum matches here too. Archive both before Friday.',
    time: '10:11', online: true, pinned: true,
  },
  {
    id: 'survey', name: 'Arcade survey', initials: 'AS',
    preview: '3 clips · 1.8 GB · 68%', time: '10:04', unread: 4, group: true,
  },
  {
    id: 'kwame', name: 'Kwame Mensah', initials: 'KM',
    preview: 'The east wall measures 4.2 m, not 4.0.', time: '09:12', online: true,
  },
  {
    id: 'mason', name: 'Mason & Co', initials: 'MC',
    preview: 'You: I will send the archive tonight.', time: 'Yesterday',
  },
  {
    id: 'desk', name: 'DESKTOP-7Q2', initials: 'D7',
    preview: 'Direct · paired 14 Sep', time: 'Tue',
  },
]

/* --------------------------------------------------------------- appearances */

export interface Appearance {
  wallpaper: WallpaperId
  custom: string
  theme: 'system' | 'light' | 'dark'
  scale: 'small' | 'default' | 'large'
  reduceMotion: boolean
}

export const APPEARANCE_DEFAULT: Appearance = {
  wallpaper: 'signal',
  custom: CUSTOM_COLOURS[5].hex,
  theme: 'light',
  scale: 'default',
  reduceMotion: false,
}
