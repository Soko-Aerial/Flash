// The application singletons every view shares: the API client wired to the session, the step-up flow, and a tiny event hub
// (session expired, navigation requests) so views never import the shell.

import { createApi, ApiError } from './api.js';
import { createStepUp, StepUpError } from './stepup.js';
import { session, setSession } from './state.js';
import { toast, toastError } from './ui/toast.js';
import { t } from './i18n.js';

const handlers = new Map();

export const events = {
  on(name, fn) {
    if (!handlers.has(name)) handlers.set(name, new Set());
    handlers.get(name).add(fn);
    return () => handlers.get(name).delete(fn);
  },
  emit(name, payload) {
    for (const fn of handlers.get(name) || []) fn(payload);
  },
};

let expiredSignalled = false;

export const api = createApi({
  getCsrf: () => session.csrf,
  refreshCsrf: async () => {
    const dto = await api.get('/session');
    setSession(dto);
  },
  onSessionExpired: (err) => {
    if (expiredSignalled) return;
    expiredSignalled = true;
    events.emit('expired', err);
  },
  onRateLimited: () => {},
});

/** Called after a successful sign-in so a later expiry signals again. */
export function rearmExpiry() {
  expiredSignalled = false;
}

export const stepUp = createStepUp({ api, getUsername: () => session.user });

/** Registered by main.js: opens the "set up confirmation on this browser" dialog; resolves true when a key was registered. */
let enrolHook = async () => false;
export function setEnrolHook(fn) {
  enrolHook = fn;
}

/**
 * Runs a dangerous action: step-up first, then `perform(stepUpId)`. Failures become plain toasts; the promise resolves to the result or
 * to `undefined` when the action did not happen (the caller can check). Errors that the caller wants to handle itself are rethrown when
 * [rethrow] is true.
 */
export async function guarded(action, target, perform, { rethrow = false } = {}) {
  try {
    return await stepUp.run({ action, target, needKey: (reason) => enrolHook(reason) }, perform);
  } catch (e) {
    if (e instanceof StepUpError) {
      if (e.reason === 'unsupported') toast(t('stepup.unsupported'), { kind: 'error' });
      else if (e.reason === 'rejected') toast(t('stepup.rejected'), { kind: 'error' });
      else toast(t('stepup.cancelled'), { kind: 'warning' });
    } else if (e instanceof ApiError && e.code === 'ERR_STEP_UP') {
      toast(t('stepup.expired'), { kind: 'error', reference: e.correlationId });
    } else if (!rethrow) {
      toastError(e);
    }
    if (rethrow && !(e instanceof StepUpError)) throw e;
    return undefined;
  }
}
