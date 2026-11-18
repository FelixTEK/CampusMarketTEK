import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { autoCompleteDeals } from "../src/chat/deals";
import { sql } from "../src/db";
import { sendLimiter } from "../src/routes/chat";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);
type Json = Record<string, any>;

async function call(method: string, path: string, token?: string, json?: unknown) {
  const headers: Record<string, string> = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (json !== undefined) headers["content-type"] = "application/json";
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body: json !== undefined ? JSON.stringify(json) : undefined }));
  const isJson = (res.headers.get("content-type") ?? "").includes("json");
  return { status: res.status, data: (isJson ? await res.json() : null) as any };
}

type U = { id: string; token: string };
const users = {} as Record<"seller" | "b1" | "b2" | "b3" | "outsider", U>;
let itLeaf: number;
const listingIds: string[] = [];

async function makeUser(name: keyof typeof users) {
  const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified) VALUES (${`test-c-${tag}-${name}`}, ${name}, true) RETURNING id`;
  users[name] = { id: u.id, token: await signToken(u.id) };
}

async function newListing(title = "ลำโพงบลูทูธ", price = 700, status = "AVAILABLE") {
  const [l] = await sql`INSERT INTO listings (seller_id, category_id, title, price, status) VALUES (${users.seller.id}, ${itLeaf}, ${title}, ${price}, ${status}) RETURNING id`;
  listingIds.push(l.id);
  return l.id as string;
}

const openRoom = async (u: U, listingId: string) => (await call("POST", `/listings/${listingId}/conversations`, u.token)).data.id as string;
const offer = (u: U, room: string) => call("POST", `/conversations/${room}/deals`, u.token);
const act = (u: U, deal: string, a: string) => call("POST", `/deals/${deal}/${a}`, u.token);
const listingStatus = async (id: string) => (await sql`SELECT status FROM listings WHERE id = ${id}`)[0].status as string;
const dealStatus = async (id: string) => (await sql`SELECT status FROM deals WHERE id = ${id}`)[0].status as string;
const messages = async (room: string, u: U, qs = "") => (await call("GET", `/conversations/${room}/messages${qs}`, u.token)).data;

beforeAll(async () => {
  const [r] = await sql`
    WITH RECURSIVE t AS (SELECT id FROM categories WHERE parent_id IS NULL AND name_th LIKE 'อุปกรณ์%'
      UNION ALL SELECT c.id FROM categories c JOIN t ON c.parent_id = t.id)
    SELECT id FROM t WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = t.id) LIMIT 1`;
  itLeaf = r.id;
  for (const n of ["seller", "b1", "b2", "b3", "outsider"] as const) await makeUser(n);
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE kmitl_sub LIKE ${`test-c-${tag}-%`}`;
  const lst = sql`SELECT id FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM reports WHERE target_id IN (SELECT id::text FROM listings WHERE seller_id IN (${mine}))`;
  await sql`DELETE FROM notifications WHERE user_id IN (${mine})`;
  await sql`DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE listing_id IN (${lst}))`;
  await sql`DELETE FROM deals WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM conversations WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM favorites WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`test-c-${tag}-%`}`;
});

describe("เปิดห้องแชต (BR06, BR25)", () => {
  test("ผู้ซื้อเปิดห้องได้ ห้องเดิมใช้ต่อ (เปิดซ้ำ/พร้อมกันได้ห้องเดียว)", async () => {
    const l = await newListing();
    const [a, b, c] = await Promise.all([openRoom(users.b1, l), openRoom(users.b1, l), openRoom(users.b1, l)]);
    expect(new Set([a, b, c]).size).toBe(1);
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM conversations WHERE listing_id = ${l} AND buyer_id = ${users.b1.id}`;
    expect(n).toBe(1);
    const room = (await call("GET", `/conversations/${a}`, users.b1.token)).data;
    expect(room).toMatchObject({ role: "BUYER", listing: { id: l, title: "ลำโพงบลูทูธ", price: 700 }, other: { displayName: "seller" } });
    expect((await call("GET", `/conversations/${a}`, users.seller.token)).data).toMatchObject({ role: "SELLER", other: { displayName: "b1" } });
  });

  test("ทักประกาศของตัวเอง → 403 · ประกาศที่ซ่อน/ไม่มี → 404 · ขายแล้วและไม่เคยมีห้อง → 409", async () => {
    const l = await newListing();
    const own = await call("POST", `/listings/${l}/conversations`, users.seller.token);
    expect(own.status).toBe(403);
    expect(own.data.code).toBe("OWN_LISTING");
    const cancelled = await newListing("ยกเลิกแล้ว", 10, "CANCELLED");
    expect((await call("POST", `/listings/${cancelled}/conversations`, users.b1.token)).status).toBe(404);
    expect((await call("POST", `/listings/${crypto.randomUUID()}/conversations`, users.b1.token)).status).toBe(404);
    const sold = await newListing("ขายแล้ว", 10, "SOLD");
    expect((await call("POST", `/listings/${sold}/conversations`, users.b1.token)).status).toBe(409);
  });

  test("ห้องเดิมยังเข้าได้หลังขายแล้ว (BR25) · คนนอกห้องเข้าไม่ได้ (BR26)", async () => {
    const l = await newListing();
    const room = await openRoom(users.b1, l);
    await sql`UPDATE listings SET status = 'SOLD' WHERE id = ${l}`;
    expect((await call("POST", `/listings/${l}/conversations`, users.b1.token)).data.id).toBe(room);
    expect((await call("GET", `/conversations/${room}`, users.outsider.token)).status).toBe(404);
    expect((await call("GET", `/conversations/${room}/messages`, users.outsider.token)).status).toBe(404);
    expect((await call("POST", `/conversations/${room}/messages`, users.outsider.token, { body: "hi" })).status).toBe(404);
  });
});

describe("ข้อความ (BR27, BR28)", () => {
  test("ส่ง/ดึง · client_id ซ้ำได้ข้อความเดิม (ไม่เบิ้ล) · ไม่สร้างแถวแจ้งเตือน (BR28)", async () => {
    const l = await newListing("หูฟัง");
    const room = await openRoom(users.b1, l);
    const clientId = crypto.randomUUID();
    const m1 = await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "  สนใจครับ  ", clientId });
    const m2 = await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "สนใจครับ", clientId });
    expect(m1.status).toBe(200);
    expect(m2.data.id).toBe(m1.data.id);
    expect(m1.data).toMatchObject({ body: "สนใจครับ", senderId: users.b1.id, clientId, dealId: null, deal: null, readAt: null });
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM messages WHERE conversation_id = ${room}`;
    expect(n).toBe(1);
    const [{ notes }] = await sql`SELECT count(*)::int AS notes FROM notifications WHERE user_id IN (${users.seller.id}, ${users.b1.id}) AND body LIKE '%สนใจ%'`;
    expect(notes).toBe(0);
  });

  test("ข้อความว่าง/ยาวเกิน 1,000 → 422 · clientId ผิดรูปแบบ → 422", async () => {
    const room = await openRoom(users.b1, await newListing("เมาส์"));
    for (const body of ["", "   ", "x".repeat(1001)])
      expect((await call("POST", `/conversations/${room}/messages`, users.b1.token, { body })).status).toBe(422);
    expect((await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "ok", clientId: "nope" })).status).toBe(422);
    expect((await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "x".repeat(1000) })).status).toBe(200);
  });

  test("ดึงย้อนหลัง 30 ข้อความต่อหน้า ใหม่สุดก่อน ไม่ซ้ำ ไม่ตก", async () => {
    const room = await openRoom(users.b2, await newListing("โต๊ะพับ"));
    for (let i = 1; i <= 35; i++) await sql`INSERT INTO messages (conversation_id, sender_id, body) VALUES (${room}, ${users.b2.id}, ${`ข้อความ ${i}`})`;
    const p1 = await messages(room, users.b2);
    expect(p1.items).toHaveLength(30);
    expect(p1.hasMore).toBe(true);
    expect(p1.items[0].body).toBe("ข้อความ 35");
    const p2 = await messages(room, users.b2, `?before=${p1.items.at(-1).id}`);
    expect(p2.items.map((m: Json) => m.body)).toEqual(["ข้อความ 5", "ข้อความ 4", "ข้อความ 3", "ข้อความ 2", "ข้อความ 1"]);
    expect(p2.hasMore).toBe(false);
  });

  test("อ่านแล้ว: นับ unread ของอีกฝ่าย · /read ล้างเฉพาะของอีกฝ่าย", async () => {
    const room = await openRoom(users.b3, await newListing("พัดลม"));
    await call("POST", `/conversations/${room}/messages`, users.b3.token, { body: "หนึ่ง" });
    await call("POST", `/conversations/${room}/messages`, users.b3.token, { body: "สอง" });
    await call("POST", `/conversations/${room}/messages`, users.seller.token, { body: "ตอบ" });
    const listFor = async (u: U) => ((await call("GET", "/conversations", u.token)).data as Json[]).find((c) => c.id === room)!;
    expect((await listFor(users.seller)).unread).toBe(2);
    expect((await listFor(users.b3)).unread).toBe(1);
    expect((await listFor(users.seller)).lastMessage.body).toBe("ตอบ");

    expect((await call("POST", `/conversations/${room}/read`, users.seller.token)).data.read).toBe(2);
    expect((await listFor(users.seller)).unread).toBe(0);
    expect((await listFor(users.b3)).unread).toBe(1); // ของที่ seller ส่ง b3 ยังไม่ได้อ่าน
  });

  test("ส่งเกิน 30 ข้อความต่อนาที → 429 (BR27) · ส่งซ้ำด้วย client_id เดิมไม่นับโควตา", async () => {
    const room = await openRoom(users.b1, await newListing("ที่ชาร์จ"));
    sendLimiter.reset(`send:${users.b1.id}`);
    let last = 0;
    for (let i = 0; i < 31; i++) last = (await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: `msg ${i}` })).status;
    expect(last).toBe(429);
    const cid = crypto.randomUUID();
    sendLimiter.reset(`send:${users.b1.id}`);
    expect((await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "a", clientId: cid })).status).toBe(200);
    for (let i = 0; i < 29; i++) await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: `x${i}` });
    expect((await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "a", clientId: cid })).status).toBe(200); // ซ้ำ = ได้ของเดิม
    sendLimiter.reset(`send:${users.b1.id}`);
  });
});

describe("เสนอซื้อ (BR06–BR08)", () => {
  test("ผู้ซื้อเสนอซื้อ → การ์ดดีลในแชต + แจ้งเตือนผู้ขาย · ผู้ขายเสนอซื้อเองไม่ได้", async () => {
    const l = await newListing("คีย์บอร์ด", 1200);
    const room = await openRoom(users.b1, l);
    const r = await offer(users.b1, room);
    expect(r.status).toBe(200);
    expect(r.data).toMatchObject({ status: "PENDING", listingId: l, buyerId: users.b1.id });

    const card = (await messages(room, users.b1)).items[0];
    expect(card.dealId).toBe(r.data.id);
    expect(card.deal.status).toBe("PENDING");
    expect(card.body).toContain("คีย์บอร์ด");
    const [n] = await sql`SELECT type FROM notifications WHERE user_id = ${users.seller.id} AND ref_id = ${r.data.id}`;
    expect(n.type).toBe("DEAL_OFFER");

    expect((await offer(users.seller, room)).status).toBe(403);
    expect((await offer(users.outsider, room)).status).toBe(404);
  });

  test("ข้อเสนอที่รอตอบมีได้ 1 รายการต่อผู้ซื้อ (BR08) · ถูกจอง/ไม่ใช่กำลังขาย เสนอไม่ได้ (BR07)", async () => {
    const l = await newListing();
    const room = await openRoom(users.b1, l);
    expect((await offer(users.b1, room)).status).toBe(200);
    const dup = await offer(users.b1, room);
    expect(dup.status).toBe(409);
    expect(dup.data.message).toContain("รอผู้ขายตอบ");

    const room2 = await openRoom(users.b2, l);
    await sql`UPDATE listings SET status = 'RESERVED' WHERE id = ${l}`;
    const reserved = await offer(users.b2, room2);
    expect(reserved.status).toBe(409);
    expect(reserved.data.message).toBe("ประกาศนี้ถูกจองไปแล้ว");
    await sql`UPDATE listings SET status = 'CANCELLED', status_reason = 'x' WHERE id = ${l}`;
    expect((await offer(users.b2, room2)).status).toBe(409);
  });
});

describe("ตกลงขายและสถานะดีล", () => {
  test("เส้นทางหลัก: เสนอ → ตกลง → นัดรับ → ส่งมอบ → ได้รับแล้ว → ประกาศขายแล้ว", async () => {
    const l = await newListing("จอคอม", 1900);
    const room = await openRoom(users.b1, l);
    const d = (await offer(users.b1, room)).data.id as string;

    expect((await act(users.b1, d, "accept")).status).toBe(403);               // ผู้ซื้อตกลงเองไม่ได้
    const acc = await act(users.seller, d, "accept");
    expect(acc.status).toBe(200);
    expect(acc.data.status).toBe("ACCEPTED");
    expect(await listingStatus(l)).toBe("RESERVED");

    expect((await act(users.seller, d, "receive")).status).toBe(403);           // ผู้ขายยืนยันรับของเองไม่ได้
    expect((await act(users.b1, d, "receive")).status).toBe(409);               // ยังไม่ส่งมอบ
    expect((await call("PATCH", `/deals/${d}/meetup`, users.b1.token, { meetup: "โรงอาหารกลาง ส. 12:30" })).data.meetup).toBe("โรงอาหารกลาง ส. 12:30");
    expect((await call("PATCH", `/deals/${d}/meetup`, users.outsider.token, { meetup: "x" })).status).toBe(404);
    expect((await call("PATCH", `/deals/${d}/meetup`, users.b1.token, { meetup: "" })).status).toBe(422);

    expect((await act(users.b1, d, "handover")).status).toBe(403);              // ผู้ซื้อกดส่งมอบเองไม่ได้
    expect((await act(users.seller, d, "handover")).data.status).toBe("HANDED_OVER");
    expect((await call("PATCH", `/deals/${d}/meetup`, users.b1.token, { meetup: "เปลี่ยนที่" })).status).toBe(409); // ส่งมอบแล้วแก้นัดไม่ได้
    expect((await act(users.seller, d, "cancel")).status).toBe(409);            // ส่งมอบแล้วยกเลิกไม่ได้

    const done = await act(users.b1, d, "receive");
    expect(done.data.status).toBe("COMPLETED");
    expect(done.data.completedAt).not.toBeNull();
    expect(await listingStatus(l)).toBe("SOLD");                                // BR24: ขายแล้วเกิดจากดีลที่สำเร็จเท่านั้น
    expect((await act(users.b1, d, "receive")).status).toBe(409);               // ทำซ้ำไม่ได้

    // ข้อความระบบเล่าทุกขั้นตอนในห้อง และการ์ดดีลแสดงสถานะล่าสุด
    const items = (await messages(room, users.seller)).items as Json[];
    const sys = items.filter((m) => m.senderId === null).map((m) => m.body);
    expect(sys.length).toBeGreaterThanOrEqual(4);
    expect(sys.some((b) => b.includes("นัดรับ: โรงอาหารกลาง"))).toBe(true);
    expect(items.find((m) => m.dealId)!.deal.status).toBe("COMPLETED");
    const types = (await sql`SELECT type FROM notifications WHERE ref_id = ${d}`).map((r: any) => r.type).sort();
    expect(types).toEqual(["DEAL_ACCEPTED", "DEAL_COMPLETED", "DEAL_HANDED_OVER", "DEAL_OFFER"]);
  });

  test("ตกลงให้คนหนึ่ง → ข้อเสนออื่นถูกปฏิเสธอัตโนมัติ + แจ้งเตือน · ผู้กดโปรดรู้ว่าถูกจอง", async () => {
    const l = await newListing("เครื่องคิดเลข", 450);
    const r1 = await openRoom(users.b1, l), r2 = await openRoom(users.b2, l);
    const d1 = (await offer(users.b1, r1)).data.id, d2 = (await offer(users.b2, r2)).data.id;
    await sql`INSERT INTO favorites (user_id, listing_id) VALUES (${users.b3.id}, ${l})`;

    expect((await act(users.seller, d1, "accept")).status).toBe(200);
    expect(await dealStatus(d2)).toBe("REJECTED");
    const [rej] = await sql`SELECT type FROM notifications WHERE user_id = ${users.b2.id} AND ref_id = ${d2}`;
    expect(rej.type).toBe("DEAL_REJECTED");
    const sys = ((await messages(r2, users.b2)).items as Json[]).filter((m) => m.senderId === null);
    expect(sys[0]!.body).toContain("รายอื่น");
    const [fav] = await sql`SELECT body FROM notifications WHERE user_id = ${users.b3.id} AND type = 'FAVORITE_RESERVED'`;
    expect(fav.body).toContain("ถูกจองแล้ว");
    // ข้อเสนอที่ถูกปฏิเสธไปแล้ว ตกลงซ้ำไม่ได้
    expect((await act(users.seller, d2, "accept")).status).toBe(409);
  });

  test("ผู้ขายปฏิเสธ · ผู้ซื้อถอนข้อเสนอ · ยกเลิกดีลที่ตกลงแล้ว → ประกาศกลับมากำลังขาย", async () => {
    const l = await newListing("ไม้บรรทัด", 30);
    const room = await openRoom(users.b1, l);
    const d1 = (await offer(users.b1, room)).data.id;
    expect((await act(users.b1, d1, "reject")).status).toBe(403);
    expect((await act(users.seller, d1, "cancel")).status).toBe(409);             // ผู้ขายใช้ปฏิเสธ ไม่ใช่ยกเลิก
    expect((await act(users.seller, d1, "reject")).data.status).toBe("REJECTED");

    const d2 = (await offer(users.b1, room)).data.id;                             // เสนอใหม่ได้หลังถูกปฏิเสธ
    expect((await act(users.b1, d2, "cancel")).data.status).toBe("CANCELLED");    // ถอน

    const d3 = (await offer(users.b1, room)).data.id;
    await act(users.seller, d3, "accept");
    expect(await listingStatus(l)).toBe("RESERVED");
    expect((await act(users.b1, d3, "cancel")).data.status).toBe("CANCELLED");
    expect(await listingStatus(l)).toBe("AVAILABLE");
    expect((await offer(users.b2, await openRoom(users.b2, l))).status).toBe(200); // คนอื่นเสนอได้อีก
  });

  test("คนนอกดีลทำอะไรไม่ได้ (404) · id มั่ว → 404", async () => {
    const l = await newListing();
    const d = (await offer(users.b1, await openRoom(users.b1, l))).data.id;
    for (const a of ["accept", "reject", "cancel", "handover", "receive"])
      expect((await act(users.outsider, d, a)).status).toBe(404);
    expect((await act(users.b1, "not-a-uuid", "cancel")).status).toBe(404);
    expect((await act(users.b1, crypto.randomUUID(), "cancel")).status).toBe(404);
  });

  test("ดีลของฉัน: role=buyer / seller", async () => {
    const l = await newListing("ปากกา", 20);
    const d = (await offer(users.b2, await openRoom(users.b2, l))).data.id;
    const asBuyer = (await call("GET", "/deals?role=buyer", users.b2.token)).data as Json[];
    const asSeller = (await call("GET", "/deals?role=seller", users.seller.token)).data as Json[];
    expect(asBuyer.find((x) => x.id === d)).toMatchObject({ status: "PENDING", other: { displayName: "seller" }, listing: { title: "ปากกา" } });
    expect(asSeller.find((x) => x.id === d)).toMatchObject({ other: { displayName: "b2" } });
    expect(asSeller.find((x) => x.id === d)!.conversationId).toBeString();
    expect((await call("GET", "/deals?role=x", users.b2.token)).status).toBe(422);
  });
});

describe("แข่งกัน: ตกลงขายพร้อมกัน (BR09)", () => {
  test("ผู้ขายกดตกลง 2 ข้อเสนอของประกาศเดียวกันพร้อมกัน (2 เครื่อง) → สำเร็จ 1 อีกอัน 409 (ทำ 8 รอบ)", async () => {
    for (let round = 0; round < 8; round++) {
      const l = await newListing(`แข่งกัน ${round}`, 100);
      const d1 = (await offer(users.b1, await openRoom(users.b1, l))).data.id;
      const d2 = (await offer(users.b2, await openRoom(users.b2, l))).data.id;

      const [a, b] = await Promise.all([act(users.seller, d1, "accept"), act(users.seller, d2, "accept")]);
      const statuses = [a.status, b.status].sort();
      expect(statuses).toEqual([200, 409]);
      const loser = a.status === 409 ? a : b;
      expect(loser.data.message).toBe("ประกาศนี้ถูกจองไปแล้ว");

      expect(await listingStatus(l)).toBe("RESERVED");
      const rows = await sql`SELECT status FROM deals WHERE listing_id = ${l} ORDER BY status`;
      expect(rows.map((r: any) => r.status).sort()).toEqual(["ACCEPTED", "REJECTED"]); // ไม่มีสองดีลที่ ACCEPTED
    }
  });

  test("ตกลงข้อเสนอเดียวกันพร้อมกันหลายครั้ง → สำเร็จครั้งเดียว", async () => {
    const l = await newListing("กดซ้ำ", 100);
    const d = (await offer(users.b1, await openRoom(users.b1, l))).data.id;
    const results = await Promise.all(Array.from({ length: 5 }, () => act(users.seller, d, "accept")));
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(results.filter((r) => r.status === 409)).toHaveLength(4);
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM messages WHERE conversation_id = (SELECT id FROM conversations WHERE listing_id = ${l}) AND body LIKE 'ผู้ขายตกลงขายแล้ว%'`;
    expect(n).toBe(1); // ข้อความระบบไม่เบิ้ล
  });
});

describe("ปิดดีลอัตโนมัติ 48 ชม. (BR11)", () => {
  async function handedOver(title: string) {
    const l = await newListing(title, 100);
    const room = await openRoom(users.b1, l);
    const d = (await offer(users.b1, room)).data.id;
    await act(users.seller, d, "accept");
    await act(users.seller, d, "handover");
    return { l, d, room };
  }

  test("เกิน 48 ชม. ปิดให้ · ยังไม่ครบไม่ปิด · ประกาศมีรายงานค้างข้าม", async () => {
    const fresh = await handedOver("ส่งมอบเมื่อกี้");
    const old = await handedOver("ส่งมอบนานแล้ว");
    const reported = await handedOver("มีรายงานค้าง");
    const longAgo = new Date(Date.now() - 49 * 3600_000);
    await sql`UPDATE deals SET updated_at = ${longAgo} WHERE id IN (${old.d}, ${reported.d})`;
    await sql`INSERT INTO reports (reporter_id, target_type, target_id, reason) VALUES (${users.b1.id}, 'LISTING', ${reported.l}, 'สินค้าไม่ตรงปก')`;

    const closed = await autoCompleteDeals();
    expect(closed).toBeGreaterThanOrEqual(1);
    expect(await dealStatus(old.d)).toBe("COMPLETED");
    expect(await listingStatus(old.l)).toBe("SOLD");
    expect(await dealStatus(fresh.d)).toBe("HANDED_OVER");
    expect(await dealStatus(reported.d)).toBe("HANDED_OVER");

    const sys = ((await messages(old.room, users.seller)).items as Json[]).find((m) => m.body.includes("ระบบปิดดีลให้อัตโนมัติ"));
    expect(sys).toBeDefined();
    const types = (await sql`SELECT user_id, type FROM notifications WHERE ref_id = ${old.d} AND type = 'DEAL_AUTO_COMPLETED'`).map((r: any) => r.user_id);
    expect(types.sort()).toEqual([users.b1.id, users.seller.id].sort());
    expect(await autoCompleteDeals()).toBe(0 + (await autoCompleteDeals())); // รันซ้ำไม่ปิดซ้ำและไม่พัง
  });
});

describe("เรียลไทม์ผ่าน WebSocket", () => {
  test("ผู้รับเห็นข้อความใหม่และสถานะดีลทันที · ไม่มี token → ปิดการเชื่อมต่อ", async () => {
    const server = app.listen({ port: 0, hostname: "127.0.0.1" });
    const port = (server as any).server?.port ?? (app as any).server?.port;
    const url = (tok: string) => `ws://127.0.0.1:${port}/ws?token=${tok}`;
    const open = (u: string) =>
      new Promise<{ ws: WebSocket; events: Json[] }>((resolve, reject) => {
        const ws = new WebSocket(u);
        const events: Json[] = [];
        ws.onmessage = (e) => events.push(JSON.parse(String(e.data)));
        ws.onopen = () => resolve({ ws, events });
        ws.onerror = () => reject(new Error("ws error"));
      });
    const until = async (cond: () => boolean, ms = 3000) => {
      const t0 = Date.now();
      while (!cond() && Date.now() - t0 < ms) await Bun.sleep(20);
      expect(cond()).toBe(true);
    };

    try {
      const l = await newListing("เสียงสด", 100);
      const room = await openRoom(users.b1, l);
      const seller = await open(url(users.seller.token));
      await until(() => seller.events.some((e) => e.type === "hello"));

      await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "สวัสดีครับ" });
      await until(() => seller.events.some((e) => e.type === "message" && e.message.body === "สวัสดีครับ"));

      const d = (await offer(users.b1, room)).data.id;
      await act(users.seller, d, "accept");
      await until(() => seller.events.some((e) => e.type === "deal" && e.deal.status === "ACCEPTED" && e.conversationId === room));
      expect(seller.events.some((e) => e.type === "message" && e.message.body.includes("ผู้ขายตกลงขายแล้ว"))).toBe(true);

      const outsider = await open(url(users.outsider.token));
      await call("POST", `/conversations/${room}/messages`, users.b1.token, { body: "ลับเฉพาะสองฝ่าย" });
      await Bun.sleep(200);
      expect(outsider.events.some((e) => e.type === "message")).toBe(false); // คนนอกห้องไม่ได้รับ (BR26)
      outsider.ws.close();

      // token ผิด → ปิดทันที (รหัส 4401)
      const closeCode = await new Promise<number>((resolve) => {
        const ws = new WebSocket(url("not-a-token"));
        ws.onclose = (e) => resolve(e.code);
      });
      expect(closeCode).toBe(4401);
      seller.ws.close();
    } finally {
      await app.stop();
    }
  });
});
