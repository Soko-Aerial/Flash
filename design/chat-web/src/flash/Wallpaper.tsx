import { useEffect, useRef } from 'react'
import { Icon } from './icons'
import { wallpaperClass, wallpaperStyle } from './Conversation'
import { CUSTOM_COLOURS, WALLPAPERS, type WallpaperId } from './data'

/**
 * Chat wallpaper, per chat. The model is deliberate and single: a pick applies
 * immediately, the footer says what was applied and offers Undo, and the sheet
 * closes by X, scrim, Escape or Done — no separate "save" that does nothing.
 * The per-chat / all-chats scope is an owner decision (ADR-354), so the mock
 * does not pretend to offer it.
 */
export function WallpaperSheet({ wallpaper, custom, onPick, onCustom, onClose, onUndo }: {
  wallpaper: WallpaperId
  custom: string
  onPick: (id: WallpaperId) => void
  onCustom: (hex: string) => void
  onClose: () => void
  onUndo: () => void
}) {
  const active = WALLPAPERS.find(w => w.id === wallpaper)
  const name = active?.name ?? 'Your colour'
  const sheet = useRef<HTMLDivElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const dialog = useRef<HTMLDivElement>(null)

  useEffect(() => {
    dialog.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose() }
      if (e.key !== 'Tab' || !sheet.current) return
      const nodes = sheet.current.querySelectorAll<HTMLElement>(
        'button, [href], input, [tabindex]:not([tabindex="-1"])',
      )
      if (nodes.length === 0) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="c-sheet-scrim" onClick={onClose} aria-hidden="true" />
      <div className="c-sheet" role="dialog" aria-modal="true" aria-labelledby="wallpaper-title" tabIndex={-1} ref={dialog}>
        <div className="c-sheet-grab" aria-hidden="true" />
        <div className="c-sheet-head">
          <h3 className="c-sheet-title" id="wallpaper-title">Chat wallpaper</h3>
          <button type="button" className="c-icon-btn" aria-label="Close" onClick={onClose} ref={closeButton}>
            <Icon name="close" size={20} />
          </button>
        </div>

        <div className="c-sheet-body">
          <div className={'w-preview ' + wallpaperClass(wallpaper)}
            style={{ ...wallpaperStyle(wallpaper, custom), minHeight: 116, padding: 12 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <span className="w-bubble in">East arcade, 40 minutes before the light goes.</span>
              <span className="w-bubble out" style={{ alignSelf: 'flex-end' }}>
                Sending them direct.
                <span style={{ opacity: 0.65, marginLeft: 6 }}>10:03</span>
              </span>
            </div>
          </div>

          <div className="w-section">
            <div className="w-label">Presets</div>
            <div className="w-grid">
              {WALLPAPERS.map(w => (
                <button key={w.id} type="button"
                  className={'w-tile' + (wallpaper === w.id ? ' is-on' : '')}
                  onClick={() => onPick(w.id)} aria-pressed={wallpaper === w.id}>
                  <span className={'w-tile-swatch ' + wallpaperClass(w.id)} />
                  <span className="w-tile-name">
                    {w.name}
                    {wallpaper === w.id && <Icon name="check" size={14} />}
                  </span>
                </button>
              ))}
              <button type="button"
                className={'w-tile' + (wallpaper === 'custom' ? ' is-on' : '')}
                onClick={() => onCustom(custom)} aria-pressed={wallpaper === 'custom'}>
                <span className="w-tile-swatch" style={{ background: custom }}>
                  <span className="w-tile-plus"><Icon name="plus" size={15} /></span>
                </span>
                <span className="w-tile-name">
                  Yours
                  {wallpaper === 'custom' && <Icon name="check" size={14} />}
                </span>
              </button>
            </div>
          </div>

          <div className="w-section">
            <div className="w-label">A colour of your own</div>
            <div className="w-colours">
              {CUSTOM_COLOURS.map(c => (
                <button key={c.hex} type="button"
                  className={'w-colour' + (wallpaper === 'custom' && custom === c.hex ? ' is-on' : '')}
                  aria-label={'Wallpaper colour: ' + c.name}
                  aria-pressed={wallpaper === 'custom' && custom === c.hex}
                  onClick={() => onCustom(c.hex)}>
                  <span className="w-colour-dot" style={{ background: c.hex }} />
                  <span className="w-colour-name">{c.name}</span>
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="c-sheet-foot sheet-foot-row">
          <span className="sheet-status">
            Applied · <strong>{name}</strong>
            <button type="button" className="sheet-undo" onClick={onUndo}>Undo</button>
          </span>
          <button type="button" className="c-primary sheet-done" onClick={onClose}>Done</button>
        </div>
      </div>
    </>
  )
}

/* --------------------------------------------------------------- appearance */

export function AppearanceScreen({ wallpaper, custom, theme, onTheme, onOpenWallpaper, onBack, onReset }: {
  wallpaper: WallpaperId
  custom: string
  theme: 'light' | 'dark'
  onTheme: (t: 'light' | 'dark') => void
  onOpenWallpaper: () => void
  onBack: () => void
  onReset: () => void
}) {
  const name = WALLPAPERS.find(w => w.id === wallpaper)?.name ?? 'Your colour'

  return (
    <div className="c-page">
      <div className="c-page-head">
        <button type="button" className="c-icon-btn" aria-label="Back to chat" onClick={onBack}>
          <Icon name="back" size={22} />
        </button>
        <h2 className="c-page-title">Chat appearance</h2>
      </div>

      <div className="c-group">
        <button type="button" className="c-row" onClick={onOpenWallpaper}>
          <span className={'c-row-thumb ' + wallpaperClass(wallpaper)} style={wallpaperStyle(wallpaper, custom)} />
          <span className="c-row-main">
            <span className="c-row-title" style={{ display: 'block' }}>Wallpaper</span>
            <span className="c-row-sub" style={{ display: 'block' }}>A preset or a colour of your own</span>
          </span>
          <span className="c-row-value">{name}</span>
          <Icon name="chevron" size={18} />
        </button>

        <div className="c-row">
          <span className="c-row-main">
            <span className="c-row-title" style={{ display: 'block' }}>Theme</span>
            <span className="c-row-sub" style={{ display: 'block' }}>Light or dark</span>
          </span>
          <span className="c-segmented" role="group" aria-label="Theme">
            <button type="button" className={theme === 'light' ? 'is-on' : ''}
              aria-pressed={theme === 'light'} onClick={() => onTheme('light')}>Light</button>
            <button type="button" className={theme === 'dark' ? 'is-on' : ''}
              aria-pressed={theme === 'dark'} onClick={() => onTheme('dark')}>Dark</button>
          </span>
        </div>
      </div>

      <div className="w-section" style={{ margin: '0 12px' }}>
        <div className="w-label">Preview</div>
        <div className={'w-preview ' + wallpaperClass(wallpaper)}
          style={{ ...wallpaperStyle(wallpaper, custom), padding: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 7 }}>
            <span className="c-day" style={{ margin: '0 auto 4px' }}>Today</span>
            <span className="w-bubble in">Checksum matches here too.</span>
            <span className="w-bubble in" style={{ width: '72%' }}>The second clip is on its way.</span>
            <span className="w-bubble out" style={{ alignSelf: 'flex-end' }}>Archive both before Friday.</span>
            <div className="w-file">
              <span className="w-file-icon"><Icon name="file" size={18} /></span>
              <span style={{ minWidth: 0 }}>
                <span className="w-file-name">Ceremony-east-arcade-02.mp4</span>
                <span className="w-file-meta">1.8 GB · verified</span>
              </span>
            </div>
            <span className="w-bubble in" style={{ width: '56%' }}>3 files, direct.</span>
          </div>
        </div>
      </div>

      <p style={{ margin: '0 20px 20px' }}>
        <button type="button" className="c-secondary" onClick={onReset}>Reset to Graphite</button>
      </p>

      <p style={{ margin: '0 20px 20px', font: 'var(--t-meta)', color: 'var(--ink-3)' }}>
        Bubbles keep their own colour and their own border, so every wallpaper stays readable.
        A dark wallpaper does not dim the text.
      </p>
    </div>
  )
}
