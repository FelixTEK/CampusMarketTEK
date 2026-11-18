import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";
import { env } from "../env";

/**
 * เข้ารหัสไฟล์ด้วย AES-256-GCM: ผลลัพธ์ = iv(12) + authTag(16) + ciphertext
 * GCM ตรวจความถูกต้องด้วย: ไฟล์ถูกแก้ไขแล้วถอดรหัสไม่ผ่าน
 */
export function encryptBytes(plain: Uint8Array, key: Buffer = env.encryptionKey): Buffer {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const body = Buffer.concat([cipher.update(plain), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]);
}

export function decryptBytes(packed: Uint8Array, key: Buffer = env.encryptionKey): Buffer {
  const buf = Buffer.from(packed);
  const decipher = createDecipheriv("aes-256-gcm", key, buf.subarray(0, 12));
  decipher.setAuthTag(buf.subarray(12, 28));
  return Buffer.concat([decipher.update(buf.subarray(28)), decipher.final()]);
}

/** ค่าแฮชของเลขบัตร (HMAC-SHA256 + กุญแจลับ) — เก็บแค่นี้ ไม่เก็บเลขจริง ใช้กัน 1 คนหลายบัญชี */
export function hashNationalId(nationalId: string, secret: Buffer = env.idHashSecret): string {
  return createHmac("sha256", secret).update(nationalId).digest("hex");
}

/** เลขบัตรประชาชนไทย 13 หลัก พร้อมตรวจเลขหลักที่ 13 (checksum) */
export function isValidThaiNationalId(id: string): boolean {
  if (!/^\d{13}$/.test(id)) return false;
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(id[i]) * (13 - i);
  return (11 - (sum % 11)) % 10 === Number(id[12]);
}

/** ตรวจชนิดไฟล์จากไบต์แรกจริง ๆ (ไม่เชื่อ Content-Type ที่ client ส่งมา) */
export function sniffImageType(bytes: Uint8Array): "image/jpeg" | "image/png" | null {
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return "image/png";
  return null;
}
