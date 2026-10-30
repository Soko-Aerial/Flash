/**
 * The non-Hub tabs, kept deliberately light: the redesign target is the Hub tab,
 * these exist so switching tabs in the mock behaves like the real shell (UI-058 D1:
 * enrolled = Chats · Hub · Nearby · Settings; Transfers lives in the chat-list top
 * bar; unenrolled = the four tabs the app always had, with Transfers back).
 */
import { FlashIcon } from './icons'
import { Avatar } from './atoms'
import { ORG } from './data'

const CHATS = [
  { name: 'Maya Chen', last: 'The maintenance window is confirmed for Friday 8–16. Doors go to offline mode', time: '09:12', state: 'read' as const, unread: 0 },
  { name: 'Engineering Design', last: 'Tom: pushed the fixture, can someone review', time: '08:56', state: null, unread: 2, group: true },
  { name: 'Jonas Weber', last: 'Got it, thanks!', time: '08:40', state: 'read' as const, unread: 0 },
  { name: 'Workshop 2 crew', last: 'On my way with the bolts', time: 'Yesterday', state: 'check' as const, unread: 0, group: true },
  { name: 'Lin Okoye', last: 'Receipts: summary is on my door', time: 'Yesterday', state: 'read' as const, unread: 0 },
  { name: 'Sam Rivera', last: 'Truck bay is free until 14:00', time: 'Yesterday', state: null, unread: 0 },
]

export function TopBarWithActions({ title, enrolled, onSearch }: {
  title: string
  enrolled: boolean
  onSearch?: () => void
}) {
  return (
    <div className="TopBar">
      <div className="TopBar-title t-h2">{title}</div>
      {enrolled && (
        <button className="IconBtn" title="Transfers moved to the top bar.">
          <FlashIcon name="transfer" />
          <span className="NavBadge">2</span>
        </button>
      )}
      <button className="IconBtn"><FlashIcon name="group" /></button>
      {onSearch && <button className="IconBtn" onClick={onSearch}><FlashIcon name="search" /></button>}
    </div>
  )
}

/* ---------- Chats ----------------------------------------------------------- */

export function ChatsScreen({ enrolled }: { enrolled: boolean }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg-page)' }}>
      <TopBarWithActions title="Chats" enrolled={enrolled} />
      <div style={{ flex: 1, overflowY: 'auto', paddingBottom: 8 }}>
        {CHATS.map(c => (
          <button key={c.name} className="ChatRow">
            <Avatar name={c.name} size="md" />
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2, flex: 1, minWidth: 0 }}>
              <span className="t-body" style={{ color: 'var(--text)' }}>{c.name}</span>
              <div className="ChatPrev" style={{ display: 'flex', gap: 4 }}>
                <span className="t-cap" style={{ color: 'var(--text-3)', overflow: 'hidden', textOverflow: 'ellipsis' }}>{c.last}</span>
                {c.state !== null && <span className="Tick"><FlashIcon name={c.state} size={14} /></span>}
              </div>
            </span>
            <span style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4 }}>
              <span className="t-meta" style={{ color: 'var(--text-3)' }}>{c.time}</span>
              {c.unread > 0 && <span className="NavBadge" style={{ position: 'static' }}>{c.unread}</span>}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}

/* ---------- Nearby ----------------------------------------------------------- */

const NEARBY = [
  { name: 'Jonas Weber — Pixel', kind: 'On your Wi-Fi', on: true },
  { name: 'Workshop laptop', kind: 'On your Wi-Fi', on: true },
  { name: "Tom's phone — Infinix", kind: 'Offline', on: false },
]

export function NearbyScreen() {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg-page)' }}>
      <div className="TopBar"><div className="TopBar-title t-h2">Nearby</div><button className="IconBtn"><FlashIcon name="search" /></button></div>
      <div style={{ padding: '4px 16px 16px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {NEARBY.map(d => (
          <div key={d.name} className="Card" style={{ display: 'flex', gap: 12, alignItems: 'center', cursor: 'pointer' }}>
            <Avatar name={d.name} size="md" />
            <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span className="t-body-s">{d.name}</span>
              <span className="t-meta" style={{ color: d.on ? 'var(--ok)' : 'var(--text-3)' }}>{d.kind}</span>
            </span>
          </div>
        ))}
        <div className="t-meta" style={{ color: 'var(--text-3)', padding: '4px 2px' }}>
          Devices appear here while they are on the same network.
        </div>
      </div>
    </div>
  )
}

/* ---------- Settings ---------------------------------------------------------- */

const SETTINGS: { label: string; on?: boolean }[] = [
  { label: 'Deliver without a network' },
  { label: 'Read receipts', on: true },
  { label: 'Voice message transcription' },
  { label: 'Private messages (Hub)', on: false },
  { label: 'Launch animation', on: true },
]

export function SettingsScreen({ enrolled }: { enrolled: boolean }) {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg-page)' }}>
      <div className="TopBar"><div className="TopBar-title t-h2">Settings</div></div>
      <div style={{ padding: 16, display: 'flex', gap: 12, alignItems: 'center' }}>
        <Avatar name={ORG.me} size="lg" />
        <span style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span className="t-h3">Sasha Vela</span>
          <span className="t-meta" style={{ color: 'var(--text-3)' }}>
            {enrolled ? 'Acme Corp · Engineering' : 'Provisional identity'}
          </span>
        </span>
      </div>
      <div style={{ padding: '0 16px 16px' }}>
        {SETTINGS.map((s, i) => (
          <button key={i} className="SetRow">
            <span className="t-body" style={{ color: 'var(--text)' }}>{s.label}</span>
            <span className={'Switch' + (s.on ? ' is-on' : '')} style={{ marginLeft: 'auto' }}><span /></span>
          </button>
        ))}
      </div>
    </div>
  )
}

/* ---------- Transfers (unenrolled devices only) ------------------------------ */

const XFERS = [
  { name: 'site-photos.zip', pct: 62, speed: '4.2 MB/s' },
  { name: 'Manual.pdf', pct: 100, speed: 'Done' },
]

export function TransfersScreen() {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg-page)' }}>
      <div className="TopBar"><div className="TopBar-title t-h2">Transfers</div></div>
      <div style={{ padding: 8, display: 'flex', flexDirection: 'column', gap: 6 }}>
        {XFERS.map(f => (
          <div key={f.name} className="XferRow" >
            <Avatar name={f.name} size="md" />
            <span style={{ display: 'flex', flexDirection: 'column' as const, gap: 6, flex: 1 }}>
              <span className="t-body-s">{f.name}</span>
              <div className="Bar"><span style={{ width: `${f.pct}%` }} /></div>
              <span className="t-meta" style={{ color: 'var(--text-3)' }}>{f.speed}</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}
