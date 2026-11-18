import { sql } from "../db";

/**
 * ระยะเก็บข้อมูลตามประกาศความเป็นส่วนตัว (PDPA ม.23): ตัวเลขเป็นข้อเสนอของทีมที่แจ้งผู้ใช้แล้ว
 *  - ข้อความแชต: ห้องที่ข้อความล่าสุดเก่ากว่า 1 ปี → ลบข้อความทั้งห้องและตัวห้อง (ดีลและรีวิวไม่ถูกแตะ)
 *  - รายงาน: ปิดแล้วเกิน 1 ปี → ลบรายงานและสำเนาหลักฐาน (รายงานที่ยังเปิดอยู่ไม่ถูกลบเด็ดขาด)
 * คืนจำนวนที่ลบ (ไว้ให้เทสต์ตรวจ) · now ส่งเข้ามาได้เพื่อทดสอบโดยไม่ต้องรอ 1 ปี
 */
export const RETENTION_MS = 365 * 24 * 60 * 60 * 1000;

export async function purgeOldChats(now = new Date()): Promise<number> {
  const cutoff = new Date(now.getTime() - RETENTION_MS);
  const rooms = await sql`
    SELECT c.id FROM conversations c
    WHERE c.created_at < ${cutoff}
      AND coalesce((SELECT max(m.created_at) FROM messages m WHERE m.conversation_id = c.id), c.created_at) < ${cutoff}
    LIMIT 500`;
  if (rooms.length === 0) return 0;
  const ids = rooms.map((r: any) => r.id);
  await sql.begin(async (tx) => {
    await tx`DELETE FROM messages WHERE conversation_id IN ${tx(ids)}`;
    await tx`DELETE FROM conversations WHERE id IN ${tx(ids)}`;
  });
  return ids.length;
}

export async function purgeOldReports(now = new Date()): Promise<number> {
  const cutoff = new Date(now.getTime() - RETENTION_MS);
  // ไม่มีคอลัมน์เวลาปิดเรื่อง: ใช้ created_at ของรายงานที่ปิดแล้ว (ปิดช้ากว่าสร้างอยู่แล้ว จึงเก็บนานกว่า 1 ปีหลังปิดเสมอ)
  const r = await sql`DELETE FROM reports WHERE status = 'CLOSED' AND created_at < ${cutoff} RETURNING id`;
  return r.length;
}

export function startRetentionJob(): void {
  const run = async () => {
    try {
      await purgeOldChats();
      await purgeOldReports();
    } catch (e) {
      console.error("[retention]", (e as Error).message);
    }
  };
  void run();
  setInterval(run, 24 * 60 * 60 * 1000).unref(); // วันละครั้ง
}
