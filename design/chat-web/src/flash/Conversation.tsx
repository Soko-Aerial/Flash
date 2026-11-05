import { useEffect, useRef, useState } from 'react'
import { Icon } from './icons'
import { Composer, type ReplyRef } from './Composer'
import { PieceMap } from './Swarm'
import { GroupAvatar } from './Group'
import photo from '../assets/east-arcade.jpg'
import {
  GROUP, GROUP_THREAD, THREAD, type Message, type WallpaperId,
} from './data'

/* --------------------------------------------------------------- wallpapers */

export function wallpaperClass(id: WallpaperId): string {
  return `wall-${id === 'custom' ? 'graphite' : id}`
}

export function wallpaperStyle(id: WallpaperId, custom: string): React.CSSProperties | undefined {
  if (id !== 'custom') return undefined
  return { '--chat-ground': custom, '--chat-pattern': 'none' } as React.CSSProperties
}

/* ------------------------------------------------------------------- header */

function Head({ chat, onMenu, onOpenInfo, onOpenProfile }: {
  chat: 'direct' | 'group'
  onMenu: () => void
  onOpenInfo: () => void
  onOpenProfile: () => void
}) {
  return (
    <header className="c-head">
      <button type="button" className="c-head-back" aria-label="Back to chats">
        <Icon name="back" size={22} />
      </button>
      <button type="button" className="c-head-who"
        aria-label={chat === 'group' ? 'Open group info' : 'Open profile'}
        onClick={chat === 'group' ? onOpenInfo : onOpenProfile}>
        {chat === 'group' ? (
          <GroupAvatar size={34} />
        ) : (
          <span className="c-avatar">
            AB
            <span className="c-avatar-dot" role="img" aria-label="Online" />
          </span>
        )}
        <span style={{ minWidth: 0 }}>
          <span className="c-head-name">
            <span className="c-head-name-text">{chat === 'group' ? GROUP.name : 'Ama Boateng'}</span>
            {chat === 'direct' && (
              <span className="c-head-lock" title="End-to-end encrypted" aria-label="End-to-end encrypted">
                <Icon name="lock" size={13} />
              </span>
            )}
          </span>
          <span className="c-head-sub">
            {chat === 'group' ? GROUP.subtitle : 'Online · direct over LAN'}
          </span>
        </span>
      </button>
      <div className="c-head-actions">
        <button type="button" className="c-icon-btn" aria-label="Voice call">
          <Icon name="call" size={21} />
        </button>
        <button type="button" className="c-icon-btn" aria-label="Video call">
          <Icon name="video" size={22} />
        </button>
        <button type="button" className="c-icon-btn" aria-label="Conversation menu" aria-haspopup="menu"
          onClick={onMenu}>
          <Icon name="more" size={22} />
        </button>
      </div>
    </header>
  )
}

/* ------------------------------------------------------------------- bubbles */

function Meta({ time, status }: { time: string; status?: 'sent' | 'read' }) {
  return (
    <span className="c-bubble-meta">
      {time}
      {status && <Icon name={status === 'read' ? 'read' : 'check'} size={14} />}
    </span>
  )
}

export function MessageView({ m, isNew, showSender }: { m: Message; isNew?: boolean; showSender?: boolean }) {
  const cls = 'c-bubble' + (isNew ? ' is-new' : '')

  if (m.k === 'photo') {
    return (
      <div className={cls + ' c-photo'}>
        <img src={photo} alt={m.alt} width={254} height={168} loading="lazy" />
        <div className="c-photo-cap">{m.caption}</div>
        <div className="c-photo-foot">
          <span>{m.size}</span>
          <span aria-hidden="true">·</span>
          <span>{m.time}</span>
        </div>
      </div>
    )
  }

  if (m.k === 'file') {
    return (
      <div className={cls + ' c-file'}>
        {showSender && m.sender && <div className="c-bubble-sender">{m.sender}</div>}
        <div className="c-file-card">
          <span className="c-file-glyph"><Icon name="file" size={19} /></span>
          <span style={{ minWidth: 0 }}>
            <span className="c-file-name">{m.name}</span>
            <span className="c-file-sub">{m.meta}</span>
          </span>
        </div>
        {m.map && <PieceMap blocks={m.map} peers={m.peers ?? 0} compact />}
        {m.progress < 1 ? (
          <div className="c-file-progress">
            <span className="c-track"><i style={{ width: `${Math.round(m.progress * 100)}%` }} /></span>
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{Math.round(m.progress * 100)}%</span>
          </div>
        ) : (
          <div className="c-file-progress">
            <span className="c-verified"><Icon name="check" size={13} />Verified · SHA-256</span>
            <span style={{ marginLeft: 'auto' }}>{m.time}</span>
          </div>
        )}
        {m.progress < 1 && (
          <div className="c-file-progress" style={{ marginTop: 4 }}>
            <span>{m.detail}</span>
            <Meta time={m.time} />
          </div>
        )}
        {m.swarmLine && <div className="c-swarm-line">{m.swarmLine}</div>}
      </div>
    )
  }

  if (m.k === 'voice') {
    const played = Math.round(m.amplitudes.length * 0.38)
    return (
      <div className={cls} style={{ paddingRight: 10 }}>
        {showSender && m.sender && <div className="c-bubble-sender">{m.sender}</div>}
        <div className="c-voice">
          <span className="c-voice-play"><Icon name="play" size={15} /></span>
          <span className="c-wave" aria-hidden="true">
            {m.amplitudes.map((a, i) => (
              <i key={i} className={i < played ? 'is-played' : undefined}
                style={{ height: `${Math.max(4, Math.round(a * 0.26))}px` }} />
            ))}
          </span>
          <span className="c-voice-time">{m.duration}</span>
        </div>
        <div className="c-photo-foot" style={{ padding: '5px 2px 0' }}>
          <Meta time={m.time} />
        </div>
      </div>
    )
  }

  return (
    <div className={cls}>
      {showSender && m.sender && <div className="c-bubble-sender">{m.sender}</div>}
      {m.reply && (
        <div className="c-quote">
          <span className="c-quote-icon"><Icon name="reply" size={13} /></span>
          <span style={{ minWidth: 0 }}>
            <span className="c-quote-name" style={{ display: 'block' }}>{m.reply.name}</span>
            <span className="c-quote-text" style={{ display: 'block' }}>{m.reply.text}</span>
          </span>
        </div>
      )}
      {m.text}
      <Meta time={m.time} status={m.status} />
    </div>
  )
}

function runOf(messages: Message[]): Message[][] {
  const runs: Message[][] = []
  for (const m of messages) {
    const last = runs[runs.length - 1]
    const sameSender = last && last[0].from === m.from &&
      (m.from === 'out' || (last[0].sender ?? '') === (m.sender ?? ''))
    if (sameSender) last.push(m)
    else runs.push([m])
  }
  return runs
}

function Runs({ messages, fresh }: { messages: Message[]; fresh: string[] }) {
  return (
    <>
      {runOf(messages).map(run => (
        <div key={run[0].id} className={'c-run ' + run[0].from}>
          {run.map((m, i) => (
            <MessageView key={m.id} m={m} isNew={fresh.includes(m.id)}
              showSender={run[0].from === 'in' && i === 0 && !!m.sender} />
          ))}
        </div>
      ))}
    </>
  )
}

/* ------------------------------------------------------------------- thread */

function Thread({ messages, unreadAt, typing, endRef }: {
  messages: Message[]
  unreadAt: number
  typing: boolean
  endRef: React.RefObject<HTMLDivElement | null>
}) {
  const fresh: string[] = []
  const split = unreadAt > 0 ? Math.min(unreadAt, messages.length) : messages.length

  return (
    <div className="c-thread" ref={endRef}
      role="log" aria-label="Messages">
      <div className="c-day">Today</div>
      <Runs messages={messages.slice(0, split)} fresh={fresh} />
      {unreadAt > 0 && messages.length > split && <div className="c-unread"><span>Unread</span></div>}
      <Runs messages={messages.slice(split)} fresh={fresh} />
      {typing && (
        <div className="c-run in" style={{ marginTop: 12 }}>
          <div className="c-typing" aria-label="Someone is typing">
            <i /><i /><i />
          </div>
        </div>
      )}
    </div>
  )
}

/* --------------------------------------------------------------- conversation */

export function Conversation({ chat, wallpaper, custom, layout = 'phone', typing = false, autoFocus = false, append = [], onMenu, onOpenInfo, onOpenProfile }: {
  chat: 'direct' | 'group'
  wallpaper: WallpaperId
  custom: string
  layout?: 'phone' | 'desk'
  typing?: boolean
  autoFocus?: boolean
  append?: Message[]
  onMenu: () => void
  onOpenInfo: () => void
  onOpenProfile: () => void
}) {
  const [extra, setExtra] = useState<Message[]>([])
  const [reply, setReply] = useState<ReplyRef | null>(null)
  const end = useRef<HTMLDivElement | null>(null)
  const base = chat === 'group' ? GROUP_THREAD : THREAD
  const messages = [...base, ...append, ...extra]

  useEffect(() => {
    const el = end.current
    if (!el) return
    const toEnd = () => { el.scrollTop = el.scrollHeight }
    toEnd()
    const raf = requestAnimationFrame(toEnd)
    return () => cancelAnimationFrame(raf)
  }, [extra.length])

  const push = (m: Message) => setExtra(list => [...list, m])

  return (
    <div className={'c-chat ' + wallpaperClass(wallpaper) + (layout === 'desk' ? ' is-desk' : '')}
      style={wallpaperStyle(wallpaper, custom)}>
      <Head chat={chat} onMenu={onMenu} onOpenInfo={onOpenInfo} onOpenProfile={onOpenProfile} />
      <Thread messages={messages} unreadAt={chat === 'group' ? 0 : 7} typing={typing} endRef={end} />
      <Composer
        autoFocus={autoFocus}
        reply={reply}
        onDismissReply={() => setReply(null)}
        onSendText={(text) => push({
          k: 'text', id: 'sent-' + Date.now(), from: 'out', time: '10:12',
          text, status: 'sent', reply: reply ?? undefined,
        })}
        onSendVoice={() => push({
          k: 'voice', id: 'voice-' + Date.now(), from: 'out', time: '10:12',
          duration: '0:07',
          amplitudes: [22, 34, 51, 68, 44, 30, 40, 58, 76, 50, 32, 26, 38, 56, 72, 46],
        })}
      />
    </div>
  )
}
