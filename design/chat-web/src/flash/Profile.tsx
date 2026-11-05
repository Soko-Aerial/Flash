import { useEffect, useRef } from 'react'
import { Icon } from './icons'

/**
 * UI-032 peer details, opened from the header avatar or "View profile". Rows are
 * the shipped ones: connection, security, trust, radio fallback, shared content,
 * and the destructive unpair action at the foot.
 */
export function ProfileSheet({ onClose, onSnack }: {
  onClose: () => void
  onSnack: (msg: string) => void
}) {
  const sheet = useRef<HTMLDivElement>(null)

  useEffect(() => {
    sheet.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="c-sheet-scrim" onClick={onClose} aria-hidden="true" />
      <div className="c-sheet" role="dialog" aria-modal="true" aria-labelledby="profile-title" tabIndex={-1} ref={sheet}>
        <div className="c-sheet-grab" aria-hidden="true" />
        <div className="c-sheet-head">
          <h3 className="sr-only" id="profile-title">Ama Boateng</h3>
          <span style={{ flex: 1 }} />
          <button type="button" className="c-icon-btn" aria-label="Close" onClick={onClose}>
            <Icon name="close" size={20} />
          </button>
        </div>
        <div className="c-sheet-body p-sheet">
          <span className="c-avatar p-avatar">
            AB
            <span className="c-avatar-dot" role="img" aria-label="Online" />
          </span>
          <div className="p-name">Ama Boateng</div>
          <div className="p-presence">Connected</div>

          <div className="p-rows">
            <div className="p-row">
              <span className="p-row-icon"><Icon name="wifi" size={18} /></span>
              <span className="p-row-label">Connection</span>
              <span className="p-row-value">Local network (Wi-Fi)</span>
            </div>
            <div className="p-row">
              <span className="p-row-icon"><Icon name="lock" size={18} /></span>
              <span className="p-row-label">Security</span>
              <span className="p-row-value">End-to-end encrypted</span>
            </div>
            <div className="p-row">
              <span className="p-row-icon"><Icon name="check" size={18} /></span>
              <span className="p-row-label">Trust</span>
              <span className="p-row-value">Paired device</span>
            </div>
            <div className="p-row">
              <span className="p-row-icon"><Icon name="bluetooth" size={18} /></span>
              <span className="p-row-label">Radio fallback</span>
              <span className="p-row-value">Automatic</span>
            </div>
            <button type="button" className="p-row is-link" onClick={() => onSnack('Shared media, files and links')}>
              <span className="p-row-icon"><Icon name="gallery" size={18} /></span>
              <span className="p-row-label">Shared Media &amp; Files</span>
              <Icon name="chevron" size={18} />
            </button>
          </div>

          <button type="button" className="p-danger" onClick={() => onSnack('Unpairing removes the trust on this device')}>
            Unpair this device
          </button>
        </div>
      </div>
    </>
  )
}
