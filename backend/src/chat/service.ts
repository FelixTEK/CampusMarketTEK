import type { SQL } from "bun";
import { hub, type RealtimeEvent } from "../realtime/hub";
import { imageUrl } from "../listings/storage";

/** ดีลในรูปที่แอปใช้ (ฟิลด์มาจากตาราง deals) */
export function dealDto(d: any) {
  return {
    id: d.deal_id ?? d.id,
    status: d.deal_status ?? d.status,
    meetup: d.meetup ?? null,
    listingId: d.deal_listing_id ?? d.listing_id,
    buyerId: d.deal_buyer_id ?? d.buyer_id,
    createdAt: d.deal_created_at ?? d.created_at,
    updatedAt: d.deal_updated_at ?? d.updated_at,
    completedAt: d.deal_completed_at ?? d.completed_at ?? null,
  };
}

const MESSAGE_SELECT = (db: SQL) => db`
  m.id, m.conversation_id, m.sender_id, m.body, m.deal_id, m.client_id, m.read_at, m.created_at,
  d.id AS d_id, d.status AS deal_status, d.meetup, d.listing_id AS deal_listing_id, d.buyer_id AS deal_buyer_id,
  d.created_at AS deal_created_at, d.updated_at AS deal_updated_at, d.completed_at AS deal_completed_at
`;

/** แถวข้อความ → DTO (ข้อความที่เป็นการ์ดดีลแนบสถานะดีลล่าสุดมาด้วย) */
export function messageDto(r: any) {
  return {
    id: Number(r.id),
    conversationId: r.conversation_id,
    senderId: r.sender_id, // null = ข้อความระบบ
    body: r.body,
    dealId: r.deal_id,
    deal: r.d_id ? dealDto({ ...r, deal_id: r.d_id }) : null,
    clientId: r.client_id,
    readAt: r.read_at,
    createdAt: r.created_at,
  };
}

export async function loadMessage(db: SQL, id: number | string) {
  const [r] = await db`
    SELECT ${MESSAGE_SELECT(db)} FROM messages m LEFT JOIN deals d ON d.id = m.deal_id WHERE m.id = ${id}`;
  return r ? messageDto(r) : null;
}

/** ย้อนหลังทีละหน้า (ใหม่สุดก่อน) — before = id ข้อความที่เก่ากว่านี้ */
export async function loadMessages(db: SQL, conversationId: string, before: number | null, limit: number) {
  const rows = Array.from(await db`
    SELECT ${MESSAGE_SELECT(db)}
    FROM messages m LEFT JOIN deals d ON d.id = m.deal_id
    WHERE m.conversation_id = ${conversationId} ${before ? db`AND m.id < ${before}` : db``}
    ORDER BY m.id DESC
    LIMIT ${limit}
  `);
  return rows.map(messageDto);
}

/**
 * เพิ่มข้อความ — client_id ซ้ำ (ส่งซ้ำตอนเน็ตหลุด) ได้ข้อความเดิมกลับมา ไม่สร้างใหม่
 * คืน { message, created }
 */
export async function insertMessage(
  db: SQL,
  o: { conversationId: string; senderId: string | null; body: string; dealId?: string | null; clientId?: string | null },
) {
  const [row] = await db`
    INSERT INTO messages (conversation_id, sender_id, body, deal_id, client_id)
    VALUES (${o.conversationId}, ${o.senderId}, ${o.body}, ${o.dealId ?? null}, ${o.clientId ?? null})
    ON CONFLICT (sender_id, client_id) WHERE client_id IS NOT NULL DO NOTHING
    RETURNING id`;
  if (row) return { message: (await loadMessage(db, row.id))!, created: true };
  const [existing] = await db`SELECT id FROM messages WHERE sender_id = ${o.senderId} AND client_id = ${o.clientId}`;
  return { message: (await loadMessage(db, existing.id))!, created: false };
}

/** ข้อความระบบ (เช่น "ผู้ขายตกลงขายแล้ว") — ผู้ส่งเป็น null */
export const systemMessage = (db: SQL, conversationId: string, body: string, dealId?: string | null) =>
  insertMessage(db, { conversationId, senderId: null, body, dealId });

/** เหตุการณ์ที่รอส่งหลัง transaction commit (ห้ามส่งก่อน — ถ้า rollback ผู้ใช้จะเห็นของที่ไม่มีจริง) */
export type Outbox = Array<{ to: Array<string | null | undefined>; event: RealtimeEvent }>;

export function flush(outbox: Outbox): void {
  for (const { to, event } of outbox) hub.publishMany(to, event);
}

/** ห้องแชต + ข้อมูลประกาศ + ผู้ขาย (ใช้ตรวจสิทธิ์และสร้าง DTO) */
export async function loadRoom(db: SQL, conversationId: string) {
  const [r] = await db`
    SELECT c.id, c.listing_id, c.buyer_id, c.created_at,
           l.seller_id, l.title, l.price, l.status AS listing_status,
           (SELECT image_key FROM listing_images i WHERE i.listing_id = l.id ORDER BY position LIMIT 1) AS cover,
           b.display_name AS buyer_name, s.display_name AS seller_name
    FROM conversations c
    JOIN listings l ON l.id = c.listing_id
    JOIN users b ON b.id = c.buyer_id
    JOIN users s ON s.id = l.seller_id
    WHERE c.id = ${conversationId}`;
  return r as
    | {
        id: string; listing_id: string; buyer_id: string; created_at: string; seller_id: string; title: string;
        price: number; listing_status: string; cover: string | null; buyer_name: string; seller_name: string;
      }
    | undefined;
}

type Room = NonNullable<Awaited<ReturnType<typeof loadRoom>>>;

/** ห้องในรูปที่แอปใช้ — มองจากมุมของ me */
export function roomDto(r: Room, me: string, activeDeal: any | null) {
  const iAmBuyer = r.buyer_id === me;
  return {
    id: r.id,
    role: iAmBuyer ? ("BUYER" as const) : ("SELLER" as const),
    listing: {
      id: r.listing_id,
      title: r.title,
      price: r.price,
      status: r.listing_status,
      cover: r.cover ? imageUrl(r.cover) : null,
    },
    other: iAmBuyer
      ? { id: r.seller_id, displayName: r.seller_name }
      : { id: r.buyer_id, displayName: r.buyer_name },
    activeDeal: activeDeal ? dealDto(activeDeal) : null,
    createdAt: r.created_at,
  };
}

/** ดีลล่าสุดของห้องนี้ (ประกาศ + ผู้ซื้อคนนี้) — ที่ยังไม่จบมาก่อน แล้วค่อยที่ใหม่สุด */
export async function latestDeal(db: SQL, listingId: string, buyerId: string) {
  const [d] = await db`
    SELECT * FROM deals WHERE listing_id = ${listingId} AND buyer_id = ${buyerId}
    ORDER BY (status IN ('PENDING', 'ACCEPTED', 'HANDED_OVER')) DESC, created_at DESC
    LIMIT 1`;
  return d ?? null;
}
