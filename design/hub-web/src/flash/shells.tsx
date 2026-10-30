/**
 * The two app shells, copied from the real ones:
 *
 *  Phone  → FlashBottomNav (UI-046): floating capsule 16/12 from the screen edges,
 *           60dp tall, hairline ring + soft shadow, accent chip at 16% alpha
 *           `calc(i*(25% - 2px) + 4px)` and width `calc(25% - 10px)` reproduce
 *           FlashBottomNavMath.indicatorStartPxAt for four tabs.
 *  Desktop → FlashNavigationRail: 68dp icon rail, brand medallion, 40dp icon wells,
 *           3×22dp active edge bar, badge pill, profile avatar.
 *
 * Tab sets follow UI-058 D1 (FlashShellTabs): enrolled = Chats · Hub · Nearby ·
 * Settings; unenrolled = Chats · Transfers · Nearby · Settings. Revoked keeps the
 * Hub item showing the removal notice, as the hub does.
 */
import { useEffect } from 'react'
import { FlashIcon, type FlashIconName } from './icons'
import { HubScreen, HubDetail } from './hub'
import { ChatsScreen, NearbyScreen, SettingsScreen, TransfersScreen } from './tabs'
import { useFrame, useGlobal, type TabId } from './state'
import { ORG } from './data'

interface TabDef { id: TabId; label: string; icon: FlashIconName }

const ENROLLED_TABS: TabDef[] = [
  { id: 'chats', label: 'Chats', icon: 'chat' },
  { id: 'hub', label: 'Hub', icon: 'hub' },
  { id: 'nearby', label: 'Nearby', icon: 'nearby' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
]
const UNENROLLED_TABS: TabDef[] = [
  { id: 'chats', label: 'Chats', icon: 'chat' },
  { id: 'transfers', label: 'Transfers', icon: 'transfer' },
  { id: 'nearby', label: 'Nearby', icon: 'nearby' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
]

/** Badges mirror the shell sources: chat unread on Chats, hub announcements on Hub. */
function badgeFor(t: TabId, enrolled: boolean): number {
  if (!enrolled) return t === 'transfers' ? 2 : 0
  return t === 'chats' ? 2 : t === 'hub' ? 0 : 0
}

function useTabFix(frameTab: TabId, enrolled: boolean, fix: (t: TabId) => void) {
  useEffect(() => {
    if (!enrolled && frameTab === 'hub') fix('chats')
  }, [enrolled, frameTab, fix])
}

/* =========================== phone ========================================== */

export function PhoneFrame() {
  const g = useGlobal()
  const frame = useFrame(g.init)
  const enrolled = g.scenario !== 'unenrolled'
  const tabs = enrolled ? ENROLLED_TABS : UNENROLLED_TABS
  useTabFix(frame.tab, enrolled, frame.setTab)

  const idx = Math.max(0, tabs.findIndex(t => t.id === frame.tab))

  return (
    <div className="Phone">
      <div className={'Phone-screen theme-' + g.theme}>
        <div className="FStatus">
          <span className="t-meta-s">17:06</span>
          <span className="FStatus-meta">
            <span style={{
              width: 16, height: 8, borderRadius: 2, border: '1px solid var(--text-3)',
              background: 'linear-gradient(to right, var(--text-3) 78%, transparent 78%)',
            }} />
          </span>
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          {frame.tab === 'hub' ? <HubScreen frame={frame} />
            : frame.tab === 'chats' ? <ChatsScreen enrolled={enrolled} />
              : frame.tab === 'nearby' ? <NearbyScreen />
                : frame.tab === 'transfers' ? <TransfersScreen />
                  : <SettingsScreen enrolled={enrolled} />}
        </div>
        <div className="NavSpace" />
        <nav className="FNav" style={{ background: 'var(--surface)' }}>
          {tabs.map((t, i) => {
            const on = i === idx
            return (
              <button key={t.id}
                className={'NavCell' + (on ? ' is-on' : '')}
                onClick={() => frame.setTab(t.id)}
                aria-label={t.label + ' tab'}>
                {on && <span className="NavChip" style={{ left: `calc(${i} * (25% - 2px) + 4px)` }} />}
                <span className="NavIcon" style={{ position: 'relative' }}>
                  <FlashIcon name={t.icon} />
                  {badgeFor(t.id, enrolled) > 0 && (
                    <span className="NavBadge" style={{ top: -6, right: -9, position: 'absolute' }}>
                      {badgeFor(t.id, enrolled)}
                    </span>
                  )}
                </span>
                <span className="NavLabel">{t.label}</span>
              </button>
            )
          })}
        </nav>
      </div>
    </div>
  )
}

/* =========================== desktop ======================================== */

export function DesktopFrame() {
  const g = useGlobal()
  const frame = useFrame(g.init)
  const enrolled = g.scenario !== 'unenrolled'
  const tabs = enrolled ? ENROLLED_TABS : UNENROLLED_TABS
  useTabFix(frame.tab, enrolled, frame.setTab)

  const detailOpen = frame.tab === 'hub' && !!frame.openId

  return (
    <div className={'Desk theme-' + g.theme}>
      <div className="Desk-body">
        <div className="Rail">
          <div className="RailMedallion" title="Flash Home">
            <span><FlashIcon name="bolt" size={20} /></span>
          </div>
          <div className="RailTabs">
            {tabs.map(t => (
              <button key={t.id} className={'RailTab' + (frame.tab === t.id ? ' is-on' : '')}
                aria-label={t.label + ' tab'} onClick={() => frame.setTab(t.id)}>
                <span className="RailTab-ind" />
                <span className="RailTab-icon">
                  <FlashIcon name={t.icon} />
                  {badgeFor(t.id, enrolled) > 0 && <span className="RailTab-badge">{badgeFor(t.id, enrolled)}</span>}
                </span>
              </button>
            ))}
          </div>
          <div className="RailProfile">{ORG.me[0]}</div>
        </div>
        <div className="RailRule" />
        <div className="Pane-list">
          {frame.tab === 'hub' ? <HubScreen frame={frame} layout="desktop" />
            : frame.tab === 'chats' ? <ChatsScreen enrolled={enrolled} />
              : frame.tab === 'nearby' ? <NearbyScreen />
                : frame.tab === 'transfers' ? <TransfersScreen />
                  : <SettingsScreen enrolled={enrolled} />}
        </div>
        <div className="Pane-detail">
          {detailOpen && frame.openId
            ? <HubDetail id={frame.openId} onClose={() => frame.setOpen(null)} />
            : <DeskDetailPlaceholder active={frame.tab} />}
        </div>
      </div>
    </div>
  )
}

/**
 * Desktop keeps a detail pane always reserved (AD-3 two-pane). With nothing open it
 * shows the same calm empty state the real app shows in an empty detail column.
 */
function DeskDetailPlaceholder({ active }: { active: TabId }) {
  const label = active === 'hub' ? 'Select a person, announcement or group'
    : active === 'chats' ? 'Select a chat'
      : active === 'nearby' ? 'Select a device' : ''
  return (
    <div style={{ flex: 1, display: 'grid', placeItems: 'center' }}>
      <div className="Empty" style={{ padding: 0 }}>
        <span style={{ color: 'var(--text-3)' }}><FlashIcon name="hub" size={28} /></span>
        <span className="Empty-body t-cap">{label}</span>
      </div>
    </div>
  )
}
