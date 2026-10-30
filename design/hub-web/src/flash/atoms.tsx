/**
 * Shared atoms. FlashAvatar maps a seed to a palette slot with the same hash the
 * Compose side uses (Kotlin `String.hashCode() % avatarPalette.size`), copied from
 * ui/theme/.../avatar/FlashAvatar.kt — so the same person gets the same colour pair
 * on this page and in the app.
 */
const AVATARS = ['pulse-avatar', 'cyan-avatar', 'violet-avatar', 'spark-avatar', 'ok-avatar']

export function kotlinHash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0
  return h
}

export function initialsOf(name: string): string {
  return name.split(' ').filter(Boolean).map(p => p[0].toUpperCase()).slice(0, 2).join('')
}

export function avatarClass(name: string): string {
  return AVATARS[Math.abs(kotlinHash(name)) % AVATARS.length]
}

export function Avatar({ name, size, className }: {
  name: string
  size: 'md' | 'sm' | 'lg'
  className?: string
}) {
  const cls = [
    'Avatar', size === 'sm' && 'Avatar-sm', size === 'lg' && 'Avatar-lg',
    avatarClass(name), className,
  ].filter(Boolean).join(' ')
  return <span className={cls} aria-hidden="true">{initialsOf(name)}</span>
}
