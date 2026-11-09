import { useEffect, useRef, useState } from 'react'
import { Icon } from './icons'
import {
  GROUP, GROUP_RULES, DEVICE_PREFS, JOIN_REQUESTS, MEMBERS,
  type Member, type Toggle,
} from './data'

/** The group's 2x2 initial collage, as FlashGroupAvatar draws it for 3+ members. */
export function GroupAvatar({ size = 34 }: { size?: number }) {
  const tiles = [MEMBERS[0], MEMBERS[2], MEMBERS[4], MEMBERS[5]]
  return (
    <span className="c-collage" style={{ width: size, height: size }} aria-label="Group avatar" role="img">
      {tiles.map((m, i) => (
        <span key={m.id} className={'c-collage-tile t' + i}>{m.initials[0]}</span>
      ))}
    </span>
  )
}

/** The app's two subtitles per switch, chosen by the state, not a static line. */
const RULE_SUB: Record<string, [string, string]> = {
  approval: ['New members need an admin’s approval to join.', 'Anyone with the group invite link can join immediately.'],
  invites: ['Only group admins can generate and share invite links.', 'Any active member can share an invite link.'],
  add: ['Active members can directly add other people.', 'Only admins can add members directly.'],
  swarm: ['Group members cooperatively share downloaded file pieces.', 'File swarming is disabled for this group.'],
  seed: ['This device seeds pieces it already has.', 'This device will not seed or share file pieces.'],
  wifi: ['Never use mobile data to seed.', 'Share files over Wi-Fi and mobile data.'],
}

function ruleSub(t: Toggle): string {
  const pair = RULE_SUB[t.id]
  return pair ? pair[t.on ? 0 : 1] : t.subtitle
}

function ToggleRow({ t, onToggle }: { t: Toggle; onToggle: () => void }) {
  return (
    <div className="g-toggle">
      <span className="g-toggle-text">
        <span className="g-toggle-title">{t.title}</span>
        <span className="g-toggle-sub">{ruleSub(t)}</span>
      </span>
      <button type="button" role="switch" aria-checked={t.on} aria-label={t.title}
        className={'c-switch' + (t.on ? ' is-on' : '')} onClick={onToggle} />
    </div>
  )
}

/* ---------------------------------------------------------------- group info */

export function GroupInfoScreen({ onBack, onSettings, onInvite, onSnack }: {
  onBack: () => void
  onSettings: () => void
  onInvite: () => void
  onSnack: (msg: string) => void
}) {
  const [expanded, setExpanded] = useState<string | null>(null)
  const [requests, setRequests] = useState(JOIN_REQUESTS.map(r => ({ ...r, decided: '' as '' | 'in' | 'out' })))

  return (
    <div className="c-page">
      <div className="c-page-head">
        <button type="button" className="c-icon-btn" aria-label="Back to conversation" onClick={onBack}>
          <Icon name="back" size={22} />
        </button>
        <h2 className="c-page-title">Group info</h2>
      </div>

      <div className="g-hero">
        <GroupAvatar size={76} />
        <div className="g-hero-name">{GROUP.name}</div>
        <div className="g-hero-sub">{GROUP.subtitle}</div>
        <div className="g-hero-actions">
          <button type="button" className="c-secondary g-action" onClick={onInvite}>
            <Icon name="share" size={16} />Invite people
          </button>
          <button type="button" className="c-secondary g-action" onClick={() => onSnack('Choose who to add')}>
            <Icon name="group" size={16} />Add members
          </button>
        </div>
      </div>

      <div className="c-group">
        <button type="button" className="c-row" onClick={() => onSnack('Shared media, files and links')}>
          <span className="g-row-icon"><Icon name="gallery" size={19} /></span>
          <span className="c-row-main">
            <span className="c-row-title" style={{ display: 'block' }}>Shared Media &amp; Files</span>
            <span className="c-row-sub" style={{ display: 'block' }}>Photos, videos, files, and links</span>
          </span>
          <Icon name="chevron" size={18} />
        </button>
        <button type="button" className="c-row" onClick={onSettings}>
          <span className="g-row-icon"><Icon name="settings" size={19} /></span>
          <span className="c-row-main">
            <span className="c-row-title" style={{ display: 'block' }}>Group settings</span>
            <span className="c-row-sub" style={{ display: 'block' }}>Join rules, file sharing, group code</span>
          </span>
          <Icon name="chevron" size={18} />
        </button>
      </div>

      <div className="g-section-head">
        <span>Members</span>
        <span className="g-count">{MEMBERS.length}</span>
      </div>
      <div className="c-group">
        {MEMBERS.map(m => {
          const open = expanded === m.id
          return (
            <div key={m.id} className="g-member-wrap">
              <button type="button" className="g-member" aria-expanded={open}
                onClick={() => setExpanded(open ? null : m.id)}>
                <span className={'c-avatar tone-' + m.tone}>
                  {m.initials}
                  {m.online && <span className="c-avatar-dot" />}
                </span>
                <span className="g-member-body">
                  <span className="g-member-top">
                    <span className="g-member-name">{m.name}{m.you ? ' (you)' : ''}</span>
                    {m.role === 'Admin' && <span className="g-chip">Admin</span>}
                  </span>
                  <span className="g-member-sub">
                    {m.transport ? m.transport : 'Offline'}
                    {m.introducedBy ? ` · Added by ${m.introducedBy} — not verified` : ''}
                  </span>
                </span>
                <Icon name="more" size={18} />
              </button>
              {open && (
                <div className="g-member-actions">
                  {m.introducedBy && (
                    <button type="button" className="g-link" onClick={() => onSnack(`Verified ${m.name}`)}>Verify</button>
                  )}
                  <button type="button" className="g-link"
                    onClick={() => onSnack(m.role === 'Admin' ? `${m.name} is no longer an admin` : `${m.name} is now an admin`)}>
                    {m.role === 'Admin' ? 'Dismiss Admin' : 'Make Admin'}
                  </button>
                  <button type="button" className="g-link is-danger"
                    onClick={() => onSnack(`Remove ${m.name}?`)}>Remove</button>
                </div>
              )}
            </div>
          )
        })}
      </div>

      <div className="g-section-head">
        <span>Join requests</span>
        <span className="g-count">{requests.filter(r => !r.decided).length}</span>
      </div>
      <div className="c-group">
        {requests.map(r => (
          <div key={r.id} className="g-request">
            <div className="g-request-main">
              <span className={'c-avatar tone-' + (r.paired ? 1 : 3)}>{r.initials}</span>
              <span className="g-member-body">
                <span className="g-member-top">
                  <span className="g-member-name">{r.name}</span>
                  {!r.paired && <span className="g-chip is-warn">Not paired</span>}
                </span>
                <span className="g-member-sub">
                  {r.decided === 'in' ? 'Approved' : r.decided === 'out' ? 'Declined' : 'Wants to join with an invite link'}
                </span>
              </span>
            </div>
            {!r.decided && (
              <div className="g-request-actions">
                <button type="button" className="g-link" onClick={() => {
                  setRequests(list => list.map(x => x.id === r.id ? { ...x, decided: 'out' } : x))
                  onSnack(`Declined ${r.name}`)
                }}>Decline</button>
                <button type="button" className="g-link is-accent" onClick={() => {
                  setRequests(list => list.map(x => x.id === r.id ? { ...x, decided: 'in' } : x))
                  onSnack(`${r.name} joined the group`)
                }}>Approve</button>
              </div>
            )}
          </div>
        ))}
      </div>

      <p className="g-note">
        The owner decides who is in the group. Removing a member clears the group code for everyone,
        so a removed device cannot read what is sent after it leaves.
      </p>
    </div>
  )
}

/* ------------------------------------------------------------ group settings */

export function GroupSettingsScreen({ onBack, onInvite, onSnack }: {
  onBack: () => void
  onInvite: () => void
  onSnack: (msg: string) => void
}) {
  const [rules, setRules] = useState(GROUP_RULES)
  const [device, setDevice] = useState(DEVICE_PREFS)
  const [max, setMax] = useState(20)

  const flip = (list: Toggle[], set: (v: Toggle[]) => void, id: string) =>
    set(list.map(t => t.id === id ? { ...t, on: !t.on } : t))

  return (
    <div className="c-page">
      <div className="c-page-head">
        <button type="button" className="c-icon-btn" aria-label="Back to group info" onClick={onBack}>
          <Icon name="back" size={22} />
        </button>
        <h2 className="c-page-title">Group settings</h2>
      </div>

      <div className="g-section-head"><span>Group rules</span><span className="g-section-sub">Signed by admins · Applies to all members</span></div>
      <div className="c-group">
        {rules.map(t => <ToggleRow key={t.id} t={t} onToggle={() => flip(rules, setRules, t.id)} />)}
        <div className="g-stepper">
          <span className="g-toggle-text">
            <span className="g-toggle-title">Maximum group size</span>
            <span className="g-toggle-sub">Signed into the group rules</span>
          </span>
          <span className="g-step">
            <button type="button" aria-label="Fewer members" onClick={() => setMax(v => Math.max(3, v - 1))}>−</button>
            <span>{max}</span>
            <button type="button" aria-label="More members" onClick={() => setMax(v => Math.min(20, v + 1))}>+</button>
          </span>
        </div>
      </div>

      <div className="g-section-head"><span>This device</span><span className="g-section-sub">Stored locally · Applies only to this device</span></div>
      <div className="c-group">
        {device.map(t => <ToggleRow key={t.id} t={t} onToggle={() => flip(device, setDevice, t.id)} />)}
        <div className="g-toggle">
          <span className="g-toggle-text">
            <span className="g-toggle-title">Battery pause threshold</span>
            <span className="g-toggle-sub">Pause seeding when the battery drops below 20%.</span>
          </span>
          <span className="g-pill">20%</span>
        </div>
        <div className="g-toggle">
          <span className="g-toggle-text">
            <span className="g-toggle-title">Keep files available</span>
            <span className="g-toggle-sub">Keep completed files available for 1 day.</span>
          </span>
          <span className="g-pill">1 day</span>
        </div>
      </div>

      <div className="g-section-head"><span>Group code &amp; invites</span><span className="g-section-sub">Manage entry points to this group</span></div>
      <div className="c-group">
        <button type="button" className="c-row" onClick={onInvite}>
          <span className="g-row-icon"><Icon name="share" size={19} /></span>
          <span className="c-row-main"><span className="c-row-title" style={{ display: 'block' }}>Share invite link</span></span>
          <Icon name="chevron" size={18} />
        </button>
        <button type="button" className="c-row" onClick={() => onSnack('Existing links stop working immediately')}>
          <span className="g-row-icon is-danger"><Icon name="lock" size={19} /></span>
          <span className="c-row-main">
            <span className="c-row-title is-danger" style={{ display: 'block' }}>Change group code</span>
            <span className="c-row-sub" style={{ display: 'block' }}>Existing invite links stop working</span>
          </span>
        </button>
      </div>

      <div className="g-section-head"><span>Organization Relay (E2c)</span></div>
      <div className="c-group">
        <div className="g-toggle">
          <span className="g-toggle-text">
            <span className="g-toggle-title">Link to Corporate Hub</span>
            <span className="g-toggle-sub">Relay new messages through the hub for remote members. Past messages are never uploaded.</span>
          </span>
          <button type="button" role="switch" aria-checked={false} aria-label="Link to Corporate Hub"
            className="c-switch" onClick={() => onSnack('Hub linking needs an enrolled device')} />
        </div>
      </div>

      <p className="g-note">
        Only group admins can change these rules. Changes are signed and reach every member; the
        device switches below never leave this phone.
      </p>
    </div>
  )
}

/* --------------------------------------------------------------- invite link */

export function InviteSheet({ onClose, onSnack }: { onClose: () => void; onSnack: (msg: string) => void }) {
  const [copied, setCopied] = useState(false)
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
      <div className="c-sheet" role="dialog" aria-modal="true" aria-labelledby="invite-title" tabIndex={-1} ref={sheet}>
        <div className="c-sheet-grab" aria-hidden="true" />
        <div className="c-sheet-head">
          <h3 className="c-sheet-title" id="invite-title">Invite to group</h3>
          <button type="button" className="c-icon-btn" aria-label="Close" onClick={onClose}>
            <Icon name="close" size={20} />
          </button>
        </div>
        <div className="c-sheet-body">
          <p className="g-invite-text">
            Anyone with Flash can use this link to join <strong>{GROUP.name}</strong>. The group’s join
            rules decide whether an admin has to approve them.
          </p>
          <div className="g-link-box">
            <span className="g-link-value">{GROUP.invite}</span>
            <button type="button" className="g-link-copy" aria-label={copied ? 'Copied' : 'Copy link'}
              onClick={() => { setCopied(true); onSnack('Link copied') }}>
              <Icon name={copied ? 'check' : 'copy'} size={16} />
              <span>{copied ? 'Copied' : 'Copy'}</span>
            </button>
          </div>
          <p className="g-note" style={{ margin: '12px 0 4px' }}>
            Links carry the group’s address hints, so a member of this group can find you even when
            discovery cannot see you yet.
          </p>
        </div>
        <div className="c-sheet-foot">
          <button type="button" className="c-primary" onClick={() => onSnack('Share sheet is a device action')}>
            Share link
          </button>
        </div>
      </div>
    </>
  )
}

export function memberInitials(m: Member): string {
  return m.initials
}
