/**
 * The redesigned Hub tab (UI-058, web mock).
 *
 * What changed against the shipped 2026-10-20 design (the "too much information"
 * brief):
 *   1. Four segments become three: News · People · Groups. Departments stop being
 *      their own segment — they are filter chips inside People, so the data is
 *      still reachable but there is one list screen less to navigate.
 *   2. The gradient hero (stat tiles, count-ups, trust ring, animated dot) is gone.
 *      One calm org row answers "am I in the right place, is it connected":
 *      monogram, name + live dot, counts line. Trust state lives in the strip.
 *   3. Rows and cards keep the 2026-10-20 "soft layered" shapes (16dp cards,
 *      12dp controls, 6dp chips, hairline underline tabs) but lose the per-card
 *      edge washes, department share bars and per-section chip rows.
 *
 * Every user-facing sentence is `FlashHubCopy.kt` text; see data.ts COPY.
 *
 * Layout contracts (docs/ui/flash-hub-tab.md):
 *   - phone:  top bar 56dp, content max 640dp, details open as bottom sheets
 *   - desktop: list pane (Hub) + detail pane on the right; same components.
 */
import { useLayoutEffect, useRef, useState } from 'react'
import { FlashIcon } from './icons'
import { Avatar } from './atoms'
import {
  type Frame, type SegmentId, useGlobal,
} from './state'
import {
  COPY, DEPARTMENTS, GROUPS, NEWS, ORG, PEOPLE,
  type Announcement, type Person,
} from './data'

const SEGMENTS: { id: SegmentId; label: string }[] = [
  { id: 'news', label: 'News' },
  { id: 'people', label: 'People' },
  { id: 'groups', label: 'Groups' },
]

/** Read-marking lives here only: a mock does not need the Room-backed store. */
const readIds = new Set<string>()

/* =========================== screen ======================================== */

export function HubScreen({ frame, layout = 'phone' }: { frame: Frame; layout?: 'phone' | 'desktop' }) {
  const { scenario, setScenario } = useGlobal()

  if (scenario === 'revoked') return <RevokedNotice />

  return (
    <div className="HubBody">
      <HubTopBar frame={frame} />
      <OrgRow connected={scenario === 'fresh'} counts={scenario !== 'never'} />

      {scenario === 'offline' && (
        <div className="Strip Strip-neutral">
          <FlashIcon name="clock" size={16} />
          <span className="Strip-sentence">{COPY.stripOfflineCached('12 min')}</span>
        </div>
      )}
      {scenario === 'never' && (
        <div className="Strip Strip-caution">
          <FlashIcon name="connection" size={16} />
          <span className="Strip-sentence">{COPY.stripLease}</span>
        </div>
      )}

      <SegmentTabs frame={frame} unread={NEWS.filter(a => a.unread && !readIds.has(a.id)).length} />

      {scenario === 'loading' ? <HubSkeleton /> : scenario === 'never' ? (
        <NeverSynced />
      ) : frame.searchOpen ? (
        <SearchResults frame={frame} />
      ) : frame.segment === 'news' ? (
        <NewsList frame={frame} />
      ) : frame.segment === 'people' ? (
        <PeopleList frame={frame} />
      ) : (
        <GroupsList frame={frame} />
      )}

      {scenario === 'never' && (
        <HubRetry setScenario={setScenario} />
      )}

      {frame.composerOpen && (
        <Composer onClose={() => frame.setComposer(false)} />
      )}
      {/* phone: details open as bottom sheets; desktop: the right detail pane hosts them */}
      {layout === 'phone' && <HubSheets frame={frame} />}
    </div>
  )
}

/* =========================== chrome ======================================== */

function HubTopBar({ frame }: { frame: Frame }) {
  if (frame.searchOpen) {
    return (
      <div className="TopBar">
        <button className="IconBtn" onClick={() => { frame.setSearchOpen(false); frame.setQuery('') }}>
          <FlashIcon name="back" />
        </button>
        <div className="SelectedRow">
          <input
            className="SelectedRow-input" autoFocus value={frame.query}
            placeholder={COPY.searchHint}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                const hit = PEOPLE.find(p => !p.you && p.name.toLowerCase().includes(frame.query.toLowerCase()))
                if (hit) { frame.setSearchOpen(false); frame.setOpen(hit.id) }
              }
              if (e.key === 'Escape') { frame.setSearchOpen(false); frame.setQuery('') }
            }}
            onChange={e => frame.setQuery(e.target.value)}
          />
        </div>
      </div>
    )
  }
  return (
    <div className="TopBar">
      <div className="TopBar-title t-h2">Hub</div>
      <button className="IconBtn" aria-label="Search" onClick={() => frame.setSearchOpen(true)}>
        <FlashIcon name="search" />
      </button>
    </div>
  )
}

/** The whole "hero" is one line now: who, connected?, how much. */
function OrgRow({ connected, counts }: { connected: boolean; counts: boolean }) {
  return (
    <div className="OrgRow">
      <span className="OrgMark">{ORG.monogram}</span>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span className="OrgLine1">
          <span className="t-body-s">{ORG.name}</span>
          <span className={connected ? 'OrgDot' : 'OrgDot is-offline'} />
        </span>
        <span className="OrgCounts t-meta">
          {counts ? COPY.countsLine(ORG.members, ORG.departments, ORG.groups) : 'Not loaded yet'}
        </span>
      </div>
    </div>
  )
}

/** Text-on-a-hairline tabs; the 3dp capsule underline glides (HubShapes). */
function SegmentTabs({ frame, unread }: { frame: Frame; unread: number }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  const [box, setBox] = useState({ left: 16, width: 40 })
  useLayoutEffect(() => {
    const i = SEGMENTS.findIndex(s => s.id === frame.segment)
    const el = refs.current[i]
    if (el) setBox({ left: el.offsetLeft + 2, width: el.offsetWidth - 4 })
  }, [frame.segment, unread])
  return (
    <div className="SegTrack-wrap">
      <div className="SegTrack" role="tablist">
        {SEGMENTS.map((s, i) => (
          <button
            key={s.id} ref={el => { refs.current[i] = el }} role="tab"
            aria-selected={frame.segment === s.id}
            className={'Seg t-body-s' + (frame.segment === s.id ? ' is-on' : '')}
            onClick={() => frame.setSegment(s.id)}
          >
            {s.label}
            {s.id === 'news' && unread > 0 && <span className="Seg-dot" />}
          </button>
        ))}
        <span className="Seg-underline" style={{ left: box.left, width: box.width }} />
      </div>
    </div>
  )
}

/* =========================== News ========================================== */

function NewsList({ frame }: { frame: Frame }) {
  const pinned = NEWS.filter(a => a.pinned)
  const latest = NEWS.filter(a => !a.pinned)
  const canPost = ORG.myRoleChip === 'Head' || ORG.myRoleChip === 'Admin'
  return (
    <div className="Hub-scroll">
      {canPost ? (
        <button className="BtnText" style={{ margin: '10px 0 2px', padding: '10px 12px' }}
          onClick={() => frame.setComposer(true)}>
          <FlashIcon name="announcement" size={16} />&nbsp;{COPY.shareUpdate}
        </button>
      ) : (
        <div className="t-meta" style={{ color: 'var(--text-3)', padding: '12px 2px 0' }}>
          {COPY.membersCantPost}
        </div>
      )}

      {pinned.length > 0 && <SectionLabel icon="pin">{COPY.pinned}</SectionLabel>}
      {pinned.map(a => <NewsCard key={a.id} a={a} frame={frame} />)}

      <SectionLabel>{COPY.latest}</SectionLabel>
      {latest.map(a => <NewsCard key={a.id} a={a} frame={frame} />)}

      <div className="t-meta" style={{ color: 'var(--text-3)', padding: '14px 2px 0' }}>
        {COPY.annHonesty}
      </div>
    </div>
  )
}

function SectionLabel({ children, icon }: { children: React.ReactNode; icon?: 'pin' }) {
  return (
    <div className="SectionLabel">
      {icon && <FlashIcon name={icon} size={12} />}
      {children}
    </div>
  )
}

function NewsCard({ a, frame }: { a: Announcement; frame: Frame }) {
  const unread = a.unread && !readIds.has(a.id)
  return (
    <button className={'Card NewsCard' + (a.pinned ? ' is-pinned' : '')}
      style={{ marginBottom: 8, textAlign: 'left' }}
      onClick={() => { readIds.add(a.id); frame.setOpen(a.id) }}>
      <div className="NewsCard-top">
        <span className="Chip">{a.scope === 'Department' ? a.dept : a.scope}</span>
        <span className="t-meta" style={{ color: 'var(--text-3)' }}>{a.ago}</span>
        {unread && <span className="UnreadDot" style={{ marginLeft: 'auto' }} />}
      </div>
      <div className="NewsTitle t-body-s">{a.title}</div>
      <div className="NewsBody NewsHead t-cap">{a.body}</div>
      <div className="NewsFoot">
        <Avatar name={a.author} size="sm" />
        <span className="t-meta">{a.author}</span>
        <span className="Chip Chip-accent">{a.authorRole}</span>
      </div>
    </button>
  )
}

/* =========================== People ======================================== */

function PeopleList({ frame }: { frame: Frame }) {
  const { scenario } = useGlobal()
  const chips = ['All', 'My department', ...DEPARTMENTS.filter(d => d !== 'Engineering')]
  const filtered = PEOPLE.filter(p => {
    const d =
      frame.deptFilter === null || frame.deptFilter === 'All' ? true
        : frame.deptFilter === 'My department' ? p.dept === 'Engineering'
          : p.dept === frame.deptFilter
    return d
  })
  return (
    <div>
      <div className="FilterChips">
        {chips.map(c => (
          <button key={c}
            className={'FilterChip' + ((frame.deptFilter ?? 'All') === c ? ' is-on' : '')}
            onClick={() => frame.setDeptFilter(c === 'All' ? null : c)}>
            {c}
          </button>
        ))}
      </div>
      {filtered.map(p => (
        <PersonRow key={p.id} p={p} frame={frame} cached={scenario === 'offline'} />
      ))}
      {filtered.length === 0 && (
        <div className="Empty"><span className="Empty-title t-body">No colleagues in this department.</span></div>
      )}
    </div>
  )
}

function PersonRow({ p, frame, cached }: { p: Person; frame: Frame; cached: boolean }) {
  const presence = cached || p.online === 'unknown' ? 'unknown' : p.online === 'online'
  return (
    <button
      className={'PersonRow' + (frame.openId === p.id ? ' is-sel' : '')}
      onClick={() => frame.setOpen(p.id)}>
      <Avatar name={p.name} size="md" />
      <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
        <span className="PersonName t-body">
          {p.name}
          {p.you && <span className="t-meta" style={{ color: 'var(--text-3)' }}>{COPY.you}</span>}
        </span>
        <span className="PersonSub">
          {p.dept}
          {p.role && <>&nbsp;·&nbsp;{p.role === 'Admin' ? COPY.roleAdmin : COPY.roleHead}</>}
        </span>
      </span>
      {/* D2: withheld / unknown availability is hidden entirely — no dot, not a grey dot. */}
      {presence === 'unknown' ? null : (
        <span className="Presence">
          <span className={presence ? 'PDot PDot-on' : 'PDot PDot-off'} />
          <span className="t-meta">{presence ? 'Online' : 'Offline'}</span>
        </span>
      )}
    </button>
  )
}

/* =========================== Groups ======================================== */

function GroupsList({ frame }: { frame: Frame }) {
  const yours = GROUPS.filter(g => g.joined)
  const other = GROUPS.filter(g => !g.joined)
  return (
    <div className="Hub-scroll">
      <SectionLabel>{COPY.groupsYours}</SectionLabel>
      {yours.map(g => (
        <button key={g.id} className="GroupRow" style={{ width: '100%', textAlign: 'left' }}
          onClick={() => frame.setOpen(g.id)}>
          <GroupAvatar name={g.name} />
          <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="GroupName t-body-s">{g.name}</span>
            <span className="GroupSub">{g.members} people{g.dept ? ` · ${g.dept}` : ''}</span>
          </span>
        </button>
      ))}
      <SectionLabel>{COPY.groupsMine}</SectionLabel>
      {other.map(g => (
        <div key={g.id} className="GroupRow" style={{ width: '100%' }}>
          <GroupAvatar name={g.name} />
          <span style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="GroupName t-body-s">{g.name}</span>
            <span className="GroupSub">{g.members} people{g.dept ? ` · ${g.dept}` : ''}</span>
          </span>
          <span style={{ marginLeft: 'auto' }}>
            {g.pending
              ? <span className="t-meta" style={{ color: 'var(--text-3)' }}>{COPY.pending}</span>
              : <button className="BtnText">{COPY.join}</button>}
          </span>
        </div>
      ))}
    </div>
  )
}

function GroupAvatar({ name }: { name: string }) {
  // Group avatars: soft accent tile with the group glyph (matches the department
  // monogram treatment; person avatars stay the palette circles of FlashAvatar).
  return (
    <span style={{
      width: 44, height: 44, flex: 'none', borderRadius: 13,
      background: 'var(--accent-dim)', color: 'var(--accent)',
      display: 'grid', placeItems: 'center',
    }} title={name}>
      <FlashIcon name="group" size={22} />
    </span>
  )
}

/* =========================== search ======================================== */

function SearchResults({ frame }: { frame: Frame }) {
  const q = frame.query.trim().toLowerCase()
  const empty = q === ''
  const people = empty ? [] : PEOPLE.filter(p => !p.you && (p.name.toLowerCase().includes(q) || p.dept.toLowerCase().includes(q)))
  const news = empty ? [] : NEWS.filter(a => a.title.toLowerCase().includes(q) || a.body.toLowerCase().includes(q))
  const groups = empty ? [] : GROUPS.filter(g => g.name.toLowerCase().includes(q))
  if (empty) return <div className="SearchIntro t-cap">{COPY.searchHint}</div>
  return (
    <div className="Hub-scroll">
      {people.length > 0 && <SectionLabel>People</SectionLabel>}
      {people.map(p => (
        <button key={p.id} className="SearchRow" onClick={() => { frame.setSearchOpen(false); frame.setOpen(p.id) }}>
          <Avatar name={p.name} size="sm" />
          <span className="t-body">{p.name}</span>
          <span className="t-meta" style={{ color: 'var(--text-3)' }}>{p.dept}</span>
        </button>
      ))}
      {news.length > 0 && <SectionLabel>News</SectionLabel>}
      {news.map(a => (
        <button key={a.id} className="SearchRow" onClick={() => { frame.setSearchOpen(false); readIds.add(a.id); frame.setOpen(a.id) }}>
          <FlashIcon name="announcement" size={16} />
          <span className="t-body" style={{ minWidth: 0 }}>{a.title}</span>
        </button>
      ))}
      {groups.length > 0 && <SectionLabel>Groups</SectionLabel>}
      {groups.map(g => (
        <button key={g.id} className="SearchRow" onClick={() => { frame.setSearchOpen(false); frame.setOpen(g.id) }}>
          <GroupAvatar name={g.name} />
          <span className="t-body">{g.name}</span>
        </button>
      ))}
      {people.length + news.length + groups.length === 0 && (
        <div className="Empty">
          <span className="Empty-title t-body">No one named “{frame.query}”</span>
          <span className="Empty-body t-cap">Type a name, a department or a word from an announcement.</span>
        </div>
      )}
    </div>
  )
}

/* =========================== states ======================================== */

function HubSkeleton() {
  return (
    <div style={{ padding: '8px 0' }}>
      {[0, 1, 2, 3, 4].map(i => (
        <div key={i} className="SkelRow">
          <div className="Skel" style={{ width: 44, height: 44, borderRadius: 999, flex: 'none' }} />
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div className="Skel" style={{ width: `${38 + i * 9}%`, height: 13 }} />
            <div className="Skel" style={{ width: `${20 + i * 5}%`, height: 10 }} />
          </div>
        </div>
      ))}
    </div>
  )
}

function NeverSynced() {
  return (
    <div className="Empty" style={{ paddingTop: 64 }}>
      <span style={{ color: 'var(--text-3)', marginBottom: 6 }}><FlashIcon name="connection" size={28} /></span>
      <span className="Empty-title t-body" style={{ maxWidth: 280 }}>{COPY.neverTitle}</span>
      <span className="Empty-body t-cap" style={{ maxWidth: 260 }}>{COPY.neverBody}</span>
    </div>
  )
}

function HubRetry({ setScenario }: { setScenario: (s: 'loading' | 'fresh') => void }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', paddingBottom: 24 }}>
      <button className="Btn Btn-primary" style={{ minWidth: 160 }}
        onClick={() => { setScenario('loading'); setTimeout(() => setScenario('fresh'), 1100) }}>
        {COPY.retry}
      </button>
    </div>
  )
}

function RevokedNotice() {
  return (
    <div style={{ flex: 1, display: 'flex', flexDirection: 'column', background: 'var(--bg-page)', overflowY: 'auto' }}>
      <div className="TopBar"><div className="TopBar-title t-h2">Hub</div></div>
      <div className="Notice">
        <span style={{ color: 'var(--text-3)', padding: '40px 0 4px', display: 'inline-block' }}>
          <FlashIcon name="group" size={28} />
        </span>
        <div className="Card NoticeCard" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <span className="NoticeCard-title t-body-s">{COPY.revokTitle}</span>
          <span className="t-cap" style={{ color: 'var(--text-2)' }}>{COPY.revokBody}</span>
          <button className="Btn Btn-quiet" style={{ marginTop: 6, color: 'var(--error)' }}>
            {COPY.signOut}
          </button>
        </div>
      </div>
    </div>
  )
}

/* =========================== details: person / announcement / group ======== */

/** Resolves the open id to a detail body; shared by the phone sheet and the desktop pane. */
export function HubDetail({ id, onClose }: { id: string; onClose: () => void }) {
  if (id.startsWith('p')) {
    const p = PEOPLE.find(x => x.id === id)
    return p ? <PersonDetail p={p} onClose={onClose} /> : null
  }
  if (id.startsWith('a')) {
    const a = NEWS.find(x => x.id === id)
    return a ? <NewsDetail a={a} onClose={onClose} /> : null
  }
  return null
}

function PanedTop({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="TopBar" style={{ flex: 'none' }}>
      <button className="IconBtn" onClick={onClose} aria-label="Close"><FlashIcon name="close" /></button>
      <div className="t-meta" style={{ color: 'var(--text-3)' }}>{title}</div>
    </div>
  )
}

function PersonDetail({ p, onClose }: { p: Person; onClose: () => void }) {
  const lanReachable = p.paired && p.online === 'online'
  const reach = p.online === 'online'
    ? lanReachable ? COPY.reachLan : COPY.reachHub
    : p.online === 'offline' ? COPY.reachOff : 'Availability unknown'
  const reachCls = p.online === 'online' ? 'Reach Reach-lan' : 'Reach Reach-off'
  const self = !!p.you
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100%' }}>
      <PanedTop title={p.dept} onClose={onClose} />
      <div className="PersonHero">
        <Avatar name={p.name} size="lg" />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <span className="t-h2" style={{ color: 'var(--text)' }}>
            {p.name}{p.you && <span className="t-meta" style={{ color: 'var(--text-3)', marginLeft: 6 }}>{COPY.you}</span>}
          </span>
          <span className="t-meta" style={{ color: 'var(--text-3)' }}>
            {p.dept} · {p.role === 'Admin' ? 'Administrator' : p.role === 'Head' ? 'Department head' : 'Member'}
          </span>
        </div>
      </div>
      <div className={reachCls}>
        <FlashIcon name={lanReachable ? 'connection' : 'device'} size={16} />
        {reach}
      </div>
      {!self && (
        <div className="PersonActions">
          <button className="Btn Btn-primary">{COPY.message}</button>
          {lanReachable && <button className="Btn Btn-quiet">{COPY.call}</button>}
        </div>
      )}
      {self && (
        <div className="PersonActions">
          <button className="Btn Btn-quiet" disabled>That is you</button>
        </div>
      )}
      {!p.paired && !self && (
        <div className="Honesty">
          <FlashIcon name="connection" size={13} />
          <span>{COPY.honestyFooter}</span>
        </div>
      )}
    </div>
  )
}

function NewsDetail({ a, onClose }: { a: Announcement; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100%' }}>
      <PanedTop title={a.scope === 'Department' ? `${a.dept} · News` : 'Whole company · News'} onClose={onClose} />
      <div className="Reader">
        <div className="NewsFoot" style={{ gap: 8 }}>
          <Avatar name={a.author} size="sm" />
          <span className="t-cap-s">{a.author}</span>
          <span className="Chip Chip-accent">{a.authorRole}</span>
          <span className="t-meta" style={{ color: 'var(--text-3)', marginLeft: 'auto' }}>{a.ago}</span>
        </div>
        <div className="t-h1" style={{ color: 'var(--text)' }}>{a.title}</div>
        {a.body.split('\n').map((p, i) => <p key={i}>{p}</p>)}
        <div className="t-meta" style={{ color: 'var(--text-3)', marginTop: 'auto', paddingTop: 16 }}>
          {COPY.annHonesty}
        </div>
      </div>
    </div>
  )
}

/* =========================== composer ====================================== */

function Composer({ onClose }: { onClose: () => void }) {
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const filled = title.trim() !== '' && body.trim() !== ''
  return (
    <div className="Scrim" onClick={onClose}>
      <div className="Sheet" role="dialog" aria-label="Share an update" onClick={e => e.stopPropagation()}>
        <div className="Composer">
          <div className="t-h3" style={{ color: 'var(--text)' }}>{COPY.shareUpdate}</div>
          <div className="t-meta" style={{ color: 'var(--text-3)' }}>{COPY.composerSubDept}</div>
          <div className="ComposerField"><input placeholder="Title" value={title} onChange={e => setTitle(e.target.value)} /></div>
          <div className="ComposerField"><textarea placeholder="Write the announcement" value={body} onChange={e => setBody(e.target.value)} /></div>
          <div style={{ display: 'flex', gap: 10, marginTop: 4 }}>
            <button className="Btn Btn-quiet" style={{ flex: 1 }} onClick={onClose}>Cancel</button>
            <button className="Btn Btn-primary" style={{ flex: 1 }} disabled={!filled}
              onClick={onClose}>Post</button>
          </div>
        </div>
      </div>
    </div>
  )
}

/** Bottom sheets hosting the same detail bodies used by the desktop pane. */
function HubSheets({ frame }: { frame: Frame }) {
  if (!frame.openId) return null
  return (
    <div className="Scrim" onClick={() => frame.setOpen(null)}>
      <div className="Sheet" onClick={e => e.stopPropagation()}>
        <HubDetail id={frame.openId} onClose={() => frame.setOpen(null)} />
        <div style={{ height: 16 }} />
      </div>
    </div>
  )
}
