// The signed-in session as the console knows it. The CSRF token lives only in memory (never localStorage, never a URL).

const listeners = new Set();

export const session = {
  user: null,
  role: null,
  csrf: '',
  expiresAtMs: 0,
  /** serverTime - Date.now() at the moment the session was read; used to show a countdown that matches the hub's clock. */
  skewMs: 0,
  sessionId: '',
  stepUpAvailable: false,
  enrolledDeviceId: null,
  approverDeviceId: null,
  lastActivity: Date.now(),
};

export function setSession(dto) {
  session.user = dto.username;
  session.role = dto.role;
  session.csrf = dto.csrfToken;
  session.expiresAtMs = dto.expiresAtMs;
  session.sessionId = dto.sessionId || '';
  session.stepUpAvailable = !!dto.stepUpAvailable;
  session.enrolledDeviceId = dto.enrolledDeviceId || null;
  session.approverDeviceId = dto.approverDeviceId || null;
  if (dto.serverTimeMs) session.skewMs = dto.serverTimeMs - Date.now();
  session.lastActivity = Date.now();
  emit();
}

export function patchSession(patch) {
  Object.assign(session, patch);
  emit();
}

export function clearSession() {
  session.user = null;
  session.role = null;
  session.csrf = '';
  session.expiresAtMs = 0;
  session.sessionId = '';
  session.stepUpAvailable = false;
  session.enrolledDeviceId = null;
  session.approverDeviceId = null;
  emit();
}

export function isSignedIn() {
  return !!session.user;
}

/** Milliseconds left of the absolute session lifetime, on the hub's clock. */
export function msLeft(now = Date.now()) {
  return session.expiresAtMs - (now + session.skewMs);
}

export function subscribe(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function emit() {
  for (const fn of listeners) fn(session);
}
