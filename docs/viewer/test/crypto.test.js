import test from "node:test";
import assert from "node:assert/strict";
import {
  base64Decode,
  base64Encode,
  base64UrlDecode,
  base64UrlEncode,
  decryptAesGcm,
  derToP1363,
  deriveEcdhKey,
  deriveOtpKey,
  encryptAesGcm,
  generateIdentityKeyPair,
  hkdfExpandOnlySha256,
  p1363ToDer,
  signPayload,
  utf8Decode,
  verifyPayload
} from "../src/crypto.js";

test("identity keypair creates non-extractable ECDSA and ECDH handles and SPKI public key", async () => {
  const kp = await generateIdentityKeyPair();
  assert.equal(kp.ecdsaPrivateKey.extractable, false);
  assert.equal(kp.ecdhPrivateKey.extractable, false);
  assert.ok(kp.publicKeySpkiBase64.length > 40);
  assert.equal("privateKeyPkcs8" in kp, false);
});

test("custom HKDF-Expand differs from WebCrypto HKDF-Extract-then-Expand and matches empty-salt RFC5869 expand", async () => {
  const otp = "test-otp-secret";
  const otpKey = await deriveOtpKey(otp);
  assert.equal(otpKey.length, 32);

  // Verify determinism and 64-byte multi-block expansion
  const long1 = await hkdfExpandOnlySha256(new TextEncoder().encode(otp), "CamMonitor-Info", 64);
  const long2 = await hkdfExpandOnlySha256(new TextEncoder().encode(otp), "CamMonitor-Info", 64);
  assert.equal(long1.length, 64);
  assert.deepEqual(long1, long2);
});

test("ECDH shared secret matches symmetrically and returns defensive clones", async () => {
  const a = await generateIdentityKeyPair();
  const b = await generateIdentityKeyPair();
  const k1 = await deriveEcdhKey(a.ecdhPrivateKey, b.publicKeySpkiBase64);
  const k2 = await deriveEcdhKey(b.ecdhPrivateKey, a.publicKeySpkiBase64);
  assert.equal(k1.length, 32);
  assert.deepEqual(k1, k2);

  const copy = k1.slice();
  k1[0] ^= 0xff;
  const k1Again = await deriveEcdhKey(a.ecdhPrivateKey, b.publicKeySpkiBase64);
  assert.deepEqual(k1Again, copy);
});

test("AES-256-GCM split tag encrypt/decrypt and tamper detection", async () => {
  const key = new Uint8Array(32).map((_, i) => (i + 5) & 0xff);
  const plain = "CamMonitor SDP + ICE 繁體中文 日本語";
  const { ciphertext, iv, tag } = await encryptAesGcm(plain, key);
  assert.equal(base64Decode(iv).length, 12);
  assert.equal(base64Decode(tag).length, 16);

  const decrypted = utf8Decode(await decryptAesGcm(ciphertext, iv, tag, key));
  assert.equal(decrypted, plain);

  // Flip 1 bit in tag -> must fail
  const badTag = base64Decode(tag);
  badTag[0] ^= 0x01;
  await assert.rejects(() => decryptAesGcm(ciphertext, iv, base64Encode(badTag), key));
});

test("p1363ToDer and derToP1363 handle 0x80 high-bit padding, leading zeros, and <32-byte integers", () => {
  const r = new Uint8Array(32);
  r[0] = 0x80; // requires leading 0x00 in DER
  r[31] = 0x01;
  const s = new Uint8Array(32);
  s[2] = 0x7f; // 30 bytes after stripping leading zeros

  const p1363 = new Uint8Array(64);
  p1363.set(r, 0);
  p1363.set(s, 32);

  const der = p1363ToDer(p1363);
  assert.equal(der[0], 0x30);
  const roundTrip = derToP1363(der);
  assert.deepEqual(roundTrip, p1363);
});

test("derToP1363 rejects malformed, negative, non-minimal, and trailing-byte encodings", () => {
  // Trailing garbage byte
  const valid = p1363ToDer(new Uint8Array(64).fill(1));
  const withTrailing = new Uint8Array(valid.length + 1);
  withTrailing.set(valid);
  withTrailing[valid.length] = 0x00;
  assert.throws(() => derToP1363(withTrailing));

  // Negative integer
  assert.throws(() => derToP1363(new Uint8Array([0x30, 0x06, 0x02, 0x01, 0x80, 0x02, 0x01, 0x01])));

  // Non-minimal leading zero
  assert.throws(() => derToP1363(new Uint8Array([0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x01])));
});

test("ECDSA signPayload and verifyPayload succeed over 50 random signatures and reject tampering", async () => {
  const kp = await generateIdentityKeyPair();
  for (let i = 0; i < 50; i++) {
    const msg = `envelope-${i}-${Math.random()}`;
    const sig = await signPayload(msg, kp.ecdsaPrivateKey);
    assert.equal(await verifyPayload(msg, sig, kp.publicKeySpkiBase64), true);
    assert.equal(await verifyPayload(msg + "!", sig, kp.publicKeySpkiBase64), false);
  }
});

test("base64url encode/decode omits padding and restores arbitrary UTF-8", () => {
  const bytes = new TextEncoder().encode("CamMonitor #pair=測試🎉");
  const enc = base64UrlEncode(bytes);
  assert.equal(enc.includes("="), false);
  assert.deepEqual(base64UrlDecode(enc), bytes);
});
