import { stat } from "node:fs/promises";
import { sql } from "../db";
import { pathOf, removeKeys } from "../identity/storage";

const SEVEN_DAYS = 7 * 24 * 60 * 60 * 1000;

/**
 * ลบรูปบัตร/เซลฟีของคำขอที่ตัดสินแล้ว (อนุมัติหรือปฏิเสธ) เมื่อครบ 7 วัน (BR19, PDPA ม.22)
 * เวลาตัดสินอ่านจากเวลาแก้ไขไฟล์ (ดู stampDecided) · ไฟล์ที่หายไปแล้วนับว่าครบกำหนด
 * คืนจำนวนคำขอที่ล้างรูป
 */
export async function purgeDecidedImages(
  olderThanMs = SEVEN_DAYS,
  now = Date.now(),
): Promise<number> {
  const rows = await sql`
    SELECT id, card_image_key, selfie_image_key FROM id_card_requests
    WHERE status <> 'PENDING' AND (card_image_key IS NOT NULL OR selfie_image_key IS NOT NULL)
  `;

  let purged = 0;
  for (const row of rows) {
    const keys: Array<string | null> = [row.card_image_key, row.selfie_image_key];
    let allOld = true;
    for (const key of keys) {
      if (!key) continue;
      try {
        if (now - (await stat(pathOf(key))).mtimeMs < olderThanMs) allOld = false;
      } catch {
        // ไฟล์ไม่อยู่แล้ว — ถือว่าลบได้
      }
    }
    if (!allOld) continue;

    await removeKeys(keys);
    await sql`
      UPDATE id_card_requests SET card_image_key = NULL, selfie_image_key = NULL WHERE id = ${row.id}
    `;
    purged++;
  }
  return purged;
}

/** รันตอนเริ่ม server และทุกชั่วโมง (เรียกจาก index.ts เท่านั้น เพื่อไม่ให้ timer รบกวนตอนเทสต์) */
export function startPurgeJob(): void {
  const run = () => purgeDecidedImages().catch((e) => console.error("[purge]", e));
  void run();
  setInterval(run, 60 * 60 * 1000).unref();
}
