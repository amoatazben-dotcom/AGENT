import * as crypto from "crypto";

const ALGORITHM = "aes-256-gcm";
const IV_LENGTH = 12; // 96 bits for GCM

/**
 * Retrieves the 32-byte master encryption key from the environment.
 * Throws a clear error if the key is missing in production.
 */
function getMasterKey(): Buffer {
  const hex = process.env.MASTER_ENCRYPTION_KEY;
  if (!hex || hex.trim() === "") {
    if (process.env.NODE_ENV === "production") {
      throw new Error(
        "MASTER_ENCRYPTION_KEY environment variable is required in production. " +
          "Generate one with: node -e \"console.log(require('crypto').randomBytes(32).toString('hex'))\""
      );
    }
    // Development-only fallback — never use in production
    console.warn(
      "[security] WARNING: MASTER_ENCRYPTION_KEY not set. Using insecure development key. Set a real key in production."
    );
    return Buffer.from("dev_fallback_key_change_in_prod_!".repeat(2).slice(0, 64), "utf8").slice(0, 32);
  }
  if (hex.length < 64) {
    throw new Error("MASTER_ENCRYPTION_KEY must be at least 64 hex characters (32 bytes).");
  }
  return Buffer.from(hex.slice(0, 64), "hex");
}

/**
 * Encrypts a plain-text string using AES-256-GCM.
 * Returns a colon-separated string: iv:authTag:ciphertext
 */
export function encryptSecret(plainText: string): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(ALGORITHM, getMasterKey(), iv);
  let encrypted = cipher.update(plainText, "utf8", "hex");
  encrypted += cipher.final("hex");
  const authTag = cipher.getAuthTag();

  return `${iv.toString("hex")}:${authTag.toString("hex")}:${encrypted}`;
}

/**
 * Decrypts a payload produced by encryptSecret.
 */
export function decryptSecret(encryptedPayload: string): string {
  const [ivHex, authTagHex, encryptedText] = encryptedPayload.split(":");
  if (!ivHex || !authTagHex || !encryptedText) {
    throw new Error("Invalid encrypted payload format. Expected iv:authTag:ciphertext.");
  }

  const iv = Buffer.from(ivHex, "hex");
  const authTag = Buffer.from(authTagHex, "hex");
  const decipher = crypto.createDecipheriv(ALGORITHM, getMasterKey(), iv);
  decipher.setAuthTag(authTag);

  let decrypted = decipher.update(encryptedText, "hex", "utf8");
  decrypted += decipher.final("utf8");
  return decrypted;
}

/**
 * Hashes a password using scrypt with a random salt.
 * Returns a colon-separated string: salt:hash
 */
export function hashPassword(password: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const salt = crypto.randomBytes(16).toString("hex");
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) return reject(err);
      resolve(`${salt}:${derivedKey.toString("hex")}`);
    });
  });
}

/**
 * Verifies a password against a stored scrypt hash.
 * Uses crypto.timingSafeEqual to prevent timing attacks.
 */
export function verifyPassword(password: string, hash: string): Promise<boolean> {
  return new Promise((resolve, reject) => {
    const [salt, key] = hash.split(":");
    if (!salt || !key) return resolve(false);
    crypto.scrypt(password, salt, 64, (err, derivedKey) => {
      if (err) return reject(err);
      try {
        resolve(crypto.timingSafeEqual(Buffer.from(key, "hex"), derivedKey));
      } catch {
        resolve(false);
      }
    });
  });
}

/**
 * Generates a cryptographically secure random token.
 * @param length Number of random bytes (output string will be 2x this length in hex)
 */
export function generateToken(length = 32): string {
  return crypto.randomBytes(length).toString("hex");
}

/**
 * Returns a masked representation, e.g. sk-••••••••••93xA.
 * Never returns the full key.
 */
export function maskApiKey(apiKey: string): string {
  if (!apiKey) return "";
  const trimmed = apiKey.trim();
  if (trimmed.length <= 8) return "••••••••";
  const prefix = trimmed.startsWith("sk-") ? "sk-" : trimmed.slice(0, 3) + "-";
  const last4 = trimmed.slice(-4);
  return `${prefix}••••••••••${last4}`;
}

/**
 * Redacts known secret patterns from log strings to prevent accidental leaks.
 */
export function redactSecrets(text: string): string {
  if (!text) return text;
  return text
    .replace(/(Bearer\s+)[A-Za-z0-9_\-\.]{8,}/gi, "$1[REDACTED]")
    .replace(/(sk-[A-Za-z0-9_\-]{4,})/gi, "sk-[REDACTED]")
    .replace(/(sk-proj-[A-Za-z0-9_\-]{4,})/gi, "sk-proj-[REDACTED]")
    .replace(/(sk-ant-[A-Za-z0-9_\-]{4,})/gi, "sk-ant-[REDACTED]")
    .replace(/(AIza[0-9A-Za-z-_]{10,})/g, "AIza[REDACTED]")
    .replace(/(xai-[A-Za-z0-9]{10,})/gi, "xai-[REDACTED]")
    .replace(/(["']?(api[_-]?key|authorization|refresh[_-]?token|password|secret)["']?\s*[:=]\s*["']?)([^"',}\s]{6,})/gi, "$1[REDACTED]")
    .replace(/(key[=:]\s*)[^\s&"']{10,}/gi, "$1[REDACTED]");
}
