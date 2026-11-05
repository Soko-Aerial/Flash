import { Icon } from './icons'
import type { Holder, Piece } from './data'

const LABEL: Record<Piece, string> = {
  3: 'Verified and saved',
  2: 'Downloading now',
  1: 'Held by a peer',
  0: 'Missing',
}

const LEGEND: Array<{ p: Piece; label: string }> = [
  { p: 3, label: 'Here' },
  { p: 2, label: 'Now' },
  { p: 1, label: 'Peer' },
  { p: 0, label: 'Missing' },
]

/**
 * The swarm piece map (UI-055). Micro-blocks in the app's four states: verified
 * (green), downloading (cyan), held by a peer (amber), missing (neutral). The
 * summary counts verified blocks only, exactly as FlashSwarmUiMath prints it.
 */
export function PieceMap({ blocks, peers, compact }: { blocks: Piece[]; peers: number; compact?: boolean }) {
  const verified = blocks.filter(b => b === 3).length
  const percent = Math.round((verified / blocks.length) * 100)

  return (
    <div className={'sw-map' + (compact ? ' is-compact' : '')}>
      <div className="sw-map-head">
        <span className="sw-map-title">
          <Icon name="transfer" size={13} />
          Piece map
        </span>
        <span className="sw-map-count">
          {verified}/{blocks.length} · {percent}%
        </span>
        <span className="sw-map-peers">{peers} peers</span>
      </div>
      <div className="sw-grid" role="img"
        aria-label={`Swarm piece map: ${verified} of ${blocks.length} verified, ${peers} peers online`}>
        {blocks.map((b, i) => (
          <span key={i} className={'sw-cell p' + b} title={LABEL[b]} />
        ))}
      </div>
      {!compact && (
        <div className="sw-legend">
          {LEGEND.map(l => (
            <span key={l.p} className="sw-legend-item">
              <span className={'sw-cell p' + l.p} />{l.label}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

export function HolderList({ holders }: { holders: Holder[] }) {
  return (
    <ul className="sw-holders">
      {holders.map((h, i) => (
        <li key={h.name} className="sw-holder">
          <span className={'sw-holder-avatar tone-' + (i % 4)}>
            {h.initials}
            {h.online && <span className="c-avatar-dot" />}
          </span>
          <span className="sw-holder-name">{h.name}</span>
          <span className="sw-holder-line">{h.line}</span>
        </li>
      ))}
    </ul>
  )
}

/* --------------------------------------------------------------- the board */

/** The proof panel for the swarm section: the map, who has the file, and the four
 *  lines the engine can print for a group transfer (FlashSwarmUiMath). */
export function SwarmBoard({ blocks, peers, holders }: {
  blocks: Piece[]
  peers: number
  holders: Holder[]
}) {
  return (
    <div className="sw-board">
      <div className="sw-board-head">
        <Icon name="transfer" size={16} />
        <h3>One file, six devices</h3>
      </div>
      <PieceMap blocks={blocks} peers={peers} />
      <HolderList holders={holders} />
      <ul className="sw-lines">
        <li><span>Sender</span>Delivered to 4 of 6 · You can go offline now</li>
        <li><span>Receiver</span>Getting it from 3 devices</li>
        <li><span>Missing parts</span>Waiting for a device that has the missing parts.</li>
        <li><span>Source lost</span>Your file is no longer available. Pick it again to keep sharing</li>
      </ul>
    </div>
  )
}
