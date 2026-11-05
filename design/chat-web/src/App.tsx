import { useState } from 'react'
import { Icon, ICON_SHEET } from './flash/icons'
import { DesktopFrame, PhoneFrame, type Sheet, type Theme } from './flash/Shells'
import { CUSTOM_COLOURS, THREAD, WALLPAPERS, type WallpaperId } from './flash/data'
import { MessageView, wallpaperClass, wallpaperStyle } from './flash/Conversation'
import { Composer } from './flash/Composer'

type View = 'all' | 'phone' | 'desk'

function params() {
  const q = new URLSearchParams(window.location.search)
  const theme = q.get('theme') === 'dark' ? 'dark' : 'light'
  const wallpaper = (WALLPAPERS.find(w => w.id === q.get('wallpaper'))?.id ?? 'signal') as WallpaperId
  const view = (q.get('view') ?? 'all') as View
  const sheet: Sheet = q.get('sheet') === '1' ? 'wallpaper' : 'none'
  const motion = q.get('motion') === 'reduced'
  return { theme: theme as Theme, wallpaper, view, sheet, motion }
}

export default function App() {
  const init = params()
  const [theme, setTheme] = useState<Theme>(init.theme)
  const [wallpaper, setWallpaper] = useState<WallpaperId>(init.wallpaper)
  const [custom, setCustom] = useState(CUSTOM_COLOURS[5].hex)
  const [view, setView] = useState<View>(init.view)
  const [sheet, setSheet] = useState<Sheet>(init.sheet)
  const [sheetBase, setSheetBase] = useState<WallpaperId>(init.wallpaper)
  const [reduced, setReduced] = useState(init.motion)
  const [replay, setReplay] = useState(0)

  const frame = {
    theme, wallpaper, custom, sheet, reduceMotion: reduced,
    onSheet: (s: Sheet) => {
      if (s === 'wallpaper') setSheetBase(wallpaper)
      setSheet(s)
    },
    onPick: (id: WallpaperId) => { setWallpaper(id); setSheet('wallpaper') },
    onCustom: (hex: string) => { setWallpaper('custom'); setCustom(hex); setSheet('wallpaper') },
    onUndo: () => setWallpaper(sheetBase),
    onTheme: setTheme,
    onResetWallpaper: () => setWallpaper('graphite'),
    onReduceMotion: setReduced,
  }

  return (
    <div className="review" data-motion={reduced ? 'reduced' : 'full'}>
      <header className="review-head">
        <h1 className="review-title">Flash chat, redesigned</h1>
        <p className="review-lede">
          The conversation, its composer, its wallpapers and its icon set, rebuilt on the shipped
          tokens: Pulse teal, the 20dp bubble with its scoop tail, 16/22 message text and the
          120/200/320ms motion scale. No behaviour changes; the port swaps paths, colour slots and layout.
        </p>
        <p className="review-meta">
          Design prototype · 5 November 2026 · branch <code>design/chat-redesign</code> · measured in Chrome at 1440, 768 and 390
        </p>
      </header>

      <div className="review-controls" role="group" aria-label="Prototype controls">
        <span className="ctl">
          <span className="ctl-label">Theme</span>
          <span className="c-segmented">
            <button type="button" className={theme === 'light' ? 'is-on' : ''}
              aria-pressed={theme === 'light'} onClick={() => setTheme('light')}>Light</button>
            <button type="button" className={theme === 'dark' ? 'is-on' : ''}
              aria-pressed={theme === 'dark'} onClick={() => setTheme('dark')}>Dark</button>
          </span>
        </span>

        <span className="ctl">
          <span className="ctl-label">Wallpaper</span>
          <span className="ctl-wallpapers">
            {WALLPAPERS.map(w => (
              <button key={w.id} type="button"
                className={'ctl-wall' + (wallpaper === w.id ? ' is-on' : '')}
                aria-pressed={wallpaper === w.id}
                onClick={() => { setWallpaper(w.id); setSheet('none') }}>
                <span className={'ctl-wall-dot ' + wallpaperClass(w.id)} />
                {w.name}
              </button>
            ))}
            <button type="button" className={'ctl-wall' + (wallpaper === 'custom' ? ' is-on' : '')}
              aria-pressed={wallpaper === 'custom'}
              onClick={() => { setWallpaper('custom'); setSheet('none') }}>
              <span className="ctl-wall-dot" style={{ background: custom }} />
              Yours
            </button>
          </span>
        </span>

        <span className="ctl">
          <span className="ctl-label">Frames</span>
          <span className="c-segmented">
            {(['all', 'phone', 'desk'] as View[]).map(v => (
              <button key={v} type="button" className={view === v ? 'is-on' : ''}
                aria-pressed={view === v} onClick={() => setView(v)}>
                {v === 'all' ? 'Both' : v === 'phone' ? 'Phone' : 'Desktop'}
              </button>
            ))}
          </span>
        </span>

        <span className="ctl ctl-last">
          <span className="ctl-label">Motion</span>
          <button type="button" role="switch" aria-checked={!reduced}
            aria-label="Motion enabled" className={'c-switch' + (!reduced ? ' is-on' : '')}
            onClick={() => setReduced(v => !v)} />
        </span>
      </div>

      <main>
        {/* ---------------------------------------------------------- conversation */}
        <section className="review-section" aria-labelledby="s-conversation">
          <div className="review-section-head">
            <h2 id="s-conversation">The conversation</h2>
            <p>
              The header drops to three actions and moves the transport into the subtitle, so the name
              gets room. Messages keep the shipped silhouette: 20dp corners, an 8dp concave scoop on the
              sender’s side of the last bubble in a run, and the time inside the bubble. The wallpaper is
              a field behind the bubbles; no text ever sits on it.
            </p>
          </div>

          <div className="review-frames">
            <figure className="shot">
              <PhoneFrame {...frame} appearance={false} />
              <figcaption>
                <strong>Conversation</strong>
                <span>Open the menu to reach appearance; the composer swaps mic for send as you type.</span>
              </figcaption>
            </figure>

            <figure className="shot">
              <PhoneFrame {...frame} appearance />
              <figcaption>
                <strong>Chat appearance</strong>
                <span>Wallpaper, theme, motion. Opens from the chat menu or a chat’s details.</span>
              </figcaption>
            </figure>
          </div>

          <aside className="review-notes review-notes-wide" aria-label="Anatomy">
            <h3>Anatomy</h3>
            <dl>
              <div><dt>Header</dt><dd>60dp · 34dp avatar · 16/600 name · 12dp presence with a 13dp lock</dd></div>
              <div><dt>Thread</dt><dd>12dp gutters · 2dp inside a run · 16dp between runs · 78% cap</dd></div>
              <div><dt>Bubble</dt><dd>20dp radius · 8dp scoop · 12/8 padding · 16/22 text · 11dp time</dd></div>
              <div><dt>Composer</dt><dd>64dp bar · three objects, 8dp apart · 44dp targets</dd></div>
              <div><dt>Wallpaper</dt><dd>behind the bubbles only · 5 presets + one colour · light and dark variants</dd></div>
              <div><dt>Day chip</dt><dd>11/600 on surface with a hairline, never on the field</dd></div>
            </dl>
            <p className="review-note-body">
              Nothing in this screen changes the wire: the same messages, the same callbacks, the same
              transfer card. The composer’s send still fires the same event; only where it sits changed.
            </p>
          </aside>
        </section>

        {/* ------------------------------------------------------------ message parts */}
        <section className="review-section" aria-labelledby="s-parts">
          <div className="review-section-head">
            <h2 id="s-parts">The message parts</h2>
            <p>
              A photo, a transfer in flight, a verified file, a voice note and a reply, at their real
              size. Each keeps the bubble’s surface, the 12dp inset and the tabular time; the file card
              is a 12dp panel inside the bubble and the voice strip is drawn from the recording’s own
              amplitudes.
            </p>
          </div>

          <div className={'parts-board ' + wallpaperClass(wallpaper)}
            style={wallpaperStyle(wallpaper, custom)}>
            {[
              { id: 'm1', label: 'Photo · 262 wide' },
              { id: 'm5', label: 'Transfer in flight' },
              { id: 'm8', label: 'Verified' },
              { id: 'm7', label: 'Voice · 14s' },
              { id: 'm6', label: 'Reply · outgoing' },
            ].map(spec => {
              const m = THREAD.find(x => x.id === spec.id)!
              return (
                <div key={spec.id} className="part">
                  <span className="part-label">{spec.label}</span>
                  <div className={'c-run ' + m.from}>
                    <MessageView m={m} />
                  </div>
                </div>
              )
            })}
            <div className="part">
              <span className="part-label">Day chip · unread rule</span>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, alignItems: 'center', width: '100%' }}>
                <span className="c-day" style={{ margin: 0 }}>Today</span>
                <span className="c-unread" style={{ width: '100%', margin: 0 }}><span>Unread</span></span>
              </div>
            </div>
          </div>
        </section>

        {/* -------------------------------------------------------------- composer */}
        <section className="review-section" aria-labelledby="s-composer">
          <div className="review-section-head">
            <h2 id="s-composer">The composer</h2>
            <p>
              The text field is no longer a bar with a button at its end. Attach, the input pill and the
              send key are three separate objects, and the trailing slot holds both the mic and the send
              arrow in the same 44dp box, so the swap is a 120ms cross-fade and never a width animation.
            </p>
          </div>

          <div className="review-composer">
            <div className={'composer-stage ' + wallpaperClass(wallpaper)} style={wallpaperStyle(wallpaper, custom)}>
              <div className="composer-stage-inner">
                <div className="c-composer" style={{ borderRadius: 16, borderTop: '1px solid var(--line-soft)' }}>
                  <Composer onSendText={() => { }} onSendVoice={() => { }} />
                </div>
                <div className="c-composer is-specimen" aria-hidden="true"
                  style={{ borderRadius: 16, borderTop: '1px solid var(--line-soft)', marginTop: 14 }}>
                  <div className="c-composer-row">
                    <span className="c-action is-attach"><Icon name="attach" size={22} /></span>
                    <span className="c-recording">
                      <span className="c-rec-dot" />
                      <span className="c-rec-time">0:03</span>
                      <span className="c-rec-hint">Release to send</span>
                    </span>
                    <span className="c-action is-send"><Icon name="check" size={20} /></span>
                  </div>
                </div>
                <p className="specimen-label">Recording replaces the pill in the same 44dp row.</p>
              </div>
              <p className="composer-hint">Type below, then clear it. The mic becomes the send key without moving.</p>
            </div>

            <ul className="review-spec">
              <li><span>Attach</span><span>44dp circle · surface-2 fill · 22dp glyph</span></li>
              <li><span>Pill</span><span>44dp min · 22dp radius · 14dp inset · grows to 6 lines</span></li>
              <li><span>Send / mic</span><span>44dp circle · accent fill when sendable · 20dp glyph</span></li>
              <li><span>Gaps</span><span>8dp between objects · 8dp above and below</span></li>
              <li><span>Bar</span><span>surface · 1dp hairline · ime + navigation insets</span></li>
              <li><span>Reply dock</span><span>3dp accent bar · 12/600 name · 12 snippet · 32dp close</span></li>
              <li><span>Recording</span><span>10dp red dot · tabular timer · 32dp cancel, same 44dp row</span></li>
            </ul>
          </div>
        </section>

        {/* ------------------------------------------------------------- wallpapers */}
        <section className="review-section" aria-labelledby="s-wallpaper">
          <div className="review-section-head">
            <h2 id="s-wallpaper">Wallpaper</h2>
            <p>
              Five fields and one colour of your own. Each preset has a light and a dark variant, and the
              pattern is drawn at 5–9% so it reads as texture at arm’s length and disappears behind a
              bubble. Selecting a dark wallpaper does not dim the text: the bubbles carry their own
              surface in both themes.
            </p>
          </div>

          <div className="wall-gallery">
            {WALLPAPERS.map(w => (
              <button key={w.id} type="button"
                className={'wall-card' + (wallpaper === w.id ? ' is-on' : '')}
                onClick={() => setWallpaper(w.id)} aria-pressed={wallpaper === w.id}>
                <span className={'wall-card-field ' + wallpaperClass(w.id)}>
                  <span className="w-bubble in">Checksum matches here too.</span>
                  <span className="w-bubble out" style={{ alignSelf: 'flex-end' }}>Archive both.</span>
                </span>
                <span className="wall-card-name">{w.name}<span>{w.note}</span></span>
              </button>
            ))}
            <button type="button"
              className={'wall-card' + (wallpaper === 'custom' ? ' is-on' : '')}
              onClick={() => setWallpaper('custom')} aria-pressed={wallpaper === 'custom'}>
              <span className="wall-card-field" style={{ background: custom }}>
                <span className="w-bubble in">One colour.</span>
                <span className="w-bubble out" style={{ alignSelf: 'flex-end' }}>No pattern.</span>
              </span>
              <span className="wall-card-name">Yours<span>Chosen from eight muted colours.</span></span>
            </button>
          </div>

          <div className="review-notes review-notes-wide">
            <h3>Rules the field keeps</h3>
            <dl>
              <div><dt>Never under text</dt><dd>Every message, time and day chip sits on an opaque surface with its own contrast.</dd></div>
              <div><dt>Two variants</dt><dd>The same preset exists light and dark, so a wallpaper is not a theme switch.</dd></div>
              <div><dt>One setting</dt><dd>Per chat, with “All chats” as the same sheet’s second option.</dd></div>
            </dl>
          </div>
        </section>

        {/* ------------------------------------------------------------------ desktop */}
        {view !== 'phone' && (
          <section className="review-section" aria-labelledby="s-desktop">
            <div className="review-section-head">
              <h2 id="s-desktop">Desktop</h2>
              <p>
                The same conversation in the shipped Windows shell: 68dp rail, 320dp list, the sheet
                holding the chat. The composer centres its three objects on an 820dp measure so a wide
                window does not stretch a text field across the screen.
              </p>
            </div>
            <div className="review-desk">
              <DesktopFrame {...frame} />
            </div>
          </section>
        )}

        {/* ------------------------------------------------------------------- icons */}
        <section className="review-section" aria-labelledby="s-icons">
          <div className="review-section-head">
            <h2 id="s-icons">The icon set</h2>
            <p>
              Fifteen chat glyphs redrawn on the same 24dp grid. The shipped set strokes at 2dp, which sits
              heavy against 16/22 text and heavier still at 20dp inside the composer; these draw at 1.75dp
              with round caps and one deliberate asymmetry each — the cut nose of the send dart, the flat
              mouthpiece of the handset, the glint inside the camera lens. Names and meanings do not change,
              so the Compose port replaces path data only.
            </p>
          </div>

          <div className="icon-grid">
            {ICON_SHEET.map(i => (
              <div key={i.name} className="icon-cell">
                <span className="icon-cell-glyph"><Icon name={i.name} size={26} /></span>
                <span className="icon-cell-name">{i.label}</span>
                <span className="icon-cell-note">{i.note}</span>
              </div>
            ))}
          </div>

          <div className="icon-strip">
            <div className="icon-strip-dark" data-theme="dark">
              <span>On the dark surface</span>
              <span className="icon-strip-row">
                {ICON_SHEET.slice(0, 8).map(i => <Icon key={i.name} name={i.name} size={22} />)}
              </span>
            </div>
            <p>
              Optical size is 24dp everywhere: 22dp in the header and composer, 20dp inside the send
              circle, 14dp for ticks and 13dp for the lock. Stroke width is constant at 1.75dp, so a
              22dp glyph does not thin out.
            </p>
          </div>
        </section>

        {/* ------------------------------------------------------------------ motion */}
        <section className="review-section" aria-labelledby="s-motion">
          <div className="review-section-head">
            <h2 id="s-motion">Motion</h2>
            <p>
              Transform and opacity only; no height, width or margin is animated anywhere. The durations
              are the shipped tokens, and every one has a reduced-motion answer that keeps the change and
              drops the travel.
            </p>
          </div>

          <div className="review-motion" key={replay}>
            <div className="motion-demo">
              <span className="motion-demo-label">Mic → send</span>
              <span className="motion-slot">
                <span className="motion-mic"><Icon name="mic" size={22} /></span>
                <span className="motion-send"><Icon name="send" size={20} /></span>
              </span>
              <span className="motion-demo-time">120ms · out 0,0,.2,1</span>
            </div>
            <div className="motion-demo">
              <span className="motion-demo-label">Message arrives</span>
              <span className="motion-bubble">Checksum matches here too.</span>
              <span className="motion-demo-time">180ms · 6dp rise, 1.5% scale</span>
            </div>
            <div className="motion-demo">
              <span className="motion-demo-label">Sheet rises</span>
              <span className="motion-sheet" />
              <span className="motion-demo-time">320ms · 16dp · .32,.72,0,1</span>
            </div>
            <button type="button" className="c-secondary motion-replay"
              onClick={() => setReplay(n => n + 1)}>
              Replay
            </button>
          </div>

          <table className="motion-table">
            <thead>
              <tr><th scope="col">Element</th><th scope="col">Change</th><th scope="col">Token</th><th scope="col">Reduced motion</th></tr>
            </thead>
            <tbody>
              <tr><td>Mic → send</td><td>opacity + scale .7 → 1, both glyphs in one slot</td><td>120ms Decelerate</td><td>instant swap</td></tr>
              <tr><td>New message</td><td>opacity, 6dp rise, 1.5% scale</td><td>200ms Decelerate</td><td>no rise, no scale</td></tr>
              <tr><td>Wallpaper sheet</td><td>opacity + 16dp rise</td><td>320ms sheet</td><td>appears</td></tr>
              <tr><td>Press</td><td>scale .94 on header, .97 on bubbles</td><td>120ms out</td><td>no scale</td></tr>
              <tr><td>Typing dots</td><td>opacity 1.2s loop, 2dp rise</td><td>loop</td><td>static at 60%</td></tr>
            </tbody>
          </table>
        </section>
      </main>

      <footer className="review-foot">
        <p>
          Prototype only: no Gradle wiring, no network, no app code changed. Photo:
          <a href="https://commons.wikimedia.org/w/index.php?curid=77688219">“Sivas Buruciye Medrese 115”</a>
          by Dosseman, CC BY-SA 4.0, via Wikimedia Commons — the thread is sample content.
        </p>
        <p>
          Files: <code>design/chat-web/src/flash/</code> · design record <code>design/chat-web/DESIGN.md</code>
        </p>
      </footer>
    </div>
  )
}
