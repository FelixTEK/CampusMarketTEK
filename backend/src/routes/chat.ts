import { Elysia, t } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { verifyToken } from "../auth/token";
import { flush, insertMessage, latestDeal, loadMessages, loadRoom, roomDto, type Outbox } from "../chat/service";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { imageUrl } from "../listings/storage";
import { FailureLimiter } from "../lib/rate-limit";
import { hub } from "../realtime/hub";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const mustBeUuid = (id: string, what = "ห้องแชต") => {
  if (!UUID.test(id)) throw notFound(`ไม่พบ${what}`);
};

/** BR27: ไม่เกิน 30 ข้อความต่อนาทีต่อคน (นับทุกครั้งที่ส่ง ไม่ใช่เฉพาะที่พลาด) */
export const sendLimiter = new FailureLimiter(30, 60_000);

/** ห้องที่ me เป็นผู้ซื้อหรือผู้ขาย — ไม่ใช่สองฝ่ายนี้ตอบ 404 (BR26: คนนอกอ่านไม่ได้ และไม่บอกว่ามีห้องอยู่) */
async function myRoom(conversationId: string, me: string) {
  mustBeUuid(conversationId);
  const room = await loadRoom(sql, conversationId);
  if (!room || (room.buyer_id !== me && room.seller_id !== me)) throw notFound("ไม่พบห้องแชต");
  return room;
}

export const chatRoutes = new Elysia({ name: "routes-chat" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      // ── เปิดห้อง (หรือใช้ห้องเดิม) จากหน้าประกาศ — 1 ห้องต่อ (ประกาศ, ผู้ซื้อ) (BR25) ──
      .post("/listings/:id/conversations", async ({ params, user }) => {
        mustBeUuid(params.id, "ประกาศ");
        const me = user!;
        const [l] = await sql`
          SELECT l.seller_id, l.status, u.is_banned, (u.deleted_at IS NOT NULL) AS deleted
          FROM listings l JOIN users u ON u.id = l.seller_id WHERE l.id = ${params.id}`;
        if (!l || l.is_banned || l.deleted || !["AVAILABLE", "RESERVED", "SOLD"].includes(l.status))
          throw notFound("ไม่พบประกาศ");
        if (l.seller_id === me.id) throw forbidden("OWN_LISTING", "ทักแชตประกาศของตัวเองไม่ได้"); // BR06

        const [existing] = await sql`SELECT id FROM conversations WHERE listing_id = ${params.id} AND buyer_id = ${me.id}`;
        let id: string = existing?.id;
        if (!id) {
          if (l.status === "SOLD") throw conflict("ประกาศนี้ขายแล้ว");
          const [row] = await sql`
            INSERT INTO conversations (listing_id, buyer_id) VALUES (${params.id}, ${me.id})
            ON CONFLICT (listing_id, buyer_id) DO UPDATE SET listing_id = EXCLUDED.listing_id
            RETURNING id`; // ถ้าสองเครื่องเปิดพร้อมกัน ได้ห้องเดียวกัน
          id = row.id;
        }
        const room = (await loadRoom(sql, id))!;
        return roomDto(room, me.id, await latestDeal(sql, room.listing_id, room.buyer_id));
      })

      // ── รายการห้องของฉัน (ทั้งที่ซื้อและที่ขาย) เรียงตามข้อความล่าสุด ──
      .get("/conversations", async ({ user }) => {
        const me = user!.id;
        const rows = Array.from(await sql`
          SELECT c.id, c.listing_id, c.buyer_id, l.seller_id, l.title, l.price, l.status AS listing_status,
                 (SELECT image_key FROM listing_images i WHERE i.listing_id = l.id ORDER BY position LIMIT 1) AS cover,
                 b.display_name AS buyer_name, s.display_name AS seller_name, c.created_at,
                 lm.id AS last_id, lm.body AS last_body, lm.sender_id AS last_sender, lm.created_at AS last_at,
                 (SELECT count(*)::int FROM messages m
                    WHERE m.conversation_id = c.id AND m.read_at IS NULL AND m.sender_id IS DISTINCT FROM ${me}) AS unread
          FROM conversations c
          JOIN listings l ON l.id = c.listing_id
          JOIN users b ON b.id = c.buyer_id
          JOIN users s ON s.id = l.seller_id
          LEFT JOIN LATERAL (SELECT id, body, sender_id, created_at FROM messages
                             WHERE conversation_id = c.id ORDER BY id DESC LIMIT 1) lm ON true
          WHERE (c.buyer_id = ${me} OR l.seller_id = ${me})
            AND NOT (CASE WHEN c.buyer_id = ${me} THEN s.is_banned ELSE b.is_banned END)
          ORDER BY COALESCE(lm.created_at, c.created_at) DESC
          LIMIT 200
        `);
        return rows.map((r: any) => ({
          ...roomDto(r, me, null),
          lastMessage: r.last_id ? { id: Number(r.last_id), body: r.last_body, senderId: r.last_sender, createdAt: r.last_at } : null,
          unread: r.unread,
        }));
      })

      .get("/conversations/:id", async ({ params, user }) => {
        const room = await myRoom(params.id, user!.id);
        return roomDto(room, user!.id, await latestDeal(sql, room.listing_id, room.buyer_id));
      })

      // ── ข้อความย้อนหลัง (ใหม่สุดก่อน ทีละ 30) ──
      .get(
        "/conversations/:id/messages",
        async ({ params, query, user }) => {
          await myRoom(params.id, user!.id);
          const before = query.before ? Number(query.before) : null;
          if (before !== null && (!Number.isInteger(before) || before <= 0)) throw new AppError(422, "VALIDATION", "ค่า before ไม่ถูกต้อง");
          const items = await loadMessages(sql, params.id, before, 31);
          return { items: items.slice(0, 30), hasMore: items.length > 30 };
        },
        { query: t.Object({ before: t.Optional(t.String()) }) },
      )

      // ── ส่งข้อความ: client_id กันส่งซ้ำ · ≤ 1,000 ตัว · ≤ 30 ข้อความ/นาที ──
      .post(
        "/conversations/:id/messages",
        async ({ params, body, user }) => {
          const me = user!.id;
          const room = await myRoom(params.id, me);
          const text = body.body.trim();
          if (text.length < 1 || text.length > 1000) throw new AppError(422, "VALIDATION", "ข้อความต้องยาว 1–1,000 ตัวอักษร");
          if (body.clientId !== undefined && !UUID.test(body.clientId)) throw new AppError(422, "VALIDATION", "clientId ไม่ถูกต้อง");

          // ส่งซ้ำด้วย client_id เดิม (เน็ตหลุดแล้วลองใหม่) ไม่นับโควตา และได้ข้อความเดิมกลับ
          if (body.clientId) {
            const [dup] = await sql`SELECT id FROM messages WHERE sender_id = ${me} AND client_id = ${body.clientId}`;
            if (dup) return (await insertMessage(sql, { conversationId: room.id, senderId: me, body: text, clientId: body.clientId })).message;
          }
          if (sendLimiter.isBlocked(`send:${me}`)) throw new AppError(429, "TOO_MANY_MESSAGES", "ส่งข้อความถี่เกินไป กรุณารอสักครู่");
          sendLimiter.recordFailure(`send:${me}`);

          const { message, created } = await insertMessage(sql, { conversationId: room.id, senderId: me, body: text, clientId: body.clientId });
          // BR28: ข้อความแชตไม่สร้างแถวในตาราง notifications — แอปนับจากข้อความที่ยังไม่อ่านเอง
          if (created) hub.publishMany([room.buyer_id, room.seller_id], { type: "message", message });
          return message;
        },
        { body: t.Object({ body: t.String(), clientId: t.Optional(t.String()) }) },
      )

      // ── อ่านแล้ว: ทำเครื่องหมายข้อความของอีกฝ่ายในห้องนี้ ──
      .post("/conversations/:id/read", async ({ params, user }) => {
        const me = user!.id;
        await myRoom(params.id, me);
        const rows = await sql`
          UPDATE messages SET read_at = now()
          WHERE conversation_id = ${params.id} AND read_at IS NULL AND sender_id IS DISTINCT FROM ${me}
          RETURNING id`;
        return { read: rows.length as number };
      }),
  )

  // ── WebSocket: แอปเชื่อมค้างไว้ตอนเปิดอยู่ — ส่งข้อความใหม่/สถานะดีลถึงทันที ──
  // ตรวจ JWT ตอนเชื่อมต่อ (header Authorization หรือ ?token= สำหรับเครื่องมือที่ใส่ header ไม่ได้)
  .ws("/ws", {
    async open(ws) {
      const header = (ws.data.headers as Record<string, string | undefined>).authorization;
      const token = header?.startsWith("Bearer ") ? header.slice(7) : (ws.data.query as Record<string, string>).token;
      const id = token ? await verifyToken(token) : null;
      if (!id) return void ws.close(4401, "unauthorized");
      const [u] = await sql`SELECT is_verified, is_banned FROM users WHERE id = ${id} AND deleted_at IS NULL`;
      if (!u || !u.is_verified || u.is_banned) return void ws.close(4403, "forbidden");
      (ws.data as any).userId = id;
      hub.add(id, ws);
      ws.send(JSON.stringify({ type: "hello" }));
    },
    close(ws) {
      const id = (ws.data as any).userId as string | undefined;
      if (id) hub.remove(id, ws);
    },
    // เซิร์ฟเวอร์เป็นฝ่ายส่งอย่างเดียว ข้อความจาก client ไม่ใช้ (ส่งข้อความผ่าน POST เพื่อให้ตรวจสิทธิ์/กันซ้ำที่เดียว)
    message() {},
  });
