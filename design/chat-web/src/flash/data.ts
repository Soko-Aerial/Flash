/**
 * The conversation, the group around it, and the chat list.
 *
 * The direct thread is a real job, not filler: Ama is surveying a tiled arcade on
 * her phone and sending the 4K pass to Kwame's desktop over the office LAN, which
 * is the exact thing Flash exists for. File sizes, percentages and the checksum
 * line agree: 1.2 of 1.8 GB is 68%, and 0.6 GB at 12.4 MB/s is 48 s.
 *
 * The group thread exercises the swarm: the map counts agree with the progress
 * (12 of 24 blocks = 50%), and the holder lines use FlashSwarmUiMath's wording.
 */
import type { IconName } from './icons'

export type WallpaperId = 'graphite' | 'signal' | 'blueprint' | 'draft' | 'pulse' | 'custom'

export interface Wallpaper {
  id: WallpaperId
  name: string
  note: string
}

export const WALLPAPERS: Wallpaper[] = [
  { id: 'graphite', name: 'Graphite', note: 'Flat. The shipped surface.' },
  { id: 'signal', name: 'Signal', note: 'The device mesh, as a dot grid.' },
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

/* ----------------------------------------------------------------- messages */

export type Piece = 0 | 1 | 2 | 3 // missing · from a peer · downloading · verified

export interface Holder {
  name: string
  initials: string
  line: string
  online: boolean
}

export type Message =
  | {
    k: 'text'; id: string; from: 'in' | 'out'; time: string; text: string; sender?: string
    reply?: { name: string; text: string }
    status?: 'sent' | 'read'
  }
  | {
    k: 'photo'; id: string; from: 'in' | 'out'; time: string; sender?: string
    caption: string; alt: string; src: string; size: string
  }
  | {
    k: 'file'; id: string; from: 'in' | 'out'; time: string; sender?: string
    name: string; meta: string; progress: number; detail: string
    verified?: boolean; status?: 'sent' | 'read'
    map?: Piece[]; peers?: number; holders?: Holder[]
    swarmLine?: string
  }
  | {
    k: 'voice'; id: string; from: 'in' | 'out'; time: string; sender?: string
    duration: string; amplitudes: number[]
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

/* --------------------------------------------------------------- the group */

export const GROUP = {
  name: 'Arcade survey',
  initials: 'AS',
  members: 6,
  online: 3,
  subtitle: '6 members · 3 online',
  invite: 'flash://g/1/7Kd2mQ9vT3xR8pLwZaN4hJcY6sUeB1fG0oXiV5rP2nMaQ',
}

export interface Member {
  id: string
  name: string
  initials: string
  role: 'Admin' | 'Member'
  online: boolean
  tone: number
  transport?: 'LAN' | 'Wi-Fi Direct' | 'Relay' | 'Radio'
  introducedBy?: string
  you?: boolean
}

export const MEMBERS: Member[] = [
  { id: 'ama', name: 'Ama Boateng', initials: 'AB', role: 'Admin', online: true, tone: 0, transport: 'LAN' },
  { id: 'you', name: 'Kofi Boateng', initials: 'KB', role: 'Member', online: true, tone: 1, transport: 'LAN', you: true },
  { id: 'kwame', name: 'Kwame Mensah', initials: 'KM', role: 'Member', online: true, tone: 2, transport: 'Wi-Fi Direct' },
  { id: 'kai', name: 'Kai Moreno', initials: 'KO', role: 'Member', online: false, tone: 3, transport: 'Relay', introducedBy: 'Ama Boateng' },
  { id: 'lina', name: 'Lina Farid', initials: 'LF', role: 'Member', online: false, tone: 0 },
  { id: 'nia', name: 'Nia Owusu', initials: 'NO', role: 'Member', online: false, tone: 1 },
]

export const JOIN_REQUESTS = [
  { id: 'dana', name: 'Dana Wolfe', initials: 'DW', paired: true },
  { id: 'tessa', name: 'Tessa Adeyemi', initials: 'TA', paired: false },
]

/** 12 verified of 24 blocks, 2 downloading, 4 from peers, 6 missing -> 50%. */
export const RECEIVED_MAP: Piece[] = [
  3, 3, 3, 1, 3, 0, 3, 2, 0, 3, 3, 1, 0, 3, 2, 3, 1, 0, 3, 3, 0, 1, 3, 0,
]
const SENT_MAP: Piece[] = Array.from({ length: 24 }, () => 3 as Piece)

export const GROUP_THREAD: Message[] = [
  {
    k: 'text', id: 'g1', from: 'in', sender: 'Ama Boateng', time: '10:02',
    text: 'East elevation is on its way to the group. 1.4 GB, 4K.',
  },
  {
    k: 'file', id: 'g2', from: 'in', sender: 'Ama Boateng', time: '10:03',
    name: 'Ceremony-east-elevation.mp4', meta: 'MP4 · 1.4 GB',
    progress: 0.5, detail: 'Getting it from 3 devices · 4.8 MB/s',
    map: RECEIVED_MAP, peers: 3,
  },
  {
    k: 'text', id: 'g3', from: 'out', time: '10:04', status: 'read',
    text: 'I will keep seeding while I cut the rough assembly.',
  },
  {
    k: 'text', id: 'g4', from: 'in', sender: 'Kwame Mensah', time: '10:04',
    text: 'Got it from two devices at once.',
  },
  {
    k: 'text', id: 'g5', from: 'in', sender: 'Ama Boateng', time: '10:05',
    text: 'That is the point of the group.',
    reply: { name: 'Kwame Mensah', text: 'Got it from two devices at once.' },
  },
  {
    k: 'text', id: 'g6', from: 'in', sender: 'Kai Moreno', time: '10:07',
    text: 'Wide shot is next, from the site phone.',
  },
]

/** The sender's own file in the group: every block verified, 4 of 6 delivered. */
export const SENT_FILE: Extract<Message, { k: 'file' }> = {
  k: 'file', id: 's1', from: 'out', time: '10:09',
  name: 'Ceremony-west-arcade.mp4', meta: 'MP4 · 1.1 GB',
  progress: 1, detail: 'Sent to the group',
  map: SENT_MAP, peers: 4,
  swarmLine: 'Delivered to 4 of 6 · You can go offline now',
  holders: [
    { name: 'Ama Boateng', initials: 'AB', line: 'Has the file', online: true },
    { name: 'Kwame Mensah', initials: 'KM', line: '62% · 4.1 MB/s', online: true },
    { name: 'Lina Farid', initials: 'LF', line: 'Offline · 40%', online: false },
    { name: 'Kai Moreno', initials: 'KO', line: 'Waiting to start', online: false },
  ],
}

/* ------------------------------------------------------------------- menus */

export interface MenuItem {
  id: string
  label: string
  icon: IconName
  destructive?: boolean
  goes?: 'profile' | 'appearance' | 'groupInfo' | 'groupSettings' | 'invite' | 'addMembers'
}

/** FlashConversationMenuMath.directItems + the proposed Chat appearance entry. */
export const DIRECT_MENU: MenuItem[] = [
  { id: 'profile', label: 'View profile', icon: 'device', goes: 'profile' },
  { id: 'search', label: 'Search in conversation', icon: 'search' },
  { id: 'appearance', label: 'Chat appearance', icon: 'gallery', goes: 'appearance' },
  { id: 'unread', label: 'Mark as unread', icon: 'read' },
  { id: 'clear', label: 'Clear conversation', icon: 'delete', destructive: true },
  { id: 'revoke', label: 'Revoke trust', icon: 'close', destructive: true },
]

/** FlashConversationMenuMath.groupItems for an admin member of a v2 group. */
export const GROUP_MENU: MenuItem[] = [
  { id: 'info', label: 'Group info', icon: 'group', goes: 'groupInfo' },
  { id: 'settings', label: 'Group settings', icon: 'settings', goes: 'groupSettings' },
  { id: 'invite', label: 'Invite link', icon: 'share', goes: 'invite' },
  { id: 'add', label: 'Add members', icon: 'plus', goes: 'addMembers' },
  { id: 'search', label: 'Search in conversation', icon: 'search' },
  { id: 'appearance', label: 'Chat appearance', icon: 'gallery', goes: 'appearance' },
  { id: 'unread', label: 'Mark as unread', icon: 'read' },
  { id: 'leave', label: 'Leave group', icon: 'close', destructive: true },
]

/* --------------------------------------------------------------- settings */

export interface Toggle {
  id: string
  title: string
  subtitle: string
  on: boolean
  disabled?: boolean
}

export const GROUP_RULES: Toggle[] = [
  { id: 'approval', title: 'Require approval to join', subtitle: 'New members need an admin’s approval to join.', on: true },
  { id: 'invites', title: 'Admins only share invites', subtitle: 'Any active member can share an invite link.', on: false },
  { id: 'add', title: 'Allow members to add others', subtitle: 'Active members can directly add other people.', on: true },
  { id: 'swarm', title: 'Group file swarming', subtitle: 'Group members cooperatively share downloaded file pieces.', on: true },
]

export const DEVICE_PREFS: Toggle[] = [
  { id: 'seed', title: 'Help share group files', subtitle: 'This device seeds pieces it already has.', on: true },
  { id: 'wifi', title: 'Share over Wi-Fi only', subtitle: 'Never use mobile data to seed.', on: true },
]

/* ------------------------------------------------------------------- chat list */

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
    preview: 'Kai: Wide shot is next, from the site phone.', time: '10:07', unread: 3, group: true,
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
