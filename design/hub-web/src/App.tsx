/**
 * Review page chrome. This is NOT the app design — it is the board the two real
 * shell mocks sit on, with the state controls the doc asks to see:
 * both hosts side by side (Android bottom nav, desktop sidebar), one scenario and
 * theme toggle each. Everything inside the frames is the deliverable.
 */
import { PhoneFrame, DesktopFrame } from './flash/shells'
import { GlobalProvider, SCENARIOS, useGlobal, type Scenario, type ViewMode } from './flash/state'
import { FlashIcon } from './flash/icons'

export default function App() {
  return (
    <GlobalProvider>
      <Page />
    </GlobalProvider>
  )
}

function Page() {
  const g = useGlobal()
  return (
    <div className="Page">
      <div className="Page-top">
        <div>
          <div className="Page-headline">
            <span style={{ color: '#1FB8A6' }}><FlashIcon name="bolt" size={18} /></span>
            <span className="t-h2">Flash — Hub tab redesign</span>
          </div>
          <div className="Page-caption mono-note" style={{ marginTop: 6 }}>
            One Hub tab, both hosts. Left: Android (bottom nav UI-046). Right: desktop (68dp sidebar).
            Tokens, type, shapes and icons are copied from the Compose design system — see src/index.css.
          </div>
        </div>
        <div className="Page-controls">
          <PillGroup label="App state">
            {SCENARIOS.filter(s => s.id !== 'loading').map(s => (
              <Pill key={s.id} label={s.label} active={g.scenario === s.id}
                onClick={() => g.setScenario(s.id as Scenario)} />
            ))}
          </PillGroup>
          <PillGroup label="Theme">
            <Pill label="Dark" active={g.theme === 'dark'} onClick={() => g.setTheme('dark')} />
            <Pill label="Light" active={g.theme === 'light'} onClick={() => g.setTheme('light')} />
          </PillGroup>
          <PillGroup label="Visible">
            <Pill label="Both" active={g.view === 'both'} onClick={() => g.setView('both' as ViewMode)} />
            <Pill label="Phone" active={g.view === 'phone'} onClick={() => g.setView('phone')} />
            <Pill label="Desktop" active={g.view === 'desktop'} onClick={() => g.setView('desktop')} />
          </PillGroup>
        </div>
      </div>
      <div className="Stage">
        {(g.view === 'both' || g.view === 'phone') && <PhoneFrame />}
        {(g.view === 'both' || g.view === 'desktop') && <DesktopFrame />}
      </div>
    </div>
  )
}

function PillGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="PillGroup">
      <span className="PillGroup-label">{label}</span>
      {children}
    </div>
  )
}

function Pill({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button className={'Pill' + (active ? ' is-on' : '')} onClick={onClick}>{label}</button>
  )
}
