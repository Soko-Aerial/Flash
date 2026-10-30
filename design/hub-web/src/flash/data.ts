/**
 * The single static world this mock presents. No network, no randomness: every
 * number on screen is in here. Names, counts and states mirror the data the
 * production Hub tab already has access to (see docs/ui/flash-hub-tab.md):
 * hubName, directory members with department + presence, announcements with
 * scope/pin/unread, department groups with join semantics.
 *
 * Copy is taken verbatim from `FlashHubCopy.kt` where a sentence is user-facing,
 * so the Kotlin port can keep using the same constants.
 */

export type Dept = 'Engineering' | 'Finance' | 'Operations' | 'People' | 'Support'
export type Presence = 'online' | 'offline'

export interface Person {
  id: string
  name: string
  dept: Dept
  role?: 'Admin' | 'Head'
  header?: boolean
  paired: boolean
  online: Presence | 'unknown'
  you?: boolean
}

export interface Announcement {
  id: string
  scope: 'Whole company' | 'Department'
  dept?: Dept
  pinned?: boolean
  unread?: boolean
  title: string
  body: string
  author: string
  authorRole: 'Admin' | 'Department head'
  ago: string
}

export interface Group {
  id: string
  name: string
  dept?: Dept
  members: number
  joined?: boolean
  pending?: boolean
}

export const ORG = {
  name: 'Acme Corp',
  monogram: 'AC',
  members: 42,
  departments: 5,
  groups: 5,
  /** The viewer of this mock — a department head, so News offers the composer. */
  me: 'Sasha Vela',
  myRole: 'Member · Engineering' as 'Member · Engineering' | 'Administrator',
  myRoleChip: 'Head',
}

export const DEPARTMENTS: Dept[] = ['Engineering', 'Finance', 'Operations', 'People', 'Support']

export const PEOPLE: Person[] = [
  { id: 'p0', name: 'Sasha Vela', dept: 'Engineering', role: 'Head', paired: true, online: 'online', you: true },
  { id: 'p1', name: 'Maya Chen', dept: 'People', role: 'Admin', paired: true, online: 'online', header: true },
  { id: 'p2', name: 'Jonas Weber', dept: 'Engineering', paired: true, online: 'online' },
  { id: 'p3', name: 'Priya Natarajan', dept: 'Engineering', paired: false, online: 'online' },
  { id: 'p4', name: 'Tom Alvarez', dept: 'Engineering', paired: false, online: 'offline' },
  { id: 'p5', name: 'Grace Liu', dept: 'Engineering', paired: false, online: 'unknown' },
  { id: 'p6', name: 'Lin Okoye', dept: 'Finance', role: 'Head', paired: false, online: 'offline' },
  { id: 'p7', name: 'Sam Rivera', dept: 'Operations', paired: false, online: 'online' },
  { id: 'p8', name: 'Amina Diallo', dept: 'Support', paired: false, online: 'offline' },
  { id: 'p9', name: 'Nadia Petrov', dept: 'People', paired: false, online: 'offline' },
]

export const NEWS: Announcement[] = [
  {
    id: 'a1', scope: 'Whole company', pinned: true,
    title: 'Office closed Friday for maintenance',
    body: 'The building loses power on Friday between 8:00 and 16:00. Badge doors will fail over to offline mode; '
      + 'the server and the company Wi-Fi will be unreachable during this window. If you need to share anything '
      + 'that day, it will send once the network is back.',
    author: 'Maya Chen', authorRole: 'Admin', ago: '3 d',
  },
  {
    id: 'a2', scope: 'Department', dept: 'Engineering', unread: true,
    title: 'New badge printer in Workshop 2',
    body: 'The broken label printer in Workshop 2 was replaced this morning. Ask Operations for the access sheet '
      + 'if you have not used it before.',
    author: 'Maya Chen', authorRole: 'Admin', ago: '1 h',
  },
  {
    id: 'a3', scope: 'Whole company',
    title: 'Reminder: expense receipts through the new form',
    body: 'Expense receipts went to Finance this week; a summary is on the door of my office. Everything you '
      + 'submit this month still uses the old questionnaire. The new form applies from 1 November.',
    author: 'Lin Okoye', authorRole: 'Department head', ago: '2 d',
  },
  {
    id: 'a4', scope: 'Whole company',
    title: 'Server maintenance this Saturday',
    body: 'The company server updates on Saturday at 22:00. Expect roughly twenty minutes without build access. '
      + 'Nothing else changes.',
    author: 'Maya Chen', authorRole: 'Admin', ago: '6 d',
  },
]

export const GROUPS: Group[] = [
  { id: 'g1', name: 'Engineering Design', dept: 'Engineering', members: 12, joined: true },
  { id: 'g2', name: 'Workshop 2 crew', dept: 'Engineering', members: 6, joined: true },
  { id: 'g3', name: 'All Hands', members: 42 },
  { id: 'g4', name: 'Finance Weekly', dept: 'Finance', members: 8 },
  { id: 'g5', name: 'Ops On-call', dept: 'Operations', members: 10, pending: true },
]

/** Strip and glance sentences come from FlashHubCopy.kt — do not paraphrase. */
export const COPY = {
  tabLabel: 'Hub',
  searchHint: 'Search the organisation',
  offline: 'Offline',
  connected: 'Connected',
  countsLine: (a: number, b: number, c: number) => `${a} colleagues · ${b} departments · ${c} groups`,
  stripOfflineCached: (age: string) => `Offline · showing information from ${age}`,
  stripLease: 'Reconnect to the company network to keep colleague trust up to date',
  neverTitle: 'Connect to the company network once to load your colleagues',
  neverBody: 'Once your colleagues are loaded they stay available here without a connection.',
  retry: 'Retry',
  revokTitle: 'Your organisation removed this device',
  revokBody: 'Organisation news, people and groups are no longer available. Chats with people you paired '
    + 'with directly are not affected.',
  signOut: 'Sign out of organisation',
  reachLan: 'On your Wi-Fi now',
  reachHub: 'Reachable through the company server',
  reachOff: 'Offline',
  message: 'Message',
  call: 'Call',
  honestyFooter: 'Not paired with you. Messages go through the company server and can be read there '
    + 'unless you turn on private messages in Settings → Hub.',
  membersCantPost: 'Announcements come from your administrators and department heads. You will see '
    + 'them here as soon as they are posted.',
  shareUpdate: 'Share an update',
  composerSubDept: 'Reach the people in your department',
  pinned: 'Pinned',
  latest: 'Latest',
  annHonesty: 'Announcements are stored as plain text on your company\'s server. Administrators can read them.',
  groupsYours: 'Yours',
  groupsMine: 'Other departments',
  joined: 'Open',
  join: 'Join',
  pending: 'Requested',
  yourDept: 'Your department',
  paired: 'Paired',
  you: 'You',
  noDept: 'No department',
  roleAdmin: 'Admin',
  roleHead: 'Head',
  roleEditorGroup: 'Admin',
}
