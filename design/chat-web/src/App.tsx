import { useState } from 'react'
import { Icon, ICON_SHEET } from './flash/icons'
import {
  DesktopFrame, PhoneFrame,
  type ChatKind, type Screen, type Sheet, type Theme,
} from './flash/Shells'
import {
  CUSTOM_COLOURS, RECEIVED_MAP, SENT_FILE, THREAD, WALLPAPERS, type Message, type WallpaperId,
} from './flash/data'
import { MessageView, wallpaperClass, wallpaperStyle } from './flash/Conversation'
import { Composer } from './flash/Composer'
import { SwarmBoard } from './flash/Swarm'

type View = 'all' | 'phone' | 'desk'

function params() {
  const q = new URLSearchParams(window.location.search)
  const theme = q.get('theme') === 'dark' ? 'dark' : 'light'
  const wallpaper = (WALLPAPERS.find(w => w.id === q.get('wallpaper'))?.id ?? 'signal') as WallpaperId
  const view = (q.get('view') ?? 'all') as View
  const motion = q.get('motion') === 'reduced'
  return { theme: theme as Theme, wallpaper, view, motion }
}

export default function App() {
  const init = params()
  const [theme, setTheme] = useState<Theme>(init.theme)
  const [wallpaper, setWallpaper] = useState<WallpaperId>(init.wallpaper)
  const [custom, setCustom] = useState(CUSTOM_COLOURS[5].hex)
  const [view, setView] = useState<View>(init.view)
  const [sheetBase, setSheetBase] = useState<WallpaperId>(init.wallpaper)
  const [reduced, setReduced] = useState(init.motion)
  const [replay, setReplay] = useState(0)

  const base = {
    theme, wallpaper, custom, reduceMotion: reduced,
    onPick: (id: WallpaperId) => { setSheetBase(wallpaper); setWallpaper(id) },
    onCustom: (hex: string) => { setSheetBase(wallpaper); setWallpaper('custom'); setCustom(hex) },
    onUndo: () => setWallpaper(sheetBase),
    onTheme: setTheme,
    onResetWallpaper: () => setWallpaper('graphite'),
    onReduceMotion: setReduced,
  }

  const phone = (chat: ChatKind, initialSheet: Sheet = 'none', initialScreen: Screen = 'chat', append?: Message[]) => (
    <PhoneFrame {...base} chat={chat} initialSheet={initialSheet} initialScreen={initialScreen} append={append} />
  )

  return (
    <div className="review" data-motion={reduced ? 'reduced' : 'full'}>
      <header className="review-head">
        <h1 className="review-title">Flash chat, redesigned</h1>
        <p className="review-lede">
          The conversation, its group, the overflow menu, profiles, the composer, wallpapers and the
          icon set, rebuilt on the shipped tokens: Pulse teal, the 20dp bubble with its scoop tail,
          16/22 message text and the 120/200/320ms motion scale. Presentation only.
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
                onClick={() => setWallpaper(w.id)}>
                <span className={'ctl-wall-dot ' + wallpaperClass(w.id)} />
                {w.name}
              </button>
            ))}
            <button type="button" className={'ctl-wall' + (wallpaper === 'custom' ? ' is-on' : '')}
              aria-pressed={wallpaper === 'custom'}
              onClick={() => setWallpaper('custom')}>
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
        {/* ---------------------------------------------------------- direct chat */}
        {view !== 'desk' && (
          <>
            <section className="review-section" aria-labelledby="s-conversation">
              <div className="review-section-head">
                <h2 id="s-conversation">The direct conversation</h2>
                <p>
                  Three actions in the header, the transport in the subtitle, and the overflow menu
                  one tap away. The menu follows the app’s own item list: the profile first, search
                  and appearance, then the quiet actions, with destructive ones below a rule. Tapping
                  the avatar opens the same profile without the menu.
                </p>
              </div>

              <div className="review-frames">
                <figure className="shot">
                  {phone('direct')}
                  <figcaption>
                    <strong>Conversation</strong>
                    <span>Type: the mic becomes send in place. The kebab opens the menu.</span>
                  </figcaption>
                </figure>
                <figure className="shot">
                  {phone('direct', 'menu')}
                  <figcaption>
                    <strong>The overflow menu</strong>
                    <span>View profile · Search · Chat appearance · Mark unread · Clear · Revoke trust.</span>
                  </figcaption>
                </figure>
                <figure className="shot">
                  {phone('direct', 'profile')}
                  <figcaption>
                    <strong>Profile</strong>
                    <span>Connection, security, trust, radio fallback, shared content, unpair.</span>
                  </figcaption>
                </figure>
              </div>

              <aside className="review-notes review-notes-wide" aria-label="Anatomy">
                <h3>Anatomy</h3>
                <dl>
                  <div><dt>Header</dt><dd>60dp · 34dp avatar · 16/600 name · 12dp presence with a 13dp lock</dd></div>
                  <div><dt>Thread</dt><dd>12dp gutters · 2dp inside a run · 16dp between runs · 78% cap</dd></div>
                  <div><dt>Bubble</dt><dd>20dp radius · 8dp scoop · 12/8 padding · 16/22 text · 11dp time</dd></div>
                  <div><dt>Menu</dt><dd>248dp popover · 44dp rows · 18dp icons · destructive below a hairline</dd></div>
                  <div><dt>Profile</dt><dd>64dp avatar · four key rows · one destructive action at the foot</dd></div>
                  <div><dt>Composer</dt><dd>64dp bar · three objects, 8dp apart · 44dp targets</dd></div>
                </dl>
                <p className="review-note-body">
                  The menu is the shipped <code>FlashConversationMenuMath</code> list, word for word.
                  One item is new: <strong>Chat appearance</strong>, proposed in ADR-354.
                </p>
              </aside>
            </section>

            {/* ------------------------------------------------------------- group */}
            <section className="review-section" aria-labelledby="s-group">
              <div className="review-section-head">
                <h2 id="s-group">The group</h2>
                <p>
                  A group header carries a member count instead of a presence line, and a group avatar
                  is the 2×2 initial collage. Incoming runs name their sender once. The file card adds
                  the swarm piece map and the delivery line; group info carries members, roles, join
                  requests and the way into settings, and group settings separates the signed rules
                  from the switches that never leave this phone.
                </p>
              </div>

              <div className="review-frames">
                <figure className="shot">
                  {phone('group')}
                  <figcaption>
                    <strong>Group conversation</strong>
                    <span>Six members, three online. The receiver’s file shows 12 of 24 pieces.</span>
                  </figcaption>
                </figure>
                <figure className="shot">
                  {phone('group', 'none', 'groupInfo')}
                  <figcaption>
                    <strong>Group info</strong>
                    <span>Invite, settings, member roles, admin actions and two join requests.</span>
                  </figcaption>
                </figure>
                <figure className="shot">
                  {phone('group', 'none', 'groupSettings')}
                  <figcaption>
                    <strong>Group settings</strong>
                    <span>Signed rules above; this device’s seeding below; code and relay last.</span>
                  </figcaption>
                </figure>
              </div>

              <aside className="review-notes review-notes-wide" aria-label="Group notes">
                <h3>What the group adds</h3>
                <dl>
                  <div><dt>Rules</dt><dd>Approval to join, admins-only invites, members may add, swarming, max size 20</dd></div>
                  <div><dt>Device</dt><dd>Help share files, Wi-Fi only, battery threshold 20%, keep for 1 day</dd></div>
                  <div><dt>Entry</dt><dd>Invite link with address hints; the group code can be changed</dd></div>
                </dl>
                <p className="review-note-body">
                  Signed rules are the group’s; the device switches are local and never travel. The
                  app’s copy is kept verbatim, including “Not paired” on an unpaired requester.
                </p>
              </aside>
            </section>

            {/* ------------------------------------------------------ invite + swarm */}
            <section className="review-section" aria-labelledby="s-swarm">
              <div className="review-section-head">
                <h2 id="s-swarm">Invites and the swarm</h2>
                <p>
                  The piece map is the one graphic drawn from transfer state: verified blocks in green,
                  the block downloading now in cyan, pieces held by another member in amber, and the
                  rest missing. The sender sees one map for the whole group, the receiver sees the map
                  it is assembling, and both read the same four sentences.
                </p>
              </div>

              <div className="swarm-row">
                <figure className="shot">
                  {phone('group', 'invite')}
                  <figcaption>
                    <strong>Invite link</strong>
                    <span>A link with address hints; the join rules still decide who gets in.</span>
                  </figcaption>
                </figure>
                <figure className="shot">
                  {phone('group', 'none', 'chat', [SENT_FILE])}
                  <figcaption>
                    <strong>Sent to the group</strong>
                    <span>All 24 pieces here; “Delivered to 4 of 6 · You can go offline now”.</span>
                  </figcaption>
                </figure>
              </div>

              <SwarmBoard blocks={RECEIVED_MAP} peers={3} holders={SENT_FILE.holders ?? []} />
            </section>

            {/* -------------------------------------------------------- message parts */}
            <section className="review-section" aria-labelledby="s-parts">
              <div className="review-section-head">
                <h2 id="s-parts">The message parts</h2>
                <p>
                  A photo, a transfer in flight, a verified file, a voice note and a reply, at their
                  real size. Each keeps the bubble’s surface, the 12dp inset and the tabular time; the
                  file block is content on the bubble, not a card inside a card.
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

            {/* ------------------------------------------------------------ composer */}
            <section className="review-section" aria-labelledby="s-composer">
              <div className="review-section-head">
                <h2 id="s-composer">The composer</h2>
                <p>
                  The text field is no longer a bar with a button at its end. Attach, the input pill and
                  the send key are three separate objects, and the trailing slot holds both the mic and
                  the send arrow in the same 44dp box, so the swap is a 120ms cross-fade and never a
                  width animation.
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

            {/* ----------------------------------------------------------- wallpaper */}
            <section className="review-section" aria-labelledby="s-wallpaper">
              <div className="review-section-head">
                <h2 id="s-wallpaper">Wallpaper</h2>
                <p>
                  Five fields and one colour of your own, drawn at 5–9% so the pattern reads as texture
                  and disappears behind a bubble. Selecting a dark wallpaper does not dim the text: the
                  bubbles carry their own surface in both themes.
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
                  <span className="wall-card-name">Yours<span>Chosen from six muted colours.</span></span>
                </button>
              </div>
            </section>

            {/* --------------------------------------------------------------- icons */}
            <section className="review-section" aria-labelledby="s-icons">
              <div className="review-section-head">
                <h2 id="s-icons">The icon set</h2>
                <p>
                  Twenty-two chat glyphs redrawn on the same 24dp grid at a 1.75dp stroke, one deliberate
                  asymmetry each — the cut nose of the send dart, the flat mouthpiece of the handset, the
                  glint inside the camera lens. Names and meanings do not change, so the Compose port
                  replaces path data only.
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
                  circle, 14dp for ticks, 13dp for the lock and the reply glyph.
                </p>
              </div>
            </section>

            {/* -------------------------------------------------------------- motion */}
            <section className="review-section" aria-labelledby="s-motion">
              <div className="review-section-head">
                <h2 id="s-motion">Motion</h2>
                <p>
                  Transform and opacity only; no height, width or margin is animated anywhere. The
                  durations are the shipped tokens, and every one has a reduced-motion answer that keeps
                  the change and drops the travel.
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
                  <tr><td>Menu</td><td>opacity + 4dp rise</td><td>200ms Decelerate</td><td>appears</td></tr>
                  <tr><td>Sheet</td><td>opacity + 16dp rise</td><td>320ms sheet</td><td>appears</td></tr>
                  <tr><td>Press</td><td>scale .94 on header, .97 on bubbles</td><td>120ms out</td><td>no scale</td></tr>
                  <tr><td>Typing dots</td><td>opacity 1.2s loop, 2dp rise</td><td>loop</td><td>static at 60%</td></tr>
                </tbody>
              </table>
            </section>
          </>
        )}

        {/* --------------------------------------------------------------- desktop */}
        {view !== 'phone' && (
          <section className="review-section" aria-labelledby="s-desktop">
            <div className="review-section-head">
              <h2 id="s-desktop">Desktop</h2>
              <p>
                The same conversation in the shipped Windows shell: 68dp rail, 320dp list, the sheet
                holding the chat. The composer centres its three objects on an 820dp measure, and the
                menu, profile, group info and settings use the same components as the phone.
              </p>
            </div>
            <div className="review-desk">
              <DesktopFrame {...base} chat="direct" />
            </div>
          </section>
        )}
      </main>

      <footer className="review-foot">
        <p>
          Prototype only: no Gradle wiring, no network, no app code changed. Photo:
          <a href="https://commons.wikimedia.org/w/index.php?curid=77688219">“Sivas Buruciye Medrese 115”</a>
          by Dosseman, CC BY-SA 4.0, via Wikimedia Commons — the thread is sample content.
        </p>
        <p>
          Files: <code>design/chat-web/src/flash/</code> · design record <code>design/chat-web/DESIGN.md</code> ·
          critique <code>design/chat-web/CRITIQUE.md</code>
        </p>
      </footer>
    </div>
  )
}
