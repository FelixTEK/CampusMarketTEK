import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { pathOf, saveEncrypted } from "../src/identity/storage";
import { LOG_DIR } from "../src/logger";
import { DELETED_NAME, exportLimiter } from "../src/routes/privacy";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);

async function call(method: string, path: string, token?: string, json?: unknown) {
  const headers: Record<string, string> = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (json !== undefined) headers["content-type"] = "application/json";
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body: json !== undefined ? JSON.stringify(json) : undefined }));
  const text = await res.text();
  let data: any = null;
  try { data = JSON.parse(text); } catch { /* ไม่ใช่ JSON */ }
  return { status: res.status, data, text, res };
}

type U = { id: string; token: string };
const users = {} as Record<"me" | "other" | "admin", U>;
let leaf: number;
const MY_EMAIL = `me-${tag}@example.test`;
const OTHER_EMAIL = `other-${tag}@example.test`;
const CARD_KEY = `verifications/test-${tag}/card.enc`;

async function mk(name: keyof typeof users, email: string | null, admin = false) {
  const hash = email ? await Bun.password.hash("Secret-Pass-9999") : null;
  const [u] = await sql`
    INSERT INTO users (email, password_hash, kmitl_sub, display_name, is_verified, is_admin)
    VALUES (${email}, ${hash}, ${email ? null : `test-p-${tag}-${name}`}, ${`ชื่อ-${name}`}, true, ${admin}) RETURNING id`;
  users[name] = { id: u.id, token: await signToken(u.id) };
}

const listing = async (seller: string, title: string, status = "AVAILABLE") =>
  (await sql`INSERT INTO listings (seller_id, category_id, title, description, price, status) VALUES (${seller}, ${leaf}, ${title}, 'รายละเอียดส่วนตัว 081-234-5678', 100, ${status}) RETURNING id`)[0].id as string;

beforeAll(async () => {
  const [r] = await sql`SELECT id FROM categories c WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = c.id) LIMIT 1`;
  leaf = r.id;
  await mk("me", MY_EMAIL);
  await mk("other", OTHER_EMAIL);
  await mk("admin", null, true);
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE id IN (${users.me.id}, ${users.other.id}, ${users.admin.id})`;
  const lst = sql`SELECT id FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM reviews WHERE deal_id IN (SELECT id FROM deals WHERE listing_id IN (${lst}))`;
  await sql`DELETE FROM notifications WHERE user_id IN (${mine})`;
  await sql`DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE listing_id IN (${lst}))`;
  await sql`DELETE FROM deals WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM conversations WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM favorites WHERE user_id IN (${mine})`;
  await sql`DELETE FROM id_card_requests WHERE user_id IN (${mine})`;
  await sql`DELETE FROM listing_images WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM users WHERE id IN (${users.me.id}, ${users.other.id}, ${users.admin.id})`;
  await sql`DELETE FROM users WHERE email = ${`reuse-${tag}@example.test`}`;
});

describe("ดาวน์โหลดข้อมูลของฉัน (GET /me/export)", () => {
  test("ต้องล็อกอิน", async () => {
    expect((await call("GET", "/me/export")).status).toBe(401);
  });

  test("มีข้อมูลของฉันครบทุกหมวด และไม่มีของลับ/ของคนอื่น", async () => {
    const mine = await listing(users.me.id, "ของฉัน");
    await sql`INSERT INTO favorites (user_id, listing_id) VALUES (${users.me.id}, ${mine})`;
    const theirs = await listing(users.other.id, "ของคนอื่น");
    const room = (await call("POST", `/listings/${theirs}/conversations`, users.me.token)).data.id;
    await call("POST", `/conversations/${room}/messages`, users.me.token, { body: "สนใจครับ", clientId: crypto.randomUUID() });
    await call("POST", `/conversations/${room}/messages`, users.other.token, { body: "ยังอยู่ครับ", clientId: crypto.randomUUID() });
    await sql`INSERT INTO notifications (user_id, type, body) VALUES (${users.me.id}, 'TEST', 'แจ้งเตือนทดสอบ')`;

    const r = await call("GET", "/me/export", users.me.token);
    expect(r.status).toBe(200);
    expect(r.res.headers.get("content-disposition")).toContain("attachment");
    const d = r.data;
    expect(d.account).toMatchObject({ id: users.me.id, email: MY_EMAIL, loginMethod: "EMAIL", displayName: "ชื่อ-me" });
    expect(d.listings.map((l: any) => l.title)).toEqual(["ของฉัน"]);
    expect(d.listings[0].description).toContain("081-234-5678"); // ข้อมูลของฉันเอง ส่งคืนให้ได้
    expect(d.favorites.map((f: any) => f.title)).toEqual(["ของฉัน"]);
    expect(d.conversations).toHaveLength(1);
    expect(d.conversations[0]).toMatchObject({ role: "BUYER", otherParty: "ชื่อ-other" });
    const from = d.conversations[0].messages.map((m: any) => `${m.from}:${m.body}`);
    expect(from).toContain("ME:สนใจครับ");
    expect(from).toContain("OTHER:ยังอยู่ครับ");
    expect(d.notifications.map((n: any) => n.body)).toContain("แจ้งเตือนทดสอบ");

    // ห้ามหลุด: แฮชรหัสผ่าน รหัสผ่าน และตัวตนของอีกฝ่าย
    expect(r.text).not.toContain("argon2");
    expect(r.text).not.toContain("Secret-Pass-9999");
    expect(r.text).not.toContain(OTHER_EMAIL);
    expect(r.text).not.toContain("password");
    expect(Object.keys(d.account)).not.toContain("idHash");
  });

  test("จำกัด 5 ครั้งต่อชั่วโมง", async () => {
    exportLimiter.reset(users.other.id);
    for (let i = 0; i < 5; i++) expect((await call("GET", "/me/export", users.other.token)).status).toBe(200);
    expect((await call("GET", "/me/export", users.other.token)).status).toBe(429);
    exportLimiter.reset(users.other.id);
  });
});

describe("ลบบัญชี (DELETE /me, BR21)", () => {
  test("ต้องส่ง confirm: true · ต้องล็อกอิน", async () => {
    expect((await call("DELETE", "/me", users.me.token, {})).status).toBe(422);
    expect((await call("DELETE", "/me", users.me.token, { confirm: false })).status).toBe(422);
    expect((await call("DELETE", "/me", undefined, { confirm: true })).status).toBe(401);
  });

  test("ผู้ดูแลลบตัวเองไม่ได้ (403)", async () => {
    expect((await call("DELETE", "/me", users.admin.token, { confirm: true })).status).toBe(403);
  });

  test("มีดีลที่ยังไม่จบ → 409 และไม่มีอะไรถูกลบ", async () => {
    const l = await listing(users.other.id, "ของที่จะซื้อ");
    const room = (await call("POST", `/listings/${l}/conversations`, users.me.token)).data.id;
    const deal = (await call("POST", `/conversations/${room}/deals`, users.me.token)).data.id;
    const r = await call("DELETE", "/me", users.me.token, { confirm: true });
    expect(r.status).toBe(409);
    expect(r.data.message).toContain("ดีลที่ไม่จบ");
    expect((await sql`SELECT deleted_at, email FROM users WHERE id = ${users.me.id}`)[0]).toMatchObject({ deleted_at: null, email: MY_EMAIL });
    // ยกเลิกดีลแล้วค่อยลบได้
    expect((await call("POST", `/deals/${deal}/cancel`, users.me.token)).status).toBe(200);
  });

  test("ลบสำเร็จ: ล้างข้อมูลระบุตัวตน · ประกาศที่ยังไม่ขายถูกยกเลิกและลบรูป/รายละเอียด · ประกาศที่ขายแล้วคงไว้", async () => {
    const open = await listing(users.me.id, "ยังไม่ขาย");
    const sold = await listing(users.me.id, "ขายแล้ว", "SOLD");
    await sql`INSERT INTO listing_images (listing_id, position, image_key) VALUES (${open}, 0, ${`listings/${open}/0.jpg`})`;
    await sql`INSERT INTO id_card_requests (user_id, card_image_key, status) VALUES (${users.me.id}, ${CARD_KEY}, 'APPROVED')`;
    await saveEncrypted(CARD_KEY, new Uint8Array([1, 2, 3]));
    expect(existsSync(pathOf(CARD_KEY))).toBe(true);

    const r = await call("DELETE", "/me", users.me.token, { confirm: true });
    expect(r.status).toBe(200);

    const [u] = await sql`SELECT email, password_hash, kmitl_sub, id_hash, display_name, faculty_id, is_verified, deleted_at FROM users WHERE id = ${users.me.id}`;
    expect(u).toMatchObject({ email: null, password_hash: null, kmitl_sub: null, id_hash: null, faculty_id: null, is_verified: false, display_name: DELETED_NAME });
    expect(u.deleted_at).not.toBeNull();

    expect((await sql`SELECT status, description FROM listings WHERE id = ${open}`)[0]).toMatchObject({ status: "CANCELLED", description: "" });
    expect((await sql`SELECT count(*)::int AS n FROM listing_images WHERE listing_id = ${open}`)[0].n).toBe(0);
    expect((await sql`SELECT status FROM listings WHERE id = ${sold}`)[0].status).toBe("SOLD");
    expect((await sql`SELECT count(*)::int AS n FROM id_card_requests WHERE user_id = ${users.me.id}`)[0].n).toBe(0);
    expect((await sql`SELECT count(*)::int AS n FROM favorites WHERE user_id = ${users.me.id}`)[0].n).toBe(0);
    expect((await sql`SELECT count(*)::int AS n FROM notifications WHERE user_id = ${users.me.id}`)[0].n).toBe(0);
    expect(existsSync(pathOf(CARD_KEY))).toBe(false);
  });

  test("หลังลบ: token เดิมใช้ไม่ได้ · ล็อกอินด้วยอีเมลเดิมไม่ได้ · ประกาศหายจากการค้นหา · อีกฝ่ายเห็นชื่อแทน", async () => {
    expect((await call("GET", "/users/me", users.me.token)).status).toBe(401);
    expect((await call("POST", "/auth/login", undefined, { email: MY_EMAIL, password: "Secret-Pass-9999" })).status).toBe(401);
    expect((await call("GET", `/users/${users.me.id}`, users.other.token)).status).toBe(404);
    const rooms = (await call("GET", "/conversations", users.other.token)).data as any[];
    expect(rooms.length).toBeGreaterThan(0);
    expect(rooms.some((x) => x.other.displayName === DELETED_NAME)).toBe(true);
  });

  test("สมัครใหม่ด้วยอีเมลเดิมได้ (ข้อมูลเก่าไม่ผูกอยู่แล้ว)", async () => {
    const email = `reuse-${tag}@example.test`;
    const first = await call("POST", "/auth/register", undefined, { email, password: "Another-Pass-1234", displayName: "เจ้าของเดิม" });
    expect(first.status).toBe(200);
    expect((await call("DELETE", "/me", first.data.token, { confirm: true })).status).toBe(200);
    const again = await call("POST", "/auth/register", undefined, { email, password: "Another-Pass-1234", displayName: "คนเดิมกลับมา" });
    expect(again.status).toBe(200);
  });
});

describe("ตรวจ log: ไม่มีรหัสผ่าน / token / เลขบัตร (BR20)", () => {
  test("คำขอที่มีความลับใน body, query และ header ไม่ทิ้งความลับไว้ในไฟล์ log", async () => {
    const SECRET_PW = `PwSecret-${tag}-zzz`;
    const SECRET_QS = `QsSecret-${tag}-zzz`;
    const NATIONAL_ID = "1101700230075";
    await call("POST", "/auth/login", undefined, { email: `nobody-${tag}@example.test`, password: SECRET_PW });
    await call("GET", `/ws?token=${SECRET_QS}&code=${SECRET_QS}`);
    await call("GET", `/users/me?access_token=${SECRET_QS}`, `Bearer-${SECRET_QS}`);
    await call("POST", "/auth/exchange", undefined, { code: SECRET_QS });
    await call("POST", `/admin/verifications/${crypto.randomUUID()}/approve`, users.admin.token, { nationalId: NATIONAL_ID });
    await Bun.sleep(300); // writeLog เป็น async

    const today = new Date().toISOString().slice(0, 10);
    const log = await readFile(join(LOG_DIR, `${today}.log`), "utf8");
    expect(log).toContain("/auth/login"); // log ทำงานอยู่จริง (กัน false pass)
    for (const secret of [SECRET_PW, SECRET_QS, NATIONAL_ID, "Secret-Pass-9999"]) expect(log).not.toContain(secret);
    expect(log).not.toMatch(/authorization/i);
  });
});

describe("ระยะเก็บข้อมูล (retention)", () => {
  test("แชตเงียบเกิน 1 ปีถูกลบทั้งห้อง · ห้องที่ยังมีข้อความใหม่ไม่ถูกแตะ · รายงานที่ยังเปิดไม่ถูกลบ", async () => {
    const { purgeOldChats, purgeOldReports } = await import("../src/jobs/retention");
    const l1 = await listing(users.admin.id, "ห้องเก่า");
    const l2 = await listing(users.admin.id, "ห้องใหม่");
    const [oldRoom] = await sql`INSERT INTO conversations (listing_id, buyer_id, created_at) VALUES (${l1}, ${users.other.id}, now() - interval '400 days') RETURNING id`;
    const [newRoom] = await sql`INSERT INTO conversations (listing_id, buyer_id, created_at) VALUES (${l2}, ${users.other.id}, now() - interval '400 days') RETURNING id`;
    await sql`INSERT INTO messages (conversation_id, sender_id, body, created_at) VALUES (${oldRoom.id}, ${users.other.id}, 'เก่ามาก', now() - interval '380 days')`;
    await sql`INSERT INTO messages (conversation_id, sender_id, body, created_at) VALUES (${newRoom.id}, ${users.other.id}, 'เก่า', now() - interval '380 days')`;
    await sql`INSERT INTO messages (conversation_id, sender_id, body, created_at) VALUES (${newRoom.id}, ${users.other.id}, 'ใหม่', now() - interval '2 days')`;

    expect(await purgeOldChats()).toBeGreaterThanOrEqual(1);
    expect((await sql`SELECT count(*)::int AS n FROM conversations WHERE id = ${oldRoom.id}`)[0].n).toBe(0);
    expect((await sql`SELECT count(*)::int AS n FROM messages WHERE conversation_id = ${oldRoom.id}`)[0].n).toBe(0);
    expect((await sql`SELECT count(*)::int AS n FROM messages WHERE conversation_id = ${newRoom.id}`)[0].n).toBe(2);

    const mkReport = async (status: string, target: string) =>
      (await sql`INSERT INTO reports (reporter_id, target_type, target_id, reason, status, admin_note, resolved_by, created_at)
                 VALUES (${users.other.id}, 'USER', ${target}, 'สแปม', ${status}, ${status === "CLOSED" ? "ok" : null}, ${status === "CLOSED" ? users.admin.id : null}, now() - interval '500 days') RETURNING id`)[0].id as string;
    const closed = await mkReport("CLOSED", users.me.id);
    const open = await mkReport("OPEN", users.admin.id);
    await purgeOldReports();
    expect((await sql`SELECT count(*)::int AS n FROM reports WHERE id = ${closed}`)[0].n).toBe(0);
    expect((await sql`SELECT count(*)::int AS n FROM reports WHERE id = ${open}`)[0].n).toBe(1);
    await sql`DELETE FROM reports WHERE id = ${open}`;
  });
});
