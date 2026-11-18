import { Elysia, t } from "elysia";
import { auth, requireAuth } from "../auth/plugin";
import { sql } from "../db";
import { notFound } from "../errors";

/**
 * แจ้งเตือนของฉัน (ตาราง notifications) — แถวถูกสร้างใน transaction เดียวกับเหตุการณ์ (ดู notifications.ts)
 * conversationId/listingId หาจากดีลที่อ้างถึง ไว้ให้แอปแตะแล้วไปหน้าที่ถูกต้อง (แชตไม่สร้างแจ้งเตือน BR28)
 */
const SELECT = sql`
  SELECT n.id, n.type, n.body, n.ref_id, n.is_read, n.created_at, c.id AS conversation_id, d.listing_id
  FROM notifications n
  LEFT JOIN deals d ON d.id::text = n.ref_id
  LEFT JOIN conversations c ON c.listing_id = d.listing_id AND c.buyer_id = d.buyer_id`;

const dto = (r: any) => ({
  id: Number(r.id),
  type: r.type as string,
  body: r.body as string,
  refId: (r.ref_id ?? null) as string | null,
  conversationId: (r.conversation_id ?? null) as string | null,
  listingId: (r.listing_id ?? null) as string | null,
  isRead: r.is_read as boolean,
  createdAt: new Date(r.created_at).toISOString(),
});

export const notificationRoutes = new Elysia({ name: "routes-notifications" })
  .use(auth)
  .guard({ beforeHandle: requireAuth }, (app) =>
    app
      // after = id ล่าสุดที่แอปมี → ส่งเฉพาะที่ใหม่กว่า (ไม่ใส่ = 50 รายการล่าสุด) · unread = จำนวนที่ยังไม่อ่านทั้งหมด
      .get(
        "/notifications",
        async ({ query, user }) => {
          const after = query.after ?? 0;
          const rows = await sql`${SELECT} WHERE n.user_id = ${user!.id} AND n.id > ${after} ORDER BY n.id DESC LIMIT ${query.limit ?? 50}`;
          const [{ n }] = await sql`SELECT count(*)::int AS n FROM notifications WHERE user_id = ${user!.id} AND NOT is_read`;
          return { items: rows.map(dto), unread: n as number };
        },
        { query: t.Object({ after: t.Optional(t.Numeric({ minimum: 0 })), limit: t.Optional(t.Numeric({ minimum: 1, maximum: 100 })) }) },
      )
      .post("/notifications/read-all", async ({ user }) => {
        await sql`UPDATE notifications SET is_read = true WHERE user_id = ${user!.id} AND NOT is_read`;
        return { unread: 0 };
      })
      .post("/notifications/:id/read", async ({ params, user }) => {
        if (!/^\d{1,15}$/.test(params.id)) throw notFound("ไม่พบการแจ้งเตือน");
        const r = await sql`UPDATE notifications SET is_read = true WHERE id = ${params.id} AND user_id = ${user!.id} RETURNING id`;
        if (r.length === 0) throw notFound("ไม่พบการแจ้งเตือน"); // ของคนอื่นก็ตอบเหมือนไม่มี
        const [{ n }] = await sql`SELECT count(*)::int AS n FROM notifications WHERE user_id = ${user!.id} AND NOT is_read`;
        return { unread: n as number };
      }),
  );
