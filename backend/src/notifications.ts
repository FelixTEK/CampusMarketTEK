import type { SQL } from "bun";

/**
 * สร้างแถวแจ้งเตือน — เรียกใน transaction เดียวกับเหตุการณ์ที่ทำให้เกิดแจ้งเตือน
 * (เหตุการณ์สำเร็จ = แจ้งเตือนมี · เหตุการณ์ย้อนกลับ = แจ้งเตือนย้อนกลับด้วย)
 */
export async function notify(db: SQL, userId: string, type: string, body: string, refId?: string): Promise<void> {
  await db`INSERT INTO notifications (user_id, type, body, ref_id) VALUES (${userId}, ${type}, ${body}, ${refId ?? null})`;
}
