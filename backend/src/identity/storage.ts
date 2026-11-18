import { mkdir, readFile, rm, utimes, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { decryptBytes, encryptBytes } from "./crypto";

/**
 * ที่เก็บรูปบัตร/เซลฟี — โฟลเดอร์ private/ แยกจากรูปสินค้า ไม่เปิดผ่าน URL สาธารณะ (ไม่มี static route)
 * ไฟล์ทุกไฟล์เข้ารหัส AES-256-GCM · คอลัมน์ id_card_requests.*_image_key เก็บ path สัมพัทธ์ใต้โฟลเดอร์นี้
 */
export const PRIVATE_DIR = process.env.PRIVATE_DIR ?? join(import.meta.dir, "..", "..", "private");

const full = (key: string) => join(PRIVATE_DIR, key);

export async function saveEncrypted(key: string, bytes: Uint8Array): Promise<void> {
  await mkdir(dirname(full(key)), { recursive: true });
  await writeFile(full(key), encryptBytes(bytes));
}

export async function readDecrypted(key: string): Promise<Buffer | null> {
  try {
    return decryptBytes(await readFile(full(key)));
  } catch {
    return null;
  }
}

/**
 * ประทับเวลาไฟล์เป็น "เวลาที่ตัดสิน" — ตาราง id_card_requests ไม่มีคอลัมน์เวลาตัดสิน (และห้ามเพิ่ม)
 * จึงใช้เวลาแก้ไขไฟล์เป็นตัวนับ 7 วันของงานลบรูป (BR19)
 */
export async function stampDecided(keys: Array<string | null>, at = new Date()): Promise<void> {
  for (const key of keys) {
    if (key) await utimes(full(key), at, at).catch(() => {});
  }
}

export async function removeKeys(keys: Array<string | null>): Promise<void> {
  for (const key of keys) {
    if (key) await rm(full(key), { force: true });
  }
  const first = keys.find(Boolean);
  if (first) await rm(dirname(full(first)), { recursive: true, force: true });
}

export { full as pathOf };
