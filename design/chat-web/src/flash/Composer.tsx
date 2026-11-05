import { useEffect, useRef, useState } from 'react'
import { Icon } from './icons'

export interface ReplyRef { name: string; text: string }

/**
 * The composer, rebuilt around three separate parts:
 *
 *   attach (44)   the input pill (grows to 6 lines)   send / mic (44)
 *
 * The trailing slot is ONE button holding both glyphs stacked, so the
 * mic-to-send swap is a 120ms opacity + scale inside one 44px box — never a
 * width or layout animation, and never a second control appearing.
 */
export function Composer({ onSendText, onSendVoice, reply, onDismissReply, autoFocus }: {
  onSendText: (text: string) => void
  onSendVoice: () => void
  reply?: ReplyRef | null
  onDismissReply?: () => void
  autoFocus?: boolean
}) {
  const [draft, setDraft] = useState('')
  const [focused, setFocused] = useState(false)
  const [recording, setRecording] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [tray, setTray] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  const canSend = draft.trim().length > 0
  const sendMode = canSend && !recording

  useEffect(() => {
    if (!recording) return
    setElapsed(0)
    const id = window.setInterval(() => setElapsed(s => s + 1), 1000)
    return () => window.clearInterval(id)
  }, [recording])

  useEffect(() => {
    if (autoFocus) input.current?.focus()
  }, [autoFocus])

  const send = () => {
    if (!canSend) return
    onSendText(draft.trim())
    setDraft('')
    input.current?.focus()
  }

  return (
    <div className="c-composer">
      {reply && (
        <div className="c-reply-dock">
          <span className="c-reply-dock-bar" aria-hidden="true" />
          <div className="c-reply-dock-body">
            <div className="c-reply-dock-name">{reply.name}</div>
            <div className="c-reply-dock-text">{reply.text}</div>
          </div>
          <button type="button" className="c-icon-btn" style={{ width: 32, height: 32 }}
            aria-label="Cancel reply" onClick={onDismissReply}>
            <Icon name="close" size={16} />
          </button>
        </div>
      )}

      {tray && (
        <div className="c-tray" role="group" aria-label="Attach">
          {([
            ['camera', 'Camera'], ['attach', 'Gallery'], ['file', 'File'], ['mic', 'Voice'],
          ] as const).map(([icon, label]) => (
            <button key={label} type="button" className="c-tray-item" onClick={() => setTray(false)}>
              <Icon name={icon} size={22} />
              <span>{label}</span>
            </button>
          ))}
        </div>
      )}

      <div className="c-composer-row">
        <button type="button"
          className={'c-action is-attach' + (tray ? ' is-open' : '')}
          aria-label={tray ? 'Close attachment options' : 'Attach media or files'}
          aria-expanded={tray}
          onClick={() => setTray(t => !t)}>
          <Icon name="attach" size={22} />
        </button>

        {recording ? (
          <div className="c-recording">
            <span className="c-rec-dot" aria-hidden="true" />
            <span className="c-rec-time">
              {`0:${String(elapsed % 60).padStart(2, '0')}`}
            </span>
            <span className="c-rec-hint">Release to send</span>
            <button type="button" className="c-icon-btn" style={{ width: 32, height: 32 }}
              aria-label="Cancel recording" onClick={() => setRecording(false)}>
              <Icon name="close" size={18} />
            </button>
          </div>
        ) : (
          <div className={'c-pill' + (focused ? ' is-focused' : '')}
            onClick={() => input.current?.focus()}>
            <input
              ref={input}
              value={draft}
              onChange={e => setDraft(e.target.value)}
              onFocus={() => setFocused(true)}
              onBlur={() => setFocused(false)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
              }}
              placeholder="Message"
              aria-label="Message"
              enterKeyHint="send"
            />
          </div>
        )}

        <button type="button"
          className={'c-action' + (sendMode || recording ? ' is-send' : '')}
          aria-label={recording ? 'Send voice message' : sendMode ? 'Send message' : 'Hold to record a voice message'}
          onClick={() => {
            if (recording) { setRecording(false); onSendVoice() }
            else if (sendMode) send()
            else setRecording(true)
          }}>
          <span className="c-slot-icon">
            <Icon name="mic" size={22} className={sendMode || recording ? 'is-hidden' : ''} />
            <Icon name="send" size={20} className={sendMode ? '' : 'is-hidden'} />
            <Icon name="check" size={20} className={recording ? '' : 'is-hidden'} />
          </span>
        </button>
      </div>
    </div>
  )
}
