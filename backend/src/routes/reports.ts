import { Elysia, t } from "elysia";
import { auth, requireAdmin, requireVerified } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { notify } from "../notifications";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** เหตุผลที่เลือกได้ (แอปแสดงเป็นชิป) — เก็บเป็นข้อความในคอลัมน์ reason */
export const REPORT_REASONS = [
  "หลอกลวง / สงสัยเป็นมิจฉาชีพ",
  "สินค้าผิดกฎหมายหรือไม่เหมาะสม",
  "ข้อมูลไม่ตรงความจริง",
  "คุกคามหรือใช้ถ้อยคำไม่เหมาะสม",
  "สแปม",
  "อื่น ๆ",
] as const;

type Target = { type: "LISTING" | "USER" | "MESSAGE"; id: string };

/**
 * ตรวจว่าเป้าหมายมีจริงและรายงานได้ แล้วทำ "สำเนาหลักฐาน ณ เวลารายงาน" (snapshot)
 * ทุกแบบมี userId = เจ้าของเรื่อง (ผู้ถูกรายงาน) ไว้ให้ผู้ดูแลระงับและกันตัดสินเรื่องของตัวเอง
 */
async function buildSnapshot(me: string, target: Target) {
  if (target.type === "LISTING") {
    if (!UUID.test(target.id)) throw notFound("ไม่พบประกาศ");
    const [l] = await sql`
      SELECT l.id, l.seller_id, l.title, l.description, l.price, l.status, u.display_name AS seller_name,
             (SELECT count(*)::int FROM listing_images i WHERE i.listing_id = l.id) AS images
      FROM listings l JOIN users u ON u.id = l.seller_id WHERE l.id = ${target.id}`;
    if (!l || (["CANCELLED", "RETURNED", "TAKEN_DOWN"].includes(l.status) && l.seller_id !== me)) {
      // ที่คนทั่วไปมองไม่เห็นอยู่แล้ว (BR16) ก็ไม่มีอะไรให้รายงาน
      throw notFound("ไม่พบประกาศ");
    }
    if (l.seller_id === me) throw forbidden("OWN_TARGET", "รายงานตัวเองไม่ได้");
    return { listingId: l.id, userId: l.seller_id, title: l.title, description: l.description, price: l.price, status: l.status, sellerName: l.seller_name, images: l.images };
  }

  if (target.type === "USER") {
    if (!UUID.test(target.id)) throw notFound("ไม่พบผู้ใช้");
    const [u] = await sql`
      SELECT u.id, u.display_name, f.name_th AS faculty FROM users u LEFT JOIN faculties f ON f.id = u.faculty_id
      WHERE u.id = ${target.id} AND u.deleted_at IS NULL`;
    if (!u) throw notFound("ไม่พบผู้ใช้");
    if (u.id === me) throw forbidden("OWN_TARGET", "รายงานตัวเองไม่ได้");
    return { userId: u.id, displayName: u.display_name, faculty: u.faculty };
  }

  // MESSAGE: ต้องเป็นผู้ร่วมห้อง และเป็นข้อความของอีกฝ่าย (BR26) · ผู้ดูแลเห็นเฉพาะข้อความนี้ + 5 ข้อความรอบข้าง
  if (!/^\d{1,15}$/.test(target.id)) throw notFound("ไม่พบข้อความ");
  const [m] = await sql`
    SELECT m.id, m.sender_id, m.conversation_id, c.buyer_id, l.seller_id, l.id AS listing_id, l.title
    FROM messages m JOIN conversations c ON c.id = m.conversation_id JOIN listings l ON l.id = c.listing_id
    WHERE m.id = ${target.id}`;
  if (!m || (m.buyer_id !== me && m.seller_id !== me)) throw notFound("ไม่พบข้อความ"); // คนนอกไม่รู้ว่ามีข้อความนี้
  if (m.sender_id === null) throw new AppError(422, "VALIDATION", "รายงานข้อความของระบบไม่ได้");
  if (m.sender_id === me) throw forbidden("OWN_TARGET", "รายงานข้อความของตัวเองไม่ได้");
  const around = await sql`
    (SELECT id, sender_id, body, created_at FROM messages WHERE conversation_id = ${m.conversation_id} AND id < ${m.id} ORDER BY id DESC LIMIT 5)
    UNION ALL
    (SELECT id, sender_id, body, created_at FROM messages WHERE conversation_id = ${m.conversation_id} AND id >= ${m.id} ORDER BY id LIMIT 6)
    ORDER BY id`;
  return {
    userId: m.sender_id, listingId: m.listing_id, listingTitle: m.title, conversationId: m.conversation_id, messageId: Number(m.id),
    messages: around.map((x: any) => ({ id: Number(x.id), senderId: x.sender_id, body: x.body, createdAt: new Date(x.created_at).toISOString() })),
  };
}

export const reportRoutes = new Elysia({ name: "routes-reports" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      .get("/reports/reasons", () => ({ reasons: REPORT_REASONS }))
      .post(
        "/reports",
        async ({ body, user }) => {
          if (!(REPORT_REASONS as readonly string[]).includes(body.reason)) throw new AppError(422, "VALIDATION", "เลือกเหตุผลจากรายการ");
          const detail = body.detail?.trim() || null;
          if (detail && detail.length > 300) throw new AppError(422, "VALIDATION", "รายละเอียดต้องไม่เกิน 300 ตัวอักษร");
          const snapshot = await buildSnapshot(user!.id, { type: body.targetType, id: body.targetId });
          // UNIQUE (target_type, target_id, reporter_id): รายงานซ้ำไม่ได้ (BR13)
          const [r] = await sql`
            INSERT INTO reports (reporter_id, target_type, target_id, reason, detail, snapshot)
            VALUES (${user!.id}, ${body.targetType}, ${body.targetId}, ${body.reason}, ${detail}, ${snapshot}::jsonb)
            ON CONFLICT (target_type, target_id, reporter_id) DO NOTHING RETURNING id`;
          if (!r) throw conflict("คุณรายงานเรื่องนี้ไปแล้ว");
          return { id: r.id as string };
        },
        { body: t.Object({ targetType: t.Union([t.Literal("LISTING"), t.Literal("USER"), t.Literal("MESSAGE")]), targetId: t.String(), reason: t.String(), detail: t.Optional(t.String()) }) },
      ),
  );

// ── ผู้ดูแล ─────────────────────────────────────────────────────────────

const SELECT = sql`
  SELECT r.id, r.target_type, r.target_id, r.reason, r.detail, r.snapshot, r.status, r.admin_note, r.created_at,
         r.reporter_id, ru.display_name AS reporter_name,
         (SELECT count(*)::int FROM reports o WHERE o.target_type = r.target_type AND o.target_id = r.target_id AND o.id <> r.id) AS other_reports
  FROM reports r JOIN users ru ON ru.id = r.reporter_id`;

/** คู่ซื้อ–ขายที่ปิดดีลกันตั้งแต่ 2 ครั้งขึ้นไป — เบาะแสการปั่นตัวเลข (ข้อ 2.13) */
async function repeatPairs(sellerId: string | undefined) {
  if (!sellerId) return [];
  const rows = await sql`
    SELECT u.display_name AS buyer_name, count(*)::int AS deals
    FROM deals d JOIN listings l ON l.id = d.listing_id JOIN users u ON u.id = d.buyer_id
    WHERE l.seller_id = ${sellerId} AND d.status = 'COMPLETED'
    GROUP BY d.buyer_id, u.display_name HAVING count(*) >= 2 ORDER BY count(*) DESC LIMIT 5`;
  return rows.map((r: any) => ({ buyerName: r.buyer_name as string, deals: r.deals as number }));
}

async function reportDto(r: any) {
  const snap = r.snapshot ?? {};
  return {
    id: r.id as string,
    targetType: r.target_type as string,
    targetId: r.target_id as string,
    reason: r.reason as string,
    detail: (r.detail ?? null) as string | null,
    status: r.status as string,
    adminNote: (r.admin_note ?? null) as string | null,
    createdAt: new Date(r.created_at).toISOString(),
    reporter: { id: r.reporter_id as string, displayName: r.reporter_name as string },
    otherReports: r.other_reports as number,
    snapshot: snap,
    repeatPairs: r.target_type === "MESSAGE" ? [] : await repeatPairs(snap.userId),
  };
}

export const adminReportRoutes = new Elysia({ name: "routes-admin-reports", prefix: "/admin/reports" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app
      .get(
        "/",
        async ({ query }) => {
          const status = query.status === "CLOSED" ? "CLOSED" : "OPEN";
          const rows = await sql`${SELECT} WHERE r.status = ${status} ORDER BY r.created_at ${status === "OPEN" ? sql`ASC` : sql`DESC`} LIMIT 100`;
          return { items: await Promise.all(rows.map(reportDto)) };
        },
        { query: t.Object({ status: t.Optional(t.String()) }) },
      )
      .get("/:id", async ({ params }) => {
        if (!UUID.test(params.id)) throw notFound("ไม่พบรายงาน");
        const [r] = await sql`${SELECT} WHERE r.id = ${params.id}`;
        if (!r) throw notFound("ไม่พบรายงาน");
        return reportDto(r);
      })
      // ปิดรายงานพร้อมหมายเหตุ · การลงโทษ (ตีกลับ/ถอด/ระงับ) เป็นปุ่มแยกที่ผู้ดูแลกดเอง
      .post(
        "/:id/close",
        async ({ params, body, user }) => {
          if (!UUID.test(params.id)) throw notFound("ไม่พบรายงาน");
          const note = body.note.trim();
          if (note.length < 1 || note.length > 300) throw new AppError(422, "VALIDATION", "ต้องระบุหมายเหตุ (ไม่เกิน 300 ตัวอักษร)");
          return await sql.begin(async (tx) => {
            const [r] = await tx`SELECT id, status, reporter_id, snapshot FROM reports WHERE id = ${params.id} FOR UPDATE`;
            if (!r) throw notFound("ไม่พบรายงาน");
            if (r.status !== "OPEN") throw conflict("รายงานนี้ปิดไปแล้ว");
            const snap = r.snapshot ?? {};
            // ตัดสินเรื่องของตัวเองไม่ได้: เป็นผู้ถูกรายงาน หรือเป็นผู้รายงาน
            if (snap.userId === user!.id || r.reporter_id === user!.id) throw forbidden("OWN_TARGET", "ตัดสินเรื่องของตัวเองไม่ได้");
            await tx`UPDATE reports SET status = 'CLOSED', admin_note = ${note}, resolved_by = ${user!.id} WHERE id = ${params.id}`;
            await notify(tx, r.reporter_id, "REPORT_CLOSED", "ผู้ดูแลตรวจสอบรายงานของคุณแล้ว ขอบคุณที่ช่วยดูแลชุมชน", params.id);
            return { ok: true };
          });
        },
        { body: t.Object({ note: t.String() }) },
      ),
  );
