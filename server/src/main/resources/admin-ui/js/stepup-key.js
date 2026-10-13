// The browser's step-up key: a non-extractable ECDSA P-256 key made with WebCrypto and kept in IndexedDB (the CryptoKey object itself, so
// the private key bytes are never available to script, including injected script: it can only ask the browser to SIGN while the page
// runs). The public half is registered with the hub together with a proof of possession. WebCrypto signs as P1363 (r || s); the hub
// accepts that and DER (ADR-125). This needs a secure context (HTTPS or localhost) and IndexedDB.

import { bytesToBase64, base64ToBytes, proofMessage, consoleKeyId } from './codec.js';

const DB = 'flash-hub-console';
const STORE = 'stepup-keys';
const ALGO = { name: 'ECDSA', namedCurve: 'P-256' };
const SIGN = { name: 'ECDSA', hash: 'SHA-256' };

export function isSupported() {
  const g = globalThis;
  return !!(g.isSecureContext && g.crypto && g.crypto.subtle && g.indexedDB);
}

function openDb() {
  return new Promise((resolve, reject) => {
    const req = globalThis.indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'username' });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error('IndexedDB unavailable'));
  });
}

async function tx(mode, fn) {
  const db = await openDb();
  try {
    return await new Promise((resolve, reject) => {
      const t = db.transaction(STORE, mode);
      const store = t.objectStore(STORE);
      const req = fn(store);
      t.oncomplete = () => resolve(req ? req.result : undefined);
      t.onerror = () => reject(t.error);
      t.onabort = () => reject(t.error);
    });
  } finally {
    db.close();
  }
}

/** The stored key record `{username, keyId, publicKey, privateKey, label, createdAt}` or null. */
export async function loadKey(username) {
  if (!isSupported()) return null;
  try {
    return (await tx('readonly', (s) => s.get(username))) || null;
  } catch (e) {
    return null;
  }
}

export async function saveKey(record) {
  await tx('readwrite', (s) => s.put(record));
}

export async function removeKey(username) {
  try {
    await tx('readwrite', (s) => s.delete(username));
  } catch (e) {
    /* nothing to remove */
  }
}

/** Generates a key and the proof of possession for [username]. The key is NOT stored yet: store it after the hub accepted it. */
export async function createKey(username, label) {
  const pair = await globalThis.crypto.subtle.generateKey(ALGO, false, ['sign', 'verify']);
  const spki = bytesToBase64(new Uint8Array(await globalThis.crypto.subtle.exportKey('spki', pair.publicKey)));
  const proof = bytesToBase64(new Uint8Array(await globalThis.crypto.subtle.sign(SIGN, pair.privateKey, proofMessage(username, spki))));
  const keyId = await consoleKeyId(spki);
  return { record: { username, keyId, publicKey: spki, privateKey: pair.privateKey, label: label || '', createdAt: Date.now() }, publicKey: spki, proof, keyId };
}

/** Signs the base64 `proofBytes` of a step-up challenge; returns base64 of the 64-byte P1363 signature. */
export async function signChallenge(record, proofBytesBase64) {
  const sig = await globalThis.crypto.subtle.sign(SIGN, record.privateKey, base64ToBytes(proofBytesBase64));
  return bytesToBase64(new Uint8Array(sig));
}
