import test from 'node:test';
import assert from 'node:assert/strict';
import { createStepUp, StepUpError } from '../src/main/resources/admin-ui/js/stepup.js';
import { ApiError } from '../src/main/resources/admin-ui/js/api.js';

const RECORD = { keyId: 'srv-1', privateKey: 'k' };

function fakeKeys({ record = RECORD } = {}) {
  const calls = { sign: 0, load: 0, created: 0, saved: [] };
  return {
    calls,
    isSupported: () => true,
    loadKey: async () => {
      calls.load += 1;
      return record;
    },
    signChallenge: async () => {
      calls.sign += 1;
      return 'sig';
    },
    createKey: async () => {
      calls.created += 1;
      return { publicKey: 'pub', proof: 'proof', keyId: 'new-1', record: { keyId: 'new-1' } };
    },
    saveKey: async (r) => calls.saved.push(r),
  };
}

/** A scripted hub: [challenge] answers the challenge request, [statuses] are returned one by one by the status poll. */
function fakeApi({ challenge, statuses = [], challengeError = null, enrolFirstError = null } = {}) {
  const log = [];
  return {
    log,
    async post(path, body, opts) {
      log.push(['POST', path, body, opts]);
      if (path === '/stepup/challenge') {
        if (challengeError) throw challengeError;
        return challenge;
      }
      if (path === '/stepup/verify') return { ok: true };
      if (path === '/admins/me/console-key') {
        if (enrolFirstError && !(opts && opts.stepUp)) throw enrolFirstError;
        return { keyId: 'new-1' };
      }
      throw new Error('unexpected ' + path);
    },
    async get(path) {
      log.push(['GET', path]);
      const next = statuses.shift();
      if (next instanceof Error) throw next;
      return next;
    },
  };
}

const baseChallenge = { challengeId: 'c-1', action: 'delegation.install', target: 't', deviceId: 'srv-1', nonce: 'n', expiresAtMs: 1, proofBytes: 'AA==' };
const noSleep = async () => {};

test('without an approver the flow is the browser key alone and never polls', async () => {
  const api = fakeApi({ challenge: baseChallenge });
  const keyStore = fakeKeys();
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore, sleep: noSleep });
  assert.equal(await stepUp.obtain('delegation.install', 't'), 'c-1');
  assert.equal(keyStore.calls.sign, 1);
  assert.equal(api.log.filter((c) => c[0] === 'GET').length, 0);
});

test('with an approver the flow shows the code, polls until the phone approved, then returns the id', async () => {
  const api = fakeApi({
    challenge: { ...baseChallenge, approvalRequired: true, approvalCode: '396796' },
    statuses: [
      { verified: true, approvalRequired: true, approved: false },
      { verified: true, approvalRequired: true, approved: false },
      { verified: true, approvalRequired: true, approved: true },
    ],
  });
  const shown = [];
  let closed = 0;
  const stepUp = createStepUp({
    api,
    getUsername: () => 'ada',
    keyStore: fakeKeys(),
    sleep: noSleep,
    onApproval: (info) => {
      shown.push(info);
      return { cancelled: () => false, close: () => (closed += 1) };
    },
  });
  assert.equal(await stepUp.obtain('delegation.install', 't'), 'c-1');
  assert.equal(shown.length, 1);
  assert.equal(shown[0].code, '396796');
  assert.equal(api.log.filter((c) => c[0] === 'GET').length, 3);
  assert.equal(closed, 1, 'the dialog is closed once the phone approved');
});

test('cancelling the dialog stops the wait with reason cancelled and closes the dialog', async () => {
  const api = fakeApi({
    challenge: { ...baseChallenge, approvalRequired: true, approvalCode: '111111' },
    statuses: [{ approved: false }, { approved: false }],
  });
  let polls = 0;
  let closed = 0;
  const stepUp = createStepUp({
    api,
    getUsername: () => 'ada',
    keyStore: fakeKeys(),
    sleep: noSleep,
    onApproval: () => ({ cancelled: () => polls >= 1, close: () => (closed += 1) }),
  });
  const origGet = api.get;
  api.get = async (p) => {
    polls += 1;
    return origGet(p);
  };
  await assert.rejects(stepUp.obtain('delegation.install', 't'), (e) => e instanceof StepUpError && e.reason === 'cancelled');
  assert.equal(closed, 1);
});

test('a challenge that expired on the hub (404) is an approval timeout', async () => {
  const api = fakeApi({
    challenge: { ...baseChallenge, approvalRequired: true, approvalCode: '222222' },
    statuses: [new ApiError({ status: 404, code: 'ERR_NOT_FOUND' })],
  });
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore: fakeKeys(), sleep: noSleep });
  await assert.rejects(stepUp.obtain('delegation.install', 't'), (e) => e instanceof StepUpError && e.reason === 'approval-timeout');
});

test('the local wait ceiling ends a phone that never answers', async () => {
  const api = fakeApi({ challenge: { ...baseChallenge, approvalRequired: true, approvalCode: '333333' }, statuses: Array(50).fill({ approved: false }) });
  let clock = 0;
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore: fakeKeys(), sleep: async () => (clock += 60_000), now: () => clock });
  await assert.rejects(stepUp.obtain('delegation.install', 't'), (e) => e instanceof StepUpError && e.reason === 'approval-timeout');
});

test('hub refusals about the phone become their own reasons, not "set up a key"', async () => {
  for (const [code, reason] of [['ERR_APPROVER_UNAVAILABLE', 'approver-unavailable'], ['ERR_NO_APPROVER', 'no-approver']]) {
    const api = fakeApi({ challengeError: new ApiError({ status: 403, code }) });
    const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore: fakeKeys(), sleep: noSleep });
    await assert.rejects(stepUp.obtain('delegation.install', 't'), (e) => e instanceof StepUpError && e.reason === reason);
    let asked = 0;
    await assert.rejects(stepUp.run({ action: 'delegation.install', target: 't', needKey: async () => (asked += 1, true) }, async () => 'x'));
    assert.equal(asked, 0, reason + ' must not open the key set-up dialog');
  }
});

test('signer replacement is phone-only: no key load, no browser signature, no verify call', async () => {
  const api = fakeApi({
    challenge: { ...baseChallenge, action: 'admin.signer.replace', approvalRequired: true, approvalCode: '444444', browserKeyRequired: false },
    statuses: [{ approved: true }],
  });
  const keyStore = fakeKeys({ record: null });
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore, sleep: noSleep, onApproval: () => ({ cancelled: () => false, close() {} }) });
  assert.equal(await stepUp.obtain('admin.signer.replace', 'console-key'), 'c-1');
  assert.equal(keyStore.calls.load, 0);
  assert.equal(keyStore.calls.sign, 0);
  assert.equal(api.log.filter((c) => c[1] === '/stepup/verify').length, 0);
});

test('enrol with a bound phone: the hub asks for step-up, the phone approves, the retry carries the id', async () => {
  const api = fakeApi({
    challenge: { ...baseChallenge, action: 'admin.signer.replace', approvalRequired: true, approvalCode: '555555', browserKeyRequired: false },
    statuses: [{ approved: true }],
    enrolFirstError: new ApiError({ status: 403, code: 'ERR_STEP_UP' }),
  });
  const keyStore = fakeKeys({ record: null });
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore, sleep: noSleep, onApproval: () => ({ cancelled: () => false, close() {} }) });
  const rec = await stepUp.enrol({ username: 'ada', code: '123456', label: 'Laptop' });
  assert.equal(rec.keyId, 'new-1');
  const posts = api.log.filter((c) => c[1] === '/admins/me/console-key');
  assert.equal(posts.length, 2);
  assert.equal(posts[1][3].stepUp, 'c-1');
  assert.equal(keyStore.calls.saved.length, 1);
});

test('enrol without a phone posts once and saves the key', async () => {
  const api = fakeApi({ challenge: baseChallenge });
  const keyStore = fakeKeys({ record: null });
  const stepUp = createStepUp({ api, getUsername: () => 'ada', keyStore, sleep: noSleep });
  await stepUp.enrol({ username: 'ada', code: '123456', label: 'Laptop' });
  assert.equal(api.log.filter((c) => c[1] === '/admins/me/console-key').length, 1);
  assert.equal(keyStore.calls.saved.length, 1);
});
