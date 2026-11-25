const encoder = new TextEncoder();
const decoder = new TextDecoder();

function subtle() {
  const s = globalThis.crypto?.subtle;
  if (!s) {
    throw new Error("WebCrypto SubtleCrypto is unavailable (HTTPS or localhost required)");
  }
  return s;
}

export function base64Encode(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let bin = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < u8.length; i += chunkSize) {
    bin += String.fromCharCode(...u8.subarray(i, i + chunkSize));
  }
  return btoa(bin);
}

export function base64Decode(b64) {
  const normalized = String(b64).trim();
  const bin = atob(normalized);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) {
    out[i] = bin.charCodeAt(i);
  }
  return out;
}

export function base64UrlEncode(bytes) {
  return base64Encode(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function base64UrlDecode(b64Url) {
  let s = String(b64Url).trim().replace(/-/g, "+").replace(/_/g, "/");
  const mod = s.length % 4;
  if (mod === 2) s += "==";
  else if (mod === 3) s += "=";
  else if (mod === 1) throw new Error("Invalid base64url length");
  return base64Decode(s);
}

export function randomBytes(len) {
  const buf = new Uint8Array(len);
  globalThis.crypto.getRandomValues(buf);
  return buf;
}

export function randomHex(byteLen = 16) {
  const bytes = randomBytes(byteLen);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, "0");
  }
  return hex;
}

export function randomUuid() {
  if (typeof globalThis.crypto?.randomUUID === "function") {
    return globalThis.crypto.randomUUID();
  }
  const b = randomBytes(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (v) => v.toString(16).padStart(2, "0")).join("");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/**
 * Generates an EC NIST P-256 identity keypair once, imports non-extractable ECDSA (sign)
 * and ECDH (deriveBits) handles, and discards the raw PKCS#8 export unless caller requests it.
 */
export async function generateIdentityKeyPair({ keepPkcs8 = false } = {}) {
  const kp = await subtle().generateKey(
    { name: "ECDH", namedCurve: "P-256" },
    true,
    ["deriveBits"]
  );
  const spkiBuf = await subtle().exportKey("spki", kp.publicKey);
  const pkcs8Buf = await subtle().exportKey("pkcs8", kp.privateKey);
  const pkcs8Bytes = new Uint8Array(pkcs8Buf);
  const { ecdsaPrivateKey, ecdhPrivateKey } = await importPrivateKeysFromPkcs8(pkcs8Bytes);
  const publicKeySpkiBase64 = base64Encode(new Uint8Array(spkiBuf));
  if (!keepPkcs8) {
    pkcs8Bytes.fill(0);
    return { ecdsaPrivateKey, ecdhPrivateKey, publicKeySpkiBase64 };
  }
  return {
    ecdsaPrivateKey,
    ecdhPrivateKey,
    publicKeySpkiBase64,
    privateKeyPkcs8: pkcs8Bytes.buffer.slice(0)
  };
}

export async function importPrivateKeysFromPkcs8(pkcs8Input) {
  const bytes = pkcs8Input instanceof Uint8Array ? pkcs8Input : new Uint8Array(pkcs8Input);
  const ecdsaPrivateKey = await subtle().importKey(
    "pkcs8",
    bytes,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"]
  );
  const ecdhPrivateKey = await subtle().importKey(
    "pkcs8",
    bytes,
    { name: "ECDH", namedCurve: "P-256" },
    false,
    ["deriveBits"]
  );
  return { ecdsaPrivateKey, ecdhPrivateKey };
}

export async function importPublicKeySpki(base64Str, algorithm) {
  const spkiBytes = base64Decode(base64Str);
  if (algorithm === "ECDSA") {
    return subtle().importKey(
      "spki",
      spkiBytes,
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["verify"]
    );
  }
  if (algorithm === "ECDH") {
    return subtle().importKey(
      "spki",
      spkiBytes,
      { name: "ECDH", namedCurve: "P-256" },
      true,
      []
    );
  }
  throw new Error(`Unsupported algorithm: ${algorithm}`);
}

/**
 * Bit-for-bit equivalent of CryptoUtils.hkdfSha256(secret, ByteArray(0), info, length):
 * When salt is empty, CryptoUtils uses `secret` directly as the HMAC-SHA256 PRK (HKDF-Expand only).
 * Do NOT use WebCrypto's built-in "HKDF" algorithm, which always runs HKDF-Extract even on empty salt.
 */
export async function hkdfExpandOnlySha256(secretBytes, infoStrOrBytes, length = 32) {
  const prkBytes = secretBytes instanceof Uint8Array ? secretBytes : new Uint8Array(secretBytes);
  const infoBytes =
    typeof infoStrOrBytes === "string"
      ? encoder.encode(infoStrOrBytes)
      : new Uint8Array(infoStrOrBytes);

  const hmacKey = await subtle().importKey(
    "raw",
    prkBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const okm = new Uint8Array(length);
  let offset = 0;
  let prev = new Uint8Array(0);
  let counter = 1;

  while (offset < length) {
    const input = new Uint8Array(prev.length + infoBytes.length + 1);
    input.set(prev, 0);
    input.set(infoBytes, prev.length);
    input[prev.length + infoBytes.length] = counter & 0xff;
    const block = new Uint8Array(await subtle().sign("HMAC", hmacKey, input));
    const take = Math.min(block.length, length - offset);
    okm.set(block.subarray(0, take), offset);
    offset += take;
    prev = block;
    counter += 1;
  }
  return okm;
}

export async function deriveOtpKey(otpString) {
  return hkdfExpandOnlySha256(encoder.encode(otpString), "CamMonitor-OTP", 32);
}

const ecdhCache = new WeakMap();

export async function deriveEcdhKey(ecdhPrivateKey, remotePublicKeyBase64) {
  let perPriv = ecdhCache.get(ecdhPrivateKey);
  if (!perPriv) {
    perPriv = new Map();
    ecdhCache.set(ecdhPrivateKey, perPriv);
  }
  const cached = perPriv.get(remotePublicKeyBase64);
  if (cached) {
    return cached.slice();
  }

  const remotePub = await importPublicKeySpki(remotePublicKeyBase64, "ECDH");
  const sharedBits = await subtle().deriveBits(
    { name: "ECDH", public: remotePub },
    ecdhPrivateKey,
    256
  );
  const derived = await hkdfExpandOnlySha256(new Uint8Array(sharedBits), "CamMonitor-E2EE", 32);
  if (perPriv.size >= 64) {
    perPriv.clear();
  }
  perPriv.set(remotePublicKeyBase64, derived);
  return derived.slice();
}

export async function encryptAesGcm(plaintextInput, keyBytes) {
  const plaintext =
    typeof plaintextInput === "string"
      ? encoder.encode(plaintextInput)
      : new Uint8Array(plaintextInput);
  const iv = randomBytes(12);
  const aesKey = await subtle().importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["encrypt"]);
  const cipherWithTag = new Uint8Array(
    await subtle().encrypt({ name: "AES-GCM", iv, tagLength: 128 }, aesKey, plaintext)
  );
  const splitIdx = cipherWithTag.length - 16;
  const ciphertext = cipherWithTag.subarray(0, splitIdx);
  const tag = cipherWithTag.subarray(splitIdx);
  return {
    ciphertext: base64Encode(ciphertext),
    iv: base64Encode(iv),
    tag: base64Encode(tag)
  };
}

export async function decryptAesGcm(ciphertextB64, ivB64, tagB64, keyBytes) {
  const ciphertext = base64Decode(ciphertextB64);
  const iv = base64Decode(ivB64);
  const tag = base64Decode(tagB64);
  if (iv.length !== 12 || tag.length !== 16) {
    throw new Error("Invalid AES-GCM IV or tag length");
  }
  const combined = new Uint8Array(ciphertext.length + tag.length);
  combined.set(ciphertext, 0);
  combined.set(tag, ciphertext.length);

  const aesKey = await subtle().importKey("raw", keyBytes, { name: "AES-GCM" }, false, ["decrypt"]);
  const plainBuf = await subtle().decrypt(
    { name: "AES-GCM", iv, tagLength: 128 },
    aesKey,
    combined
  );
  return new Uint8Array(plainBuf);
}

function encodeDerInteger(raw32) {
  let i = 0;
  while (i < raw32.length - 1 && raw32[i] === 0) {
    i++;
  }
  const needsZeroPad = (raw32[i] & 0x80) !== 0;
  const bodyLen = raw32.length - i + (needsZeroPad ? 1 : 0);
  const out = new Uint8Array(2 + bodyLen);
  out[0] = 0x02;
  out[1] = bodyLen;
  let pos = 2;
  if (needsZeroPad) {
    out[pos++] = 0x00;
  }
  out.set(raw32.subarray(i), pos);
  return out;
}

/**
 * Converts a 64-byte IEEE P1363 ECDSA P-256 signature (r || s) into ASN.1 DER format.
 */
export function p1363ToDer(p1363Bytes) {
  const sig = p1363Bytes instanceof Uint8Array ? p1363Bytes : new Uint8Array(p1363Bytes);
  if (sig.length !== 64) {
    throw new Error(`Expected 64-byte P-256 IEEE P1363 signature, got ${sig.length}`);
  }
  const rDer = encodeDerInteger(sig.subarray(0, 32));
  const sDer = encodeDerInteger(sig.subarray(32, 64));
  const seqLen = rDer.length + sDer.length;
  const header = seqLen < 128 ? new Uint8Array([0x30, seqLen]) : new Uint8Array([0x30, 0x81, seqLen]);
  const out = new Uint8Array(header.length + seqLen);
  out.set(header, 0);
  out.set(rDer, header.length);
  out.set(sDer, header.length + rDer.length);
  return out;
}

function readDerInteger(der, offset) {
  if (offset + 2 > der.length || der[offset] !== 0x02) {
    throw new Error("Invalid ASN.1 INTEGER tag");
  }
  const len = der[offset + 1];
  const valStart = offset + 2;
  const valEnd = valStart + len;
  if (len === 0 || (len & 0x80) !== 0 || valEnd > der.length) {
    throw new Error("Invalid ASN.1 INTEGER length");
  }
  // Reject negative integers or non-minimal DER padding
  if ((der[valStart] & 0x80) !== 0) {
    throw new Error("Negative ASN.1 INTEGER");
  }
  if (len > 1 && der[valStart] === 0x00 && (der[valStart + 1] & 0x80) === 0) {
    throw new Error("Non-minimal ASN.1 INTEGER leading zero");
  }
  let sliceStart = valStart;
  if (der[sliceStart] === 0x00) {
    sliceStart += 1;
  }
  const magnitude = der.subarray(sliceStart, valEnd);
  if (magnitude.length > 32) {
    throw new Error("ASN.1 INTEGER exceeds 32 bytes for P-256");
  }
  const out = new Uint8Array(32);
  out.set(magnitude, 32 - magnitude.length);
  return { value: out, nextOffset: valEnd };
}

/**
 * Converts an ASN.1 DER ECDSA P-256 signature into 64-byte IEEE P1363 (r || s).
 */
export function derToP1363(derBytes) {
  const der = derBytes instanceof Uint8Array ? derBytes : new Uint8Array(derBytes);
  if (der.length < 8 || der[0] !== 0x30) {
    throw new Error("Invalid ASN.1 DER SEQUENCE");
  }
  let seqLen = der[1];
  let pos = 2;
  if ((seqLen & 0x80) !== 0) {
    const lenBytes = seqLen & 0x7f;
    if (lenBytes !== 1 || der.length < 3) {
      throw new Error("Invalid ASN.1 DER SEQUENCE length");
    }
    seqLen = der[2];
    pos = 3;
  }
  if (pos + seqLen !== der.length) {
    throw new Error("ASN.1 DER SEQUENCE length mismatch or trailing bytes");
  }
  const r = readDerInteger(der, pos);
  const s = readDerInteger(der, r.nextOffset);
  if (s.nextOffset !== der.length) {
    throw new Error("Trailing bytes after ECDSA s component");
  }
  const out = new Uint8Array(64);
  out.set(r.value, 0);
  out.set(s.value, 32);
  return out;
}

export function buildSignatureInput(senderId, recipientId, timestamp, nonce, ciphertext) {
  return `${senderId}${recipientId}${timestamp}${nonce}${ciphertext}`;
}

export async function signPayload(payloadStr, ecdsaPrivateKey) {
  const p1363Buf = await subtle().sign(
    { name: "ECDSA", hash: "SHA-256" },
    ecdsaPrivateKey,
    encoder.encode(payloadStr)
  );
  return base64Encode(p1363ToDer(new Uint8Array(p1363Buf)));
}

export async function verifyPayload(payloadStr, signatureB64, publicKeyBase64) {
  try {
    const pubKey = await importPublicKeySpki(publicKeyBase64, "ECDSA");
    const derBytes = base64Decode(signatureB64);
    const p1363 = derToP1363(derBytes);
    return await subtle().verify(
      { name: "ECDSA", hash: "SHA-256" },
      pubKey,
      p1363,
      encoder.encode(payloadStr)
    );
  } catch (_) {
    return false;
  }
}

export async function createEnvelope({
  senderId,
  recipientId,
  plaintextJson,
  keyBytes,
  ecdsaPrivateKey,
  timestamp = Date.now()
}) {
  const nonce = randomHex(16);
  const { ciphertext, iv, tag } = await encryptAesGcm(plaintextJson, keyBytes);
  const sigInput = buildSignatureInput(senderId, recipientId, timestamp, nonce, ciphertext);
  const signature = await signPayload(sigInput, ecdsaPrivateKey);
  return {
    senderId,
    recipientId,
    timestamp,
    nonce,
    ciphertext,
    iv,
    tag,
    signature
  };
}

export async function verifyEnvelopeSignature(envelope, publicKeyBase64) {
  if (!envelope || !publicKeyBase64) return false;
  const sigInput = buildSignatureInput(
    envelope.senderId,
    envelope.recipientId,
    envelope.timestamp,
    envelope.nonce,
    envelope.ciphertext
  );
  return verifyPayload(sigInput, envelope.signature, publicKeyBase64);
}

export function utf8Decode(bytes) {
  return decoder.decode(bytes);
}
