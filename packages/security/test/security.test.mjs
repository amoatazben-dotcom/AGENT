import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { encryptSecret, decryptSecret, maskApiKey, redactSecrets } from "../dist/index.js";

describe("security", () => {
  it("encrypt/decrypt roundtrip", () => {
    const enc = encryptSecret("sk-test-12345");
    assert.ok(!enc.includes("sk-test-12345"), "ciphertext must not contain plaintext");
    assert.equal(decryptSecret(enc), "sk-test-12345");
  });

  it("maskApiKey never returns full key", () => {
    const masked = maskApiKey("sk-proj-abcdef1234567890XYZ");
    assert.ok(!masked.includes("abcdef"), masked);
    assert.ok(masked.endsWith("9XYZ") || masked.endsWith("0XYZ") || masked.includes("••"), masked);
    assert.equal(maskApiKey(""), "");
  });

  it("redactSecrets strips keys, bearers, passwords", () => {
    const raw = 'POST /x Authorization: Bearer abcdefghijklmnop12345 key=sk-proj-SECRETVALUE123 password: hunter2secret api_key="AIzaABCDEF1234567890abcdef12345678901"';
    const out = redactSecrets(raw);
    assert.ok(!out.includes("abcdefghijklmnop"), out);
    assert.ok(!out.includes("SECRETVALUE"), out);
    assert.ok(!out.includes("hunter2secret"), out);
    assert.ok(out.includes("[REDACTED]"), out);
  });
});
