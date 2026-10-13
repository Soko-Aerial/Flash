// Small byte and text codecs shared by the step-up key code and the tests. No DOM access.

export function bytesToBase64(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let bin = '';
  const step = 0x8000;
  for (let i = 0; i < u8.length; i += step) bin += String.fromCharCode.apply(null, u8.subarray(i, i + step));
  return btoa(bin);
}

export function base64ToBytes(b64) {
  const bin = atob(String(b64).trim());
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) out[i] = bin.charCodeAt(i);
  return out;
}

export function utf8(text) {
  return new TextEncoder().encode(String(text));
}

export function toHex(bytes) {
  return Array.from(bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');
}

/** The exact text a browser signs to prove it holds a new step-up key: tag, user name and the base64 SPKI, one per line. */
export const PROOF_TAG = 'flash-console-key-v1';
export function proofMessage(username, spkiBase64) {
  return utf8(PROOF_TAG + '\n' + username + '\n' + spkiBase64);
}

/** The id the hub gives a console key: `console-` plus the first 16 hex digits of SHA-256 over the UTF-8 of the base64 SPKI. */
export async function consoleKeyId(spkiBase64, subtle = globalThis.crypto.subtle) {
  const digest = await subtle.digest('SHA-256', utf8(spkiBase64));
  return 'console-' + toHex(new Uint8Array(digest)).slice(0, 16);
}
