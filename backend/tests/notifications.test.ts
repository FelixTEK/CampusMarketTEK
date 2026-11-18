import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { sendDealReminders } from "../src/jobs/deal-reminders";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);

async function call(method: string, path: string, token?: string, json?: unknown) {
  const headers: Record<string, string> = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (json !== undefined) headers["content-type"] = "application/json";
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body: json !== undefined ? JSON.stringify(json) : undefined }));
  const isJson = (res.headers.get("content-type") ?? "").includes("json");
  return { status: res.status, data: (isJson ? await res.json() : null) as any };
}

type U = { id: string; token: string };
const users = {} as Record<"seller" | "buyer" | "other", U>;
let listingId = "";

beforeAll(async () => {
  for (const n of ["seller", "buyer", "other"] as const) {
    const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified) VALUES (${`test-n-${tag}-${n}`}, ${n}, true) RETURNING id`;
    users[n] = { id: u.id, token: await signToken(u.id) };
  }
  const [r] = await sql`SELECT id FROM categories c WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = c.id) LIMIT 1`;
  const [l] = await sql`INSERT INTO listings (seller_id, category_id, title, price) VALUES (${users.seller.id}, ${r.id}, 'ลำโพงทดสอบ', 500) RETURNING id`;
  listingId = l.id;
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE kmitl_sub LIKE ${`test-n-${tag}-%`}`;
  await sql`DELETE FROM notifications WHERE user_id IN (${mine})`;
  await sql`DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE listing_id = ${listingId})`;
  await sql`DELETE FROM deals WHERE listing_id = ${listingId}`;
  await sql`DELETE FROM conversations WHERE listing_id = ${listingId}`;
  await sql`DELETE FROM listings WHERE id = ${listingId}`;
  await sql`DELETE FROM users WHERE id IN (${mine})`;
});

describe("แจ้งเตือน", () => {
  let dealId = "";
  let room = "";

  test("ต้องล็อกอิน", async () => {
    expect((await call("GET", "/notifications")).status).toBe(401);
  });

  test("เสนอซื้อ → ผู้ขายได้แจ้งเตือน พร้อม id ห้องแชตไว้แตะไปหน้าที่ถูก · ผู้ซื้อไม่ได้", async () => {
    room = (await call("POST", `/listings/${listingId}/conversations`, users.buyer.token)).data.id;
    dealId = (await call("POST", `/conversations/${room}/deals`, users.buyer.token)).data.id;
    const s = (await call("GET", "/notifications", users.seller.token)).data;
    expect(s.unread).toBe(1);
    expect(s.items[0]).toMatchObject({ type: "DEAL_OFFER", refId: dealId, conversationId: room, listingId, isRead: false });
    expect((await call("GET", "/notifications", users.buyer.token)).data.items).toHaveLength(0);
  });

  test("ตกลง/ส่งมอบ → ผู้ซื้อได้แจ้งเตือน · ?after= ส่งเฉพาะที่ใหม่กว่า", async () => {
    const before = (await call("GET", "/notifications", users.buyer.token)).data;
    expect(before.items).toHaveLength(0);
    await call("POST", `/deals/${dealId}/accept`, users.seller.token);
    await call("POST", `/deals/${dealId}/handover`, users.seller.token);
    const b = (await call("GET", "/notifications", users.buyer.token)).data;
    expect(b.items.map((i: any) => i.type)).toEqual(["DEAL_HANDED_OVER", "DEAL_ACCEPTED"]); // ใหม่สุดก่อน
    const newer = (await call("GET", `/notifications?after=${b.items[1].id}`, users.buyer.token)).data;
    expect(newer.items.map((i: any) => i.type)).toEqual(["DEAL_HANDED_OVER"]);
    expect(newer.unread).toBe(2);
  });

  test("แชตไม่สร้างแจ้งเตือน (BR28)", async () => {
    const n0 = (await call("GET", "/notifications", users.seller.token)).data.unread;
    await call("POST", `/conversations/${room}/messages`, users.buyer.token, { body: "สวัสดีครับ", clientId: crypto.randomUUID() });
    expect((await call("GET", "/notifications", users.seller.token)).data.unread).toBe(n0);
  });

  test("อ่านทีละรายการ · อ่านของคนอื่นไม่ได้ (404) · อ่านทั้งหมด", async () => {
    const list = (await call("GET", "/notifications", users.buyer.token)).data.items;
    const one = await call("POST", `/notifications/${list[0].id}/read`, users.buyer.token);
    expect(one.status).toBe(200);
    expect(one.data.unread).toBe(1);
    expect((await call("POST", `/notifications/${list[1].id}/read`, users.other.token)).status).toBe(404);
    expect((await call("POST", `/notifications/abc/read`, users.buyer.token)).status).toBe(404);
    expect((await call("GET", "/notifications", users.buyer.token)).data.unread).toBe(1); // ของผู้ซื้อไม่ถูกคนอื่นแตะ
    expect((await call("POST", "/notifications/read-all", users.buyer.token)).data.unread).toBe(0);
    expect((await call("GET", "/notifications", users.seller.token)).data.unread).toBeGreaterThan(0); // ของผู้ขายยังอยู่
  });

  test("งานเตือน: ส่งมอบเกิน 24 ชม. → เตือนผู้ซื้อ 1 ครั้ง (รันซ้ำไม่เบิ้ล)", async () => {
    const soon = await sendDealReminders(new Date(), dealId);
    expect(soon.reminders).toBe(0); // เพิ่งส่งมอบ ยังไม่ถึงเวลา
    const later = new Date(Date.now() + 25 * 3_600_000);
    expect((await sendDealReminders(later, dealId)).reminders).toBe(1);
    expect((await sendDealReminders(later, dealId)).reminders).toBe(0);
    const types = (await call("GET", "/notifications", users.buyer.token)).data.items.map((i: any) => i.type);
    expect(types.filter((t: string) => t === "DEAL_REMINDER")).toHaveLength(1);
  });

  test("ดีลสำเร็จ → ชวนรีวิวผู้ซื้อ 1 ครั้ง หลังผ่านไป 1 ชม.", async () => {
    expect((await call("POST", `/deals/${dealId}/receive`, users.buyer.token)).status).toBe(200);
    expect((await sendDealReminders(new Date(), dealId)).reviewPrompts).toBe(0);
    const later = new Date(Date.now() + 2 * 3_600_000);
    expect((await sendDealReminders(later, dealId)).reviewPrompts).toBe(1);
    expect((await sendDealReminders(later, dealId)).reviewPrompts).toBe(0);
    const prompt = (await call("GET", "/notifications", users.buyer.token)).data.items.find((i: any) => i.type === "REVIEW_PROMPT");
    expect(prompt).toMatchObject({ refId: dealId, conversationId: room });
  });
});
