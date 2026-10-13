// The step-up flow (HUBA-12). For a dangerous action the console asks the hub for a challenge, signs its proof bytes with this browser's
// key, has the hub verify it, and then performs the action with `X-Step-Up: <challengeId>`. The id is single use and lives 5 minutes.
// If this browser has no key (or not the registered one) the flow throws a StepUpError the caller turns into the "set up confirmation"
// dialog and then retries once.

import { ApiError } from './api.js';
import * as keys from './stepup-key.js';

export class StepUpError extends Error {
  /** reason: 'unsupported' | 'no-key' | 'key-mismatch' | 'no-server-key' | 'rejected' */
  constructor(reason, message) {
    super(message || reason);
    this.name = 'StepUpError';
    this.reason = reason;
  }
}

/**
 * @param {{api: ReturnType<import('./api.js').createApi>, getUsername: () => string, keyStore?: typeof keys}} deps
 */
export function createStepUp({ api, getUsername, keyStore = keys }) {
  /** Gets a verified step-up id for [action]/[target]. */
  async function obtain(action, target) {
    if (!keyStore.isSupported()) throw new StepUpError('unsupported');
    const record = await keyStore.loadKey(getUsername());
    if (!record) throw new StepUpError('no-key');
    let challenge;
    try {
      challenge = await api.post('/stepup/challenge', { action, target });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'ERR_NO_DEVICE') throw new StepUpError('no-server-key');
      throw e;
    }
    if (challenge.deviceId !== record.keyId) throw new StepUpError('key-mismatch');
    const signature = await keyStore.signChallenge(record, challenge.proofBytes);
    try {
      await api.post('/stepup/verify', { challengeId: challenge.challengeId, signature });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'ERR_STEP_UP') throw new StepUpError('rejected');
      throw e;
    }
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
        if (e instanceof StepUpError && needKey && attempt === 0 && e.reason !== 'unsupported') {
          if (await needKey(e.reason)) continue;
        }
        throw e;
      }
    }
    return undefined;
  }

  /** Registers this browser's key through `POST /admins/me/console-key`. [code] is a fresh authenticator code. */
  async function enrol({ username, code, label }) {
    const made = await keyStore.createKey(username, label);
    const res = await api.post('/admins/me/console-key', { publicKey: made.publicKey, proof: made.proof, code, label });
    made.record.keyId = res.keyId || made.keyId;
    await keyStore.saveKey(made.record);
    return made.record;
  }

  return { obtain, run, enrol, supported: () => keyStore.isSupported() };
}
