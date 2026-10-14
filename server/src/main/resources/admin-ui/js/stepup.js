// The step-up flow (HUBA-12). For a dangerous action the console asks the hub for a challenge, signs its proof bytes with this browser's
// key, has the hub verify it, and then performs the action with `X-Step-Up: <challengeId>`. The id is single use and lives 5 minutes.
// If this browser has no key (or not the registered one) the flow throws a StepUpError the caller turns into the "set up confirmation"
// dialog and then retries once.
//
// ADR-131 (FO-14): when the administrator has an approver phone, the most dangerous actions also wait for it. The hub says so in the
// challenge (`approvalRequired`); the console then shows the six digits the phone shows ([onApproval]) and polls `GET /stepup/{id}` until
// the phone has answered. Signer replacement is phone-only (the browser key may be the lost one), so it skips the browser key entirely.

import { ApiError } from './api.js';
import * as keys from './stepup-key.js';

export class StepUpError extends Error {
  /**
   * reason: 'unsupported' | 'no-key' | 'key-mismatch' | 'no-server-key' | 'rejected'
   *       | 'cancelled' | 'approval-timeout' | 'approver-unavailable' | 'no-approver'
   */
  constructor(reason, message) {
    super(message || reason);
    this.name = 'StepUpError';
    this.reason = reason;
  }
}

/** Actions the hub confirms with the phone alone; the browser key is not asked (its loss is why the signer is replaced). */
export const PHONE_ONLY_ACTIONS = new Set(['admin.signer.replace']);

/** Reasons that mean "this browser has no usable key", i.e. the ones the key set-up dialog can fix. */
const NOT_A_KEY_PROBLEM = new Set(['unsupported', 'cancelled', 'approval-timeout', 'approver-unavailable', 'no-approver']);

const APPROVAL_POLL_MS = 1500;
const APPROVAL_MAX_WAIT_MS = 6 * 60 * 1000;

/**
 * @param {{
 *   api: ReturnType<import('./api.js').createApi>,
 *   getUsername: () => string,
 *   keyStore?: typeof keys,
 *   onApproval?: (info: {code: string, action: string, target: string, expiresAtMs: number}) => ({cancelled: () => boolean, close: () => void} | null),
 *   sleep?: (ms: number) => Promise<void>,
 *   now?: () => number,
 *   pollMs?: number,
 * }} deps
 */
export function createStepUp({
  api,
  getUsername,
  keyStore = keys,
  onApproval = null,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now = () => Date.now(),
  pollMs = APPROVAL_POLL_MS,
}) {
  /** Waits until the phone has approved [challenge]; throws StepUpError('cancelled' | 'approval-timeout'). */
  async function awaitApproval(challenge, action, target) {
    const handle = onApproval ? onApproval({ code: challenge.approvalCode || '', action, target, expiresAtMs: challenge.expiresAtMs }) : null;
    const startedAt = now();
    try {
      for (;;) {
        if (handle && handle.cancelled()) throw new StepUpError('cancelled');
        if (now() - startedAt > APPROVAL_MAX_WAIT_MS) throw new StepUpError('approval-timeout');
        let status;
        try {
          status = await api.get('/stepup/' + encodeURIComponent(challenge.challengeId));
        } catch (e) {
          // 404: the hub no longer has the challenge (it expired). Anything else is a real error.
          if (e instanceof ApiError && e.status === 404) throw new StepUpError('approval-timeout');
          throw e;
        }
        if (status.approved) return;
        await sleep(pollMs);
      }
    } finally {
      if (handle) handle.close();
    }
  }

  /** Gets a verified step-up id for [action]/[target]. */
  async function obtain(action, target) {
    const needsKey = !PHONE_ONLY_ACTIONS.has(action);
    let record = null;
    if (needsKey) {
      if (!keyStore.isSupported()) throw new StepUpError('unsupported');
      record = await keyStore.loadKey(getUsername());
      if (!record) throw new StepUpError('no-key');
    }
    let challenge;
    try {
      challenge = await api.post('/stepup/challenge', { action, target });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'ERR_NO_DEVICE') throw new StepUpError('no-server-key');
      if (e instanceof ApiError && e.code === 'ERR_APPROVER_UNAVAILABLE') throw new StepUpError('approver-unavailable');
      if (e instanceof ApiError && e.code === 'ERR_NO_APPROVER') throw new StepUpError('no-approver');
      throw e;
    }
    if (needsKey && challenge.browserKeyRequired !== false) {
      if (challenge.deviceId !== record.keyId) throw new StepUpError('key-mismatch');
      const signature = await keyStore.signChallenge(record, challenge.proofBytes);
      try {
        await api.post('/stepup/verify', { challengeId: challenge.challengeId, signature });
      } catch (e) {
        if (e instanceof ApiError && e.code === 'ERR_STEP_UP') throw new StepUpError('rejected');
        throw e;
      }
    }
    if (challenge.approvalRequired) await awaitApproval(challenge, action, target);
    return challenge.challengeId;
  }

  /**
   * Runs [perform(stepUpId)] after a successful step-up. When this browser must first be set up, [needKey(reason)] is awaited (it opens the
   * enrolment dialog and resolves true when a key was registered) and the flow is tried once more.
   */
  async function run({ action, target, needKey }, perform) {
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const id = await obtain(action, target);
        return await perform(id);
      } catch (e) {
        if (e instanceof StepUpError && needKey && attempt === 0 && !NOT_A_KEY_PROBLEM.has(e.reason)) {
          if (await needKey(e.reason)) continue;
        }
        throw e;
      }
    }
    return undefined;
  }

  /**
   * Registers this browser's key through `POST /admins/me/console-key`. [code] is a fresh authenticator code. With an approver phone bound
   * the hub first wants the phone's approval of the replacement ([StepUpError] reasons from [obtain] bubble up).
   */
  async function enrol({ username, code, label }) {
    const made = await keyStore.createKey(username, label);
    const body = { publicKey: made.publicKey, proof: made.proof, code, label };
    let res;
    try {
      res = await api.post('/admins/me/console-key', body);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'ERR_STEP_UP')) throw e;
      const id = await obtain('admin.signer.replace', 'console-key');
      res = await api.post('/admins/me/console-key', body, { stepUp: id });
    }
    made.record.keyId = res.keyId || made.keyId;
    await keyStore.saveKey(made.record);
    return made.record;
  }

  return { obtain, run, enrol, supported: () => keyStore.isSupported() };
}
