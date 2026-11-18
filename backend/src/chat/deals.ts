import type { SQL } from "bun";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { assertTransition } from "../listings/status";
import { notify } from "../notifications";
import { dealDto, flush, insertMessage, loadRoom, systemMessage, type Outbox } from "./service";

const BAIL_RESERVED = "ประกาศนี้ถูกจองไปแล้ว";

/** ล็อกแถวประกาศ (สองคนกดตกลงพร้อมกันจึงต้องต่อคิวกันที่นี่) — ล็อกประกาศก่อนดีลเสมอ กัน deadlock */
async function lockListing(tx: SQL, id: string) {
  const [l] = await tx`SELECT id, seller_id, status, title FROM listings WHERE id = ${id} FOR UPDATE`;
  if (!l) throw notFound("ไม่พบประกาศ");
  return l as { id: string; seller_id: string; status: string; title: string };
}

type Ctx = { deal: any; listing: { id: string; seller_id: string; status: string; title: string }; conversationId: string | null };

/** โหลดดีล + ประกาศ + ห้องแชต โดยล็อกประกาศแล้วล็อกดีล */
async function load(tx: SQL, dealId: string): Promise<Ctx> {
  const [pre] = await tx`SELECT listing_id FROM deals WHERE id = ${dealId}`;
  if (!pre) throw notFound("ไม่พบดีล");
  const listing = await lockListing(tx, pre.listing_id);
  const [deal] = await tx`SELECT * FROM deals WHERE id = ${dealId} FOR UPDATE`;
  const [conv] = await tx`SELECT id FROM conversations WHERE listing_id = ${deal.listing_id} AND buyer_id = ${deal.buyer_id}`;
  return { deal, listing, conversationId: conv?.id ?? null };
}

/** ข้อความระบบในห้อง + ส่งเหตุการณ์สดให้ทั้งสองฝ่ายหลัง commit */
async function announce(tx: SQL, out: Outbox, c: Ctx, text: string | null, deal: any) {
  const parties = [c.deal.buyer_id, c.listing.seller_id];
  if (c.conversationId) {
    if (text) out.push({ to: parties, event: { type: "message", message: (await systemMessage(tx, c.conversationId, text)).message } });
    out.push({ to: parties, event: { type: "deal", conversationId: c.conversationId, deal: dealDto(deal) } });
  }
}

const money = (n: number) => `฿${n.toLocaleString("en-US")}`;

// ── เสนอซื้อ (BR06–BR08) ─────────────────────────────────────────────────

export async function createDeal(conversationId: string, me: string) {
  const out: Outbox = [];
  const deal = await sql.begin(async (tx) => {
    const room = await loadRoom(tx, conversationId);
    if (!room || (room.buyer_id !== me && room.seller_id !== me)) throw notFound("ไม่พบห้องแชต");
    if (room.seller_id === me) throw forbidden("NOT_BUYER", "เสนอซื้อได้เฉพาะผู้ซื้อ"); // BR06

    const l = await lockListing(tx, room.listing_id);
    if (l.status === "RESERVED") throw conflict(BAIL_RESERVED);
    if (l.status !== "AVAILABLE") throw conflict("ประกาศนี้เสนอซื้อไม่ได้แล้ว"); // BR07

    let row;
    try {
      [row] = await tx`INSERT INTO deals (listing_id, buyer_id) VALUES (${l.id}, ${me}) RETURNING *`;
    } catch (e) {
      // partial unique index: ผู้ซื้อ 1 คนมีข้อเสนอที่รอตอบได้ 1 รายการต่อประกาศ (BR08)
      if ((e as { errno?: string }).errno === "23505") throw conflict("คุณมีข้อเสนอที่กำลังรอผู้ขายตอบอยู่แล้ว");
      throw e;
    }
    const parties = [room.buyer_id, room.seller_id];
    const card = await insertMessage(tx, {
      conversationId, senderId: me, body: `เสนอซื้อ "${room.title}" ${money(room.price)}`, dealId: row.id,
    });
    await notify(tx, room.seller_id, "DEAL_OFFER", `${room.buyer_name} เสนอซื้อ "${room.title}"`, row.id);
    out.push({ to: parties, event: { type: "message", message: card.message } });
    return row;
  });
  flush(out);
  return dealDto(deal);
}

// ── เปลี่ยนสถานะดีล ──────────────────────────────────────────────────────

export type DealAction = "accept" | "reject" | "cancel" | "handover" | "receive";

/** ปิดดีลสำเร็จ: ดีล → COMPLETED, ประกาศ → ขายแล้ว (เกิดจากดีลที่สำเร็จเท่านั้น BR24) */
async function complete(tx: SQL, out: Outbox, c: Ctx, auto: boolean) {
  assertTransition(c.listing.status as any, "SOLD", "system");
  const [deal] = await tx`
    UPDATE deals SET status = 'COMPLETED', completed_at = now(), updated_at = now() WHERE id = ${c.deal.id} RETURNING *`;
  await tx`UPDATE listings SET status = 'SOLD', status_reason = NULL, updated_at = now() WHERE id = ${c.listing.id}`;
  const text = auto ? "ระบบปิดดีลให้อัตโนมัติ เพราะผู้ซื้อไม่ได้กดยืนยันภายใน 48 ชั่วโมง" : "ผู้ซื้อยืนยันว่าได้รับสินค้าแล้ว ดีลสำเร็จ";
  await announce(tx, out, c, text, deal);
  await notify(tx, c.listing.seller_id, auto ? "DEAL_AUTO_COMPLETED" : "DEAL_COMPLETED", `ดีล "${c.listing.title}" สำเร็จแล้ว`, deal.id);
  if (auto) await notify(tx, c.deal.buyer_id, "DEAL_AUTO_COMPLETED", `ระบบปิดดีล "${c.listing.title}" ให้อัตโนมัติ`, deal.id);
  return deal;
}

export async function dealAction(dealId: string, me: string, action: DealAction) {
  const out: Outbox = [];
  const result = await sql.begin(async (tx) => {
    const c = await load(tx, dealId);
    const { deal, listing } = c;
    const isSeller = me === listing.seller_id;
    const isBuyer = me === deal.buyer_id;
    if (!isSeller && !isBuyer) throw notFound("ไม่พบดีล"); // คนนอกไม่รู้ว่ามีดีลนี้

    const wrongState = (msg: string) => conflict(msg);

    switch (action) {
      case "accept": {
        if (!isSeller) throw forbidden("NOT_SELLER", "ตกลงขายได้เฉพาะผู้ขาย");
        // ข้อเสนอนี้ถูกปฏิเสธอัตโนมัติเพราะอีกข้อเสนอถูกตกลงไปก่อน (ประกาศถูกจองแล้ว) → บอกเหตุผลที่แท้จริง
        if (deal.status === "REJECTED" && listing.status === "RESERVED") throw conflict(BAIL_RESERVED);
        if (deal.status !== "PENDING") throw wrongState("ข้อเสนอนี้ตอบไปแล้ว");
        // BR09: ล็อกประกาศแล้วต้อง "กำลังขาย" เท่านั้น — ถ้าอีกคนตกลงไปก่อนหน้านี้ จะเห็น RESERVED และได้ 409
        if (listing.status !== "AVAILABLE") throw conflict(BAIL_RESERVED);

        let accepted;
        try {
          [accepted] = await tx`UPDATE deals SET status = 'ACCEPTED', updated_at = now() WHERE id = ${deal.id} RETURNING *`;
        } catch (e) {
          if ((e as { errno?: string }).errno === "23505") throw conflict(BAIL_RESERVED); // unique index คือด่านสุดท้ายกันจองซ้อน
          throw e;
        }
        await tx`UPDATE listings SET status = 'RESERVED', status_reason = NULL, updated_at = now() WHERE id = ${listing.id}`;
        await announce(tx, out, c, "ผู้ขายตกลงขายแล้ว ประกาศนี้ถูกจองให้คุณ", accepted);
        await notify(tx, deal.buyer_id, "DEAL_ACCEPTED", `ผู้ขายตกลงขาย "${listing.title}" ให้คุณ`, deal.id);

        // ข้อเสนออื่นที่รอตอบอยู่ ถูกปฏิเสธอัตโนมัติ + แจ้งผู้ซื้อแต่ละคน
        const others = await tx`
          UPDATE deals SET status = 'REJECTED', updated_at = now()
          WHERE listing_id = ${listing.id} AND status = 'PENDING' AND id <> ${deal.id} RETURNING *`;
        for (const o of others) {
          const [oc] = await tx`SELECT id FROM conversations WHERE listing_id = ${listing.id} AND buyer_id = ${o.buyer_id}`;
          const octx: Ctx = { deal: o, listing, conversationId: oc?.id ?? null };
          await announce(tx, out, octx, "ผู้ขายขายให้ผู้ซื้อรายอื่นแล้ว ข้อเสนอของคุณถูกปฏิเสธอัตโนมัติ", o);
          await notify(tx, o.buyer_id, "DEAL_REJECTED", `"${listing.title}" ถูกจองโดยผู้ซื้อรายอื่นแล้ว`, o.id);
        }
        // คนที่กดโปรดประกาศนี้ไว้ (ยกเว้นคู่ดีล) ได้รู้ว่าถูกจองแล้ว
        await tx`
          INSERT INTO notifications (user_id, type, body, ref_id)
          SELECT f.user_id, 'FAVORITE_RESERVED', ${`"${listing.title}" ที่คุณบันทึกไว้ถูกจองแล้ว`}, ${listing.id}
          FROM favorites f WHERE f.listing_id = ${listing.id} AND f.user_id NOT IN (${deal.buyer_id}, ${listing.seller_id})`;
        return accepted;
      }

      case "reject": {
        if (!isSeller) throw forbidden("NOT_SELLER", "ปฏิเสธได้เฉพาะผู้ขาย");
        if (deal.status !== "PENDING") throw wrongState("ข้อเสนอนี้ตอบไปแล้ว");
        const [d] = await tx`UPDATE deals SET status = 'REJECTED', updated_at = now() WHERE id = ${deal.id} RETURNING *`;
        await announce(tx, out, c, "ผู้ขายปฏิเสธข้อเสนอ", d);
        await notify(tx, deal.buyer_id, "DEAL_REJECTED", `ผู้ขายปฏิเสธข้อเสนอซื้อ "${listing.title}"`, deal.id);
        return d;
      }

      case "cancel": {
        if (deal.status === "PENDING") {
          if (!isBuyer) throw wrongState("ผู้ขายใช้ปุ่ม ปฏิเสธ แทน");
        } else if (deal.status !== "ACCEPTED") {
          throw wrongState("ดีลนี้ยกเลิกไม่ได้แล้ว"); // ส่งมอบแล้ว/จบแล้ว — มีปัญหาให้กดรายงาน
        }
        const wasAccepted = deal.status === "ACCEPTED";
        const [d] = await tx`UPDATE deals SET status = 'CANCELLED', updated_at = now() WHERE id = ${deal.id} RETURNING *`;
        if (wasAccepted) {
          // ยกเลิกการจอง → ประกาศกลับมา "กำลังขาย" (ถ้ายังเป็น RESERVED — ผู้ดูแลอาจนำออกไปแล้ว)
          if (listing.status === "RESERVED") await tx`UPDATE listings SET status = 'AVAILABLE', updated_at = now() WHERE id = ${listing.id}`;
        }
        const who = isBuyer ? "ผู้ซื้อ" : "ผู้ขาย";
        await announce(tx, out, c, wasAccepted ? `${who}ยกเลิกดีล ประกาศกลับมากำลังขายอีกครั้ง` : "ผู้ซื้อถอนข้อเสนอ", d);
        await notify(tx, isBuyer ? listing.seller_id : deal.buyer_id, "DEAL_CANCELLED", `${who}ยกเลิกดีล "${listing.title}"`, deal.id);
        return d;
      }

      case "handover": {
        if (!isSeller) throw forbidden("NOT_SELLER", "กดส่งมอบได้เฉพาะผู้ขาย");
        if (deal.status !== "ACCEPTED") throw wrongState("ต้องตกลงขายก่อนจึงจะส่งมอบได้");
        // updated_at ใหม่ = เริ่มนับ 48 ชั่วโมงที่ผู้ซื้อต้องยืนยัน (BR11)
        const [d] = await tx`UPDATE deals SET status = 'HANDED_OVER', updated_at = now() WHERE id = ${deal.id} RETURNING *`;
        await announce(tx, out, c, "ผู้ขายแจ้งว่าส่งมอบสินค้าแล้ว กรุณากด ได้รับแล้ว เมื่อได้รับของ", d);
        await notify(tx, deal.buyer_id, "DEAL_HANDED_OVER", `ผู้ขายส่งมอบ "${listing.title}" แล้ว กรุณายืนยันการรับของ`, deal.id);
        return d;
      }

      case "receive": {
        if (!isBuyer) throw forbidden("NOT_BUYER", "ยืนยันรับของได้เฉพาะผู้ซื้อ");
        if (deal.status !== "HANDED_OVER") throw wrongState("ผู้ขายยังไม่ได้กดส่งมอบ");
        return await complete(tx, out, c, false);
      }
    }
  });
  flush(out);
  return dealDto(result);
}

/** นัดรับ: ข้อความสั้น ๆ (≤ 200 ตัว) แสดงบนการ์ดดีล — แก้ได้ทั้งสองฝ่ายตอนที่ดีลตกลงแล้ว (ก่อนส่งมอบ) */
export async function setMeetup(dealId: string, me: string, text: string) {
  const meetup = text.trim();
  if (meetup.length < 1 || meetup.length > 200) throw new AppError(422, "VALIDATION", "สถานที่นัดรับต้องยาว 1–200 ตัวอักษร");
  const out: Outbox = [];
  const result = await sql.begin(async (tx) => {
    const c = await load(tx, dealId);
    if (me !== c.listing.seller_id && me !== c.deal.buyer_id) throw notFound("ไม่พบดีล");
    if (c.deal.status !== "ACCEPTED") throw conflict("กำหนดนัดรับได้เฉพาะดีลที่ตกลงขายแล้ว และยังไม่ส่งมอบ");
    const [d] = await tx`UPDATE deals SET meetup = ${meetup}, updated_at = now() WHERE id = ${dealId} RETURNING *`;
    await announce(tx, out, c, `นัดรับ: ${meetup}`, d);
    return d;
  });
  flush(out);
  return dealDto(result);
}

/**
 * ปิดดีลอัตโนมัติ (BR11): ส่งมอบแล้วเกิน 48 ชั่วโมงที่ผู้ซื้อไม่กด ได้รับแล้ว
 * ข้ามดีลที่ประกาศมีรายงานค้างอยู่ (ผู้ดูแลต้องตรวจก่อน) · คืนจำนวนที่ปิด
 */
export async function autoCompleteDeals(olderThanMs = 48 * 60 * 60 * 1000, now = Date.now()): Promise<number> {
  const cutoff = new Date(now - olderThanMs);
  const due = await sql`
    SELECT d.id FROM deals d
    WHERE d.status = 'HANDED_OVER' AND d.updated_at < ${cutoff}
      AND NOT EXISTS (
        SELECT 1 FROM reports r
        WHERE r.status = 'OPEN' AND r.target_type = 'LISTING' AND r.target_id = d.listing_id::text)
    ORDER BY d.updated_at
    LIMIT 100`;

  let closed = 0;
  for (const row of due) {
    const out: Outbox = [];
    try {
      await sql.begin(async (tx) => {
        const c = await load(tx, row.id);
        if (c.deal.status !== "HANDED_OVER") return; // เพิ่งมีคนกดไปแล้ว
        await complete(tx, out, c, true);
        closed++;
      });
      flush(out);
    } catch (e) {
      console.error("[auto-complete]", row.id, e); // ดีลหนึ่งพังไม่ให้ลากดีลอื่น
    }
  }
  return closed;
}

export function startAutoCompleteJob(): void {
  const run = () => autoCompleteDeals().catch((e) => console.error("[auto-complete]", e));
  void run();
  setInterval(run, 15 * 60 * 1000).unref(); // ทุก 15 นาที
}
