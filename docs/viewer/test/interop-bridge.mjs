import assert from "node:assert/strict";
import fs from "node:fs";
import {
  base64Decode,
  base64Encode,
  createEnvelope,
  decryptAesGcm,
  deriveEcdhKey,
  deriveOtpKey,
  generateIdentityKeyPair,
  importPrivateKeysFromPkcs8,
  utf8Decode,
  verifyPayload
} from "../src/crypto.js";
import { createEnvelopeProcessor } from "../src/signaling.js";

const stdinStr = fs.readFileSync(0, "utf8");
const input = JSON.parse(stdinStr);

if (process.argv.includes("--verify-answer")) {
  const { now, pbId, paId, paPubB64, pbPkcs8B64, answerEnvelope } = input;
  const { ecdsaPrivateKey, ecdhPrivateKey } = await importPrivateKeysFromPkcs8(base64Decode(pbPkcs8B64));
  const processor = createEnvelopeProcessor({
    getIdentity: async () => ({
      deviceId: pbId,
      ecdsaPrivateKey,
      ecdhPrivateKey
    }),
    getPairedCamera: async (id) => (id === paId ? { deviceId: paId, publicKeyBase64: paPubB64 } : null),
    nowFn: () => now
  });
  const res = await processor.processIncoming(answerEnvelope);
  assert.ok(res, "Answer envelope must decrypt and verify");
  assert.equal(res.usedOtpKey, false);
  assert.equal(res.message.type, "ANSWER");
  process.stdout.write(JSON.stringify(res.message));
  process.exit(0);
}

// 1. Verify OTP HKDF matches Android CryptoUtils bit-for-bit
const derivedOtpKey = await deriveOtpKey(input.otp);
assert.equal(base64Encode(derivedOtpKey), input.otpKeyB64, "OTP HKDF key mismatch");

// 2. Verify raw AES-GCM decryption
const decRaw = utf8Decode(
  await decryptAesGcm(input.rawCipher, input.rawIv, input.rawTag, base64Decode(input.rawAesKeyB64))
);
assert.equal(decRaw, input.rawPlain, "Raw AES-GCM plaintext mismatch");

// 3. Verify raw ECDSA ASN.1 DER signature from Android
const sigOk = await verifyPayload(input.rawPlain, input.rawSig, input.paPubB64);
assert.equal(sigOk, true, "Android ECDSA DER signature failed in WebCrypto");

// 4. Verify PAIR_CONFIRM envelope via EnvelopeProcessor (OTP + PoP)
const pbKp = await generateIdentityKeyPair({ keepPkcs8: true });
const processor = createEnvelopeProcessor({
  getIdentity: async () => ({
    deviceId: input.pbId,
    ecdsaPrivateKey: pbKp.ecdsaPrivateKey,
    ecdhPrivateKey: pbKp.ecdhPrivateKey
  }),
  getPairedCamera: async () => null,
  nowFn: () => input.now
});
processor.setActivePairingOtp(input.otp, input.paId);
const processedConfirm = await processor.processIncoming(input.confirmEnvelope);
assert.ok(processedConfirm, "PAIR_CONFIRM envelope failed verification");
assert.equal(processedConfirm.usedOtpKey, true);
assert.equal(processedConfirm.message.type, "PAIR_CONFIRM");
assert.equal(processedConfirm.message.paDeviceId, input.paId);
assert.equal(processedConfirm.message.paPublicKeyBase64, input.paPubB64);

// 5. Generate Web -> Android ECDH key, PAIR_REQUEST, and OFFER envelopes
const webEcdhKey = await deriveEcdhKey(pbKp.ecdhPrivateKey, input.paPubB64);
const pairRequestEnvelope = await createEnvelope({
  senderId: input.pbId,
  recipientId: input.paId,
  plaintextJson: JSON.stringify({
    type: "PAIR_REQUEST",
    pbDeviceId: input.pbId,
    pbName: "iPhone Safari",
    pbPublicKeyBase64: pbKp.publicKeySpkiBase64
  }),
  keyBytes: derivedOtpKey,
  ecdsaPrivateKey: pbKp.ecdsaPrivateKey,
  timestamp: input.now
});

const offerEnvelope = await createEnvelope({
  senderId: input.pbId,
  recipientId: input.paId,
  plaintextJson: JSON.stringify({
    type: "OFFER",
    sdp: "v=0\r\no=- 42 2 IN IP4 127.0.0.1"
  }),
  keyBytes: webEcdhKey,
  ecdsaPrivateKey: pbKp.ecdsaPrivateKey,
  timestamp: input.now + 500
});

process.stdout.write(
  JSON.stringify({
    verifiedAndroidVectors: true,
    pbPubB64: pbKp.publicKeySpkiBase64,
    pbPkcs8B64: base64Encode(new Uint8Array(pbKp.privateKeyPkcs8)),
    webEcdhKeyB64: base64Encode(webEcdhKey),
    pairRequestEnvelope,
    offerEnvelope
  })
);
