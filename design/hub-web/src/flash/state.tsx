/**
 * Two stores:
 *  - GlobalStore: review-page controls (scenario, theme, which frames are visible).
 *  - useFrame(): one device's UI state (selected tab, hub segment, search, selection).
 *
 * `?state=offline&theme=light&view=phone&segment=people&open=p3` is supported so the
 * screenshot harness and review links can pin a view without clicking.
 */
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

export type Theme = 'dark' | 'light'

export type Scenario = 'fresh' | 'offline' | 'never' | 'revoked' | 'unenrolled' | 'loading'
export type ViewMode = 'both' | 'phone' | 'desktop'
export type TabId = 'chats' | 'transfers' | 'hub' | 'nearby' | 'settings'
export type SegmentId = 'news' | 'people' | 'groups'

interface GlobalShape {
  theme: Theme
  scenario: Scenario
  view: ViewMode
  /** URL-pinned initial state for a Hub frame: `?segment=people&open=p3`. */
  init: { segment?: SegmentId; open?: string }
  setTheme: (t: Theme) => void
  setScenario: (s: Scenario) => void
  setView: (v: ViewMode) => void
}

const GlobalCtx = createContext<GlobalShape | null>(null)

/** Scenario labels come from the state table in docs/ui/flash-hub-tab.md. */
export const SCENARIOS: { id: Scenario; label: string }[] = [
  { id: 'fresh', label: 'Fresh' },
  { id: 'offline', label: 'Offline · cached' },
  { id: 'never', label: 'Never synced' },
  { id: 'loading', label: 'Loading' },
  { id: 'revoked', label: 'Removed' },
  { id: 'unenrolled', label: 'Not enrolled' },
]

function readParams(): { theme: Theme; scenario: Scenario; view: ViewMode; segment?: SegmentId; open?: string } {
  const q = new URLSearchParams(location.search)
  const theme = q.get('theme') === 'light' ? 'light' : 'dark'
  const view = (q.get('view') as ViewMode) || 'both'
  const scenarioIn = q.get('state')
  const scenario: Scenario =
    SCENARIOS.some(s => s.id === scenarioIn) ? (scenarioIn as Scenario)
      : q.get('enrolled') === '0' ? 'unenrolled' : 'fresh'
  const seg = q.get('segment')
  const segment: SegmentId | undefined =
    seg === 'people' || seg === 'groups' || seg === 'news' ? seg : undefined
  const open = q.get('open') || undefined
  return { theme, scenario, view, segment, open }
}

export function GlobalProvider({ children }: { children: ReactNode }) {
  const p = useMemo(readParams, [])
  const [theme, setTheme] = useState<Theme>(p.theme)
  const [scenario, setScenario] = useState<Scenario>(p.scenario)
  const [view, setView] = useState<ViewMode>(p.view)
  return (
    <GlobalCtx.Provider value={{ theme, scenario, view, init: { segment: p.segment, open: p.open }, setTheme, setScenario, setView }}>
      {children}
    </GlobalCtx.Provider>
  )
}

export function useGlobal(): GlobalShape {
  const g = useContext(GlobalCtx)
  if (!g) throw new Error('GlobalProvider missing')
  return g
}

/* ---------- one device frame -------------------------------------------------- */

export interface Frame {
  tab: TabId
  segment: SegmentId
  query: string
  searchOpen: boolean
  /** person id or announcement id currently open in a detail pane / sheet */
  openId: string | null
  composerOpen: boolean
  deptFilter: string | null
  setTab: (t: TabId) => void
  setSegment: (s: SegmentId) => void
  setQuery: (q: string) => void
  setSearchOpen: (b: boolean) => void
  setOpen: (id: string | null) => void
  setComposer: (b: boolean) => void
  setDeptFilter: (d: string | null) => void
}

export function useFrame(initial?: { segment?: SegmentId; open?: string }): Frame {
  const [tab, setTab] = useState<TabId>('hub')
  const [segment, setSegment] = useState<SegmentId>(initial?.segment ?? 'news')
  const [query, setQuery] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [openId, setOpen] = useState<string | null>(initial?.open ?? null)
  const [composerOpen, setComposer] = useState(false)
  const [deptFilter, setDeptFilter] = useState<string | null>(null)
  useEffect(() => { if (initial?.segment) setSegment(initial.segment) }, [initial?.segment])
  useEffect(() => { if (initial?.open) setOpen(initial.open) }, [initial?.open])
  return {
    tab, segment, query, searchOpen, openId, composerOpen, deptFilter,
    setTab, setSegment, setQuery, setSearchOpen, setOpen, setComposer, setDeptFilter,
  }
}
