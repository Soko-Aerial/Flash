import { Fragment, useEffect, useRef } from 'react'
import { Icon } from './icons'
import type { MenuItem } from './data'

/**
 * The conversation three-dot menu, as the app composes it: FlashConversationMenuMath
 * decides the item list, destructive items are red and separated by a rule, and the
 * popover is anchored under the More button (never a full-width sheet).
 */
export function ChatMenu({ items, onPick, onClose }: {
  items: MenuItem[]
  onPick: (item: MenuItem) => void
  onClose: () => void
}) {
  const menu = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const first = menu.current?.querySelector<HTMLButtonElement>('button')
    first?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose() }
      if (e.key !== 'Tab' || !menu.current) return
      const nodes = [...menu.current.querySelectorAll<HTMLButtonElement>('button')]
      const i = nodes.indexOf(document.activeElement as HTMLButtonElement)
      if (i < 0) return
      const next = e.shiftKey ? (i - 1 + nodes.length) % nodes.length : (i + 1) % nodes.length
      e.preventDefault()
      nodes[next].focus()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <>
      <div className="c-menu-scrim" onClick={onClose} aria-hidden="true" />
      <div className="c-menu" role="menu" aria-label="Conversation menu" ref={menu}>
        {items.map((item, i) => (
          <Fragment key={item.id}>
            {item.destructive && !items[i - 1]?.destructive && <div className="c-menu-rule" role="separator" />}
            <button type="button" role="menuitem"
              className={'c-menu-item' + (item.destructive ? ' is-danger' : '')}
              onClick={() => onPick(item)}>
              <Icon name={item.icon} size={18} />
              <span>{item.label}</span>
            </button>
          </Fragment>
        ))}
      </div>
    </>
  )
}
