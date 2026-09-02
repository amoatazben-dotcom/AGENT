import * as crypto from "crypto";

const ALGORITHM = "aes-256-gcm";
const IV_LENGTH = 12; // 96 bits for GCM
const TAG_LENGTH = 16;

function getMasterKey(): Buffer {
  const hex = process.env.MASTER_ENCRYPTION_KEY || "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  return Buffer.from(hex.slice(0, 64), "hex");
}

export function encryptSecret(plainText: string): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, getMasterKey(), iv);
  let encrypted = cipher.update(plainText, "utf8", "hex");
  encrypted += cipher.final("hex");
  const authTag = cipher.getAuthTag();

  return `${iv.toString("hex")}:${authTag.toString("hex")}:${encrypted}`;
}

export function decryptSecret(encryptedPayload: string): string {
  const [ivHex, authTagHex, encryptedText] = encryptedPayload.split(":");
  if (!ivHex || !authTagHex || !encryptedText) {
    throw new Error("Invalid encrypted payload format");
  }

  const iv = Buffer.from(ivHex, "hex");
  const authTag = Buffer.from(authTagHex, "hex");
  const decipher = crypto.createDecipheriv(ALGORITHM, getMasterKey(), iv);
  decipher.setAuthTag(authTag);

  let decrypted = decipher.update(encryptedText, "hex", "utf8");
  decrypted += decipher.final("utf8");
  return decrypted;
}

export function hashPassword(password: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const salt = crypto.randomBytes(16).toString("hex");
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) return reject(err);
      resolve(`${salt}:${derivedKey.toString("hex")}`);
    });
  });
}

export function verifyPassword(password: string, hash: string): Promise<boolean> {
  return new Promise((resolve, reject) => {
    const [salt, key] = hash.split(":");
    if (!salt || !key) return resolve(false);
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) return reject(err);
      resolve(crypto.timingSafeEqual(Buffer.from(key, "hex"), derivedKey));
    });
  });
}

export function generateToken(length = 32): string {
  return crypto.randomBytes(length).toString("hex");
}

export function redactSecrets(text: string): string {
  if (!text) return text;
  // Redact common API key patterns (Bearer tokens, sk-..., ai-...)
  return text
    .replace(/(Bearer\s+)[A-Za-z0-9_\-\.]{20,}/gi, "$1[REDACTED]")
    .replace(/(sk-[A-Za-z0-9]{20,})/gi, "sk-[REDACTED]")
    .replace(/(AIza[0-9A-Za-z-_]{35})/g, "AIza[REDACTED]");
}
