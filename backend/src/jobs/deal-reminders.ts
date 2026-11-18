import { sql } from "../db";

/**
 * งานเตือน (ส่งครั้งเดียวต่อดีล — เช็กจาก notifications_dedupe_idx (type, ref_id) ด้วย NOT EXISTS ในคำสั่งเดียว):
 *  - DEAL_REMINDER: ส่งมอบแล้วเกิน 24 ชม. ผู้ซื้อยังไม่กดได้รับ → เตือนก่อนระบบปิดให้เองที่ 48 ชม.
 *  - REVIEW_PROMPT: ดีลสำเร็จเกิน 1 ชม. ยังไม่มีรีวิว → ชวนผู้ซื้อรีวิวผู้ขาย
 * คืนจำนวนที่ส่ง · onlyDeal = จำกัดเฉพาะดีลเดียว (ไว้ให้เทสต์ ไม่ไปแตะดีลอื่น)
 */
export async function sendDealReminders(now = new Date(), onlyDeal?: string): Promise<{ reminders: number; reviewPrompts: number }> {
  const scope = (col: string) => (onlyDeal ? sql`AND ${sql(col)} = ${onlyDeal}` : sql``);
  const hours = (h: number) => new Date(now.getTime() - h * 3_600_000);

  const reminders = await sql`
    INSERT INTO notifications (user_id, type, body, ref_id)
    SELECT d.buyer_id, 'DEAL_REMINDER', 'อย่าลืมกด "ได้รับแล้ว" สำหรับ "' || l.title || '" — ไม่กดภายใน 48 ชั่วโมง ระบบจะปิดดีลให้', d.id::text
    FROM deals d JOIN listings l ON l.id = d.listing_id
    WHERE d.status = 'HANDED_OVER' ${scope("d.id")} AND d.updated_at < ${hours(24)}
      AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.type = 'DEAL_REMINDER' AND n.ref_id = d.id::text)
    RETURNING id`;

  const prompts = await sql`
    INSERT INTO notifications (user_id, type, body, ref_id)
    SELECT d.buyer_id, 'REVIEW_PROMPT', 'ดีล "' || l.title || '" สำเร็จแล้ว ให้คะแนนผู้ขายหน่อยไหม', d.id::text
    FROM deals d JOIN listings l ON l.id = d.listing_id
    WHERE d.status = 'COMPLETED' ${scope("d.id")} AND d.completed_at < ${hours(1)}
      AND NOT EXISTS (SELECT 1 FROM reviews r WHERE r.deal_id = d.id)
      AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.type = 'REVIEW_PROMPT' AND n.ref_id = d.id::text)
    RETURNING id`;

  return { reminders: reminders.length, reviewPrompts: prompts.length };
}

export function startDealReminderJob(): void {
  const run = () => sendDealReminders().catch((e) => console.error("[deal-reminders]", e));
  void run();
  setInterval(run, 15 * 60 * 1000).unref();
}
