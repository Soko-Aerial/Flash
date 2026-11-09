import { useEffect, useState } from 'react'
import { Icon } from './icons'
import { Conversation } from './Conversation'
import { AppearanceScreen, WallpaperSheet } from './Wallpaper'
import { ChatMenu } from './Menu'
import { GroupInfoScreen, GroupSettingsScreen, InviteSheet } from './Group'
import { ProfileSheet } from './Profile'
import {
  CHATS, DIRECT_MENU, GROUP_MENU, type MenuItem, type WallpaperId,
} from './data'

export type Theme = 'light' | 'dark'
export type Screen = 'chat' | 'appearance' | 'groupInfo' | 'groupSettings'
export type Sheet = 'none' | 'menu' | 'wallpaper' | 'profile' | 'invite'
export type ChatKind = 'direct' | 'group'

export interface FrameProps {
  theme: Theme
  wallpaper: WallpaperId
  custom: string
  chat?: ChatKind
  initialScreen?: Screen
  initialSheet?: Sheet
  append?: import('./data').Message[]
  onPick: (id: WallpaperId) => void
  onCustom: (hex: string) => void
  onUndo: () => void
  onTheme: (t: Theme) => void
  onResetWallpaper: () => void
  reduceMotion: boolean
  onReduceMotion: (v: boolean) => void
}

const INERT: Record<string, string> = {
  search: 'Search in conversation opens the in-chat search',
  unread: 'Marked as unread',
  clear: 'Clear conversation asks for confirmation',
  revoke: 'Revoke trust asks for confirmation',
  leave: 'Leave group asks for confirmation',
}

/** One phone's worth of state: which screen, which sheet, and the last confirmation. */
function useAppState(p: FrameProps) {
  const chat = p.chat ?? 'direct'
  const [screen, setScreen] = useState<Screen>(p.initialScreen ?? 'chat')
  const [sheet, setSheet] = useState<Sheet>(p.initialSheet ?? 'none')
  const [snack, setSnack] = useState('')

  useEffect(() => {
    if (!snack) return
    const id = window.setTimeout(() => setSnack(''), 2600)
    return () => window.clearTimeout(id)
  }, [snack])

  const menuItems = chat === 'group' ? GROUP_MENU : DIRECT_MENU

  const pick = (item: MenuItem) => {
    setSheet('none')
    switch (item.goes) {
      case 'profile': setSheet('profile'); break
      case 'appearance': setScreen('appearance'); break
      case 'groupInfo':
      case 'addMembers': setScreen('groupInfo'); break
      case 'groupSettings': setScreen('groupSettings'); break
      case 'invite': setSheet('invite'); break
      default: setSnack(INERT[item.id] ?? item.label)
    }
  }

  return {
    chat, screen, setScreen, sheet, setSheet, snack, setSnack, menuItems, pick,
  }
}

function Snack({ text }: { text: string }) {
  if (!text) return null
  return (
    <div className="c-snack" role="status" aria-live="polite">{text}</div>
  )
}

/* ------------------------------------------------------------------- status */

function StatusBar({ dark }: { dark: boolean }) {
  const ink = dark ? '#f5f6f8' : '#171a22'
  return (
    <div className="f-status" aria-hidden="true">
      <span className="f-status-time">10:12</span>
      <span className="f-status-icons">
        <svg width="17" height="11" viewBox="0 0 17 11" fill={ink}>
          <rect x="0" y="7" width="3" height="4" rx="1" />
          <rect x="4.6" y="5" width="3" height="6" rx="1" />
          <rect x="9.2" y="2.6" width="3" height="8.4" rx="1" />
          <rect x="13.8" y="0" width="3" height="11" rx="1" opacity="0.35" />
        </svg>
        <svg width="15" height="11" viewBox="0 0 15 11" fill="none" stroke={ink} strokeWidth="1.5" strokeLinecap="round">
          <path d="M1 4.2a9.6 9.6 0 0 1 13 0" />
          <path d="M3.6 6.8a6 6 0 0 1 7.8 0" />
          <path d="M6.4 9.4a2.2 2.2 0 0 1 2.2 0" />
        </svg>
        <svg width="23" height="11" viewBox="0 0 23 11" fill="none">
          <rect x="0.6" y="0.6" width="19" height="9.8" rx="2.4" stroke={ink} strokeOpacity="0.45" />
          <rect x="2.4" y="2.4" width="13.4" height="6.2" rx="1.4" fill={ink} />
          <path d="M21.2 4v3" stroke={ink} strokeOpacity="0.45" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </span>
    </div>
  )
}

/* -------------------------------------------------------------------- screens */

function AppScreen({ p, s }: { p: FrameProps; s: ReturnType<typeof useAppState> }) {
  if (s.screen === 'appearance') {
    return (
      <AppearanceScreen
        wallpaper={p.wallpaper}
        custom={p.custom}
        theme={p.theme}
        onTheme={p.onTheme}
        onReset={p.onResetWallpaper}
        onOpenWallpaper={() => s.setSheet('wallpaper')}
        onBack={() => s.setScreen('chat')}
      />
    )
  }
  if (s.screen === 'groupInfo') {
    return (
      <GroupInfoScreen
        onBack={() => s.setScreen('chat')}
        onSettings={() => s.setScreen('groupSettings')}
        onInvite={() => s.setSheet('invite')}
        onSnack={s.setSnack}
      />
    )
  }
  if (s.screen === 'groupSettings') {
    return (
      <GroupSettingsScreen
        onBack={() => s.setScreen('groupInfo')}
        onInvite={() => s.setSheet('invite')}
        onSnack={s.setSnack}
      />
    )
  }
  return (
    <Conversation
      chat={s.chat}
      wallpaper={p.wallpaper}
      custom={p.custom}
      typing={false}
      append={p.append}
      onMenu={() => s.setSheet('menu')}
      onOpenInfo={() => s.setScreen('groupInfo')}
      onOpenProfile={() => s.setSheet('profile')}
    />
  )
}

/* --------------------------------------------------------------------- phone */

export function PhoneFrame(p: FrameProps) {
  const s = useAppState(p)

  return (
    <div className="f-phone">
      <div className="f-phone-screen" data-theme={p.theme}>
        <StatusBar dark={p.theme === 'dark'} />
        <div className="f-phone-body">
          <AppScreen p={p} s={s} />

          {s.sheet === 'menu' && (
            <ChatMenu items={s.menuItems} onPick={s.pick} onClose={() => s.setSheet('none')} />
          )}
          {s.sheet === 'wallpaper' && (
            <WallpaperSheet
              wallpaper={p.wallpaper}
              custom={p.custom}
              onPick={p.onPick}
              onCustom={p.onCustom}
              onUndo={p.onUndo}
              onClose={() => s.setSheet('none')}
            />
          )}
          {s.sheet === 'profile' && (
            <ProfileSheet onClose={() => s.setSheet('none')} onSnack={s.setSnack} />
          )}
          {s.sheet === 'invite' && (
            <InviteSheet onClose={() => s.setSheet('none')} onSnack={s.setSnack} />
          )}
          <Snack text={s.snack} />
        </div>
        <div className="f-gesture" aria-hidden="true" />
      </div>
    </div>
  )
}

/* --------------------------------------------------------------- chats list */

function ListPane({ chat }: { chat: ChatKind }) {
  const [query, setQuery] = useState('')
  const q = query.trim().toLowerCase()
  const rows = q
    ? CHATS.filter(c => (c.name + ' ' + c.preview).toLowerCase().includes(q))
    : CHATS
  const selected = chat === 'group' ? 'survey' : 'ama'

  return (
    <aside className="d-list" aria-label="Chats">
      <div className="d-list-head">
        <h3 className="d-list-title">Chats</h3>
        <button type="button" className="d-ghost" aria-label="Search chats">
          <Icon name="search" size={20} />
        </button>
      </div>
      <label className="d-search">
        <Icon name="search" size={16} />
        <input
          value={query}
          onChange={e => setQuery(e.target.value)}
          placeholder="Search"
          aria-label="Search chats"
        />
        <span className="d-search-hint">Ctrl K</span>
      </label>
      <div className="d-rows">
        {rows.length === 0 && <p className="d-empty">No chat matches “{query}”.</p>}
        {rows.map(c => (
          <button key={c.id} type="button" className={'d-row' + (c.id === selected ? ' is-on' : '')}>
            <span className={'c-avatar c-avatar-lg' + (c.group ? ' is-group' : '')}>
              {c.initials}
              {c.online && <span className="c-avatar-dot" />}
            </span>
            <span className="d-row-body">
              <span className="d-row-top">
                <span className="d-row-name">{c.name}</span>
                <span className="d-row-time">{c.time}</span>
              </span>
              <span className="d-row-bottom">
                <span className="d-row-preview">{c.preview}</span>
                {c.unread ? <span className="d-unread">{c.unread}</span>
                  : c.pinned ? <Icon name="pin" size={13} /> : null}
              </span>
            </span>
          </button>
        ))}
      </div>
      <div className="d-list-foot">
        <span className="d-foot-dot" />2 paired devices on this network
      </div>
    </aside>
  )
}

/* ------------------------------------------------------------------- desktop */

const NAV_ITEMS = [
  { icon: 'chat', label: 'Chats' },
  { icon: 'hub', label: 'Hub' },
  { icon: 'nearby', label: 'Nearby' },
  { icon: 'settings', label: 'Settings' },
] as const

export function DesktopFrame(p: FrameProps) {
  const s = useAppState(p)
  const chat = p.chat ?? 'direct'

  return (
    <div className="f-window" data-theme={p.theme}>
      <div className="f-titlebar">
        <span className="f-titlebar-brand">
          <span className="f-titlebar-bolt"><Icon name="bolt" size={13} /></span>
          Flash
        </span>
        <span className="f-titlebar-window" aria-hidden="true">
          <span><svg width="10" height="10" viewBox="0 0 10 10"><path d="M1 5h8" stroke="currentColor" strokeWidth="1" /></svg></span>
          <span><svg width="10" height="10" viewBox="0 0 10 10"><rect x="1.2" y="1.2" width="7.6" height="7.6" fill="none" stroke="currentColor" strokeWidth="1" /></svg></span>
          <span><svg width="10" height="10" viewBox="0 0 10 10"><path d="M1.6 1.6l6.8 6.8M8.4 1.6l-6.8 6.8" stroke="currentColor" strokeWidth="1" /></svg></span>
        </span>
      </div>
      <div className="f-desk">
        <nav className="d-rail" aria-label="Primary">
          <span className="d-medallion"><Icon name="bolt" size={19} /></span>
          <div className="d-rail-tabs">
            {NAV_ITEMS.map((t, i) => (
              <button key={t.icon} type="button" className={'d-rail-tab' + (i === 0 ? ' is-on' : '')}
                aria-label={t.label}>
                <Icon name={t.icon} size={22} />
                {t.icon === 'chat' && <span className="d-rail-badge">2</span>}
              </button>
            ))}
          </div>
          <span className="d-rail-profile">KB</span>
        </nav>
        <div className="f-rail-rule" aria-hidden="true" />
        <ListPane chat={chat} />
        <main className="d-chat">
          <AppScreen p={p} s={s} />
          {s.sheet === 'menu' && (
            <ChatMenu items={s.menuItems} onPick={s.pick} onClose={() => s.setSheet('none')} />
          )}
          {s.sheet === 'wallpaper' && (
            <WallpaperSheet
              wallpaper={p.wallpaper}
              custom={p.custom}
              onPick={p.onPick}
              onCustom={p.onCustom}
              onUndo={p.onUndo}
              onClose={() => s.setSheet('none')}
            />
          )}
          {s.sheet === 'profile' && (
            <ProfileSheet onClose={() => s.setSheet('none')} onSnack={s.setSnack} />
          )}
          {s.sheet === 'invite' && (
            <InviteSheet onClose={() => s.setSheet('none')} onSnack={s.setSnack} />
          )}
          <Snack text={s.snack} />
        </main>
      </div>
    </div>
  )
}
