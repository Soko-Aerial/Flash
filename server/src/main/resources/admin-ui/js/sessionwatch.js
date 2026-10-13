// Session lifetime handling: a countdown to the absolute end of the session, an idle warning shortly before the hub's idle timeout, a
// quiet keep-alive while the administrator is working, and one warning five minutes before the end. The hub is the authority: this is
// only so nobody is surprised.

import { h } from './dom.js';
import { t } from './i18n.js';
import { session, msLeft } from './state.js';
import { api, events } from './app.js';
import { openDialog } from './ui/dialog.js';
import { button } from './ui/widgets.js';
import { toast } from './ui/toast.js';

const IDLE_LIMIT_MS = 30 * 60 * 1000;
const IDLE_WARN_MS = 25 * 60 * 1000;
const KEEPALIVE_MS = 5 * 60 * 1000;
const END_WARN_MS = 5 * 60 * 1000;

/**
 * @param {{onTick: (msLeft: number, tone: 'ok'|'warn') => void}} o
 * @returns {() => void} stop function
 */
export function watchSession({ onTick }) {
  const doc = globalThis.document;
  let lastActivity = Date.now();
  let lastKeepalive = Date.now();
  let idleDialog = null;
  let endWarned = false;

  const bump = () => {
    lastActivity = Date.now();
  };
  const evts = ['pointerdown', 'keydown', 'wheel', 'touchstart'];
  for (const e of evts) doc.addEventListener(e, bump, { passive: true, capture: true });

  async function keepalive() {
    lastKeepalive = Date.now();
    try {
      await api.get('/session');
    } catch (e) {
      /* a 401 already signalled expiry */
    }
  }

  const timer = setInterval(() => {
    const now = Date.now();
    const left = msLeft(now);
    onTick(Math.max(0, left), left <= END_WARN_MS ? 'warn' : 'ok');
    if (left <= 0) {
      events.emit('expired', { kind: 'absolute' });
      return;
    }
    if (left <= END_WARN_MS && !endWarned) {
      endWarned = true;
      toast(t('session.endsSoon'), { kind: 'warning' });
    }
    const idle = now - lastActivity;
    if (idle < KEEPALIVE_MS && now - lastKeepalive >= KEEPALIVE_MS) keepalive();
    if (idle >= IDLE_WARN_MS && !idleDialog) {
      const remaining = IDLE_LIMIT_MS - idle;
      const dlg = openDialog({
        title: t('session.idle.title'),
        size: 'sm',
        dismissible: false,
        body: h('p', { text: t('session.idle.text', { minutes: Math.max(1, Math.round(remaining / 60000)) }) }),
        actions: [
          button({
            label: t('session.idle.stay'),
            variant: 'primary',
            on: {
              click: async () => {
                bump();
                await keepalive();
                dlg.close();
              },
            },
          }),
        ],
        onClose: () => {
          idleDialog = null;
        },
      });
      idleDialog = dlg;
    }
  }, 1000);

  return () => {
    clearInterval(timer);
    for (const e of evts) doc.removeEventListener(e, bump, { capture: true });
    if (idleDialog) idleDialog.close();
  };
}

export function resetWarnings() {
  session.lastActivity = Date.now();
}
