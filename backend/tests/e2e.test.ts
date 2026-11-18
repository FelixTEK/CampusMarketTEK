import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { rm } from "node:fs/promises";
import { SignJWT } from "jose";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { env } from "../src/env";
import { sql } from "../src/db";
import { pathOf } from "../src/identity/storage";
import { withChecksum } from "./helpers";

/**
 * Phase 13 — ทดสอบแบบครบเส้นทางผู้ใช้จริง (ทีละคนต่อกัน ไม่ใช่พร้อมกัน — โครงงานนี้เป็น proof of concept)
 *  1) เส้นทางปกติ: สมัคร → ยืนยันตัวตน → ลงขาย → ค้น → โปรด → แชต → เสนอซื้อ → ตกลง → ส่งมอบ → ได้รับ → รีวิว → รายงาน → ผู้ดูแลปิด → สถิติ → ส่งออก → ลบบัญชี
 *  2) ผิดพลาด: ยิง API ทุกเส้นทางโดยไม่มีสิทธิ์ · token เสีย/หมดอายุ/ปลอม · ฟอร์มผิด
 * ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างถูกลบตอนจบ
 */
const tag = crypto.randomUUID().slice(0, 8);
const PASSWORD = "E2e-Pass-12345";

type Opts = { token?: string; json?: unknown; form?: FormData };
async function call(method: string, path: string, o: Opts = {}) {
  const headers: Record<string, string> = {};
  if (o.token) headers.authorization = `Bearer ${o.token}`;
  if (o.json !== undefined) headers["content-type"] = "application/json";
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body: o.form ?? (o.json !== undefined ? JSON.stringify(o.json) : undefined) }));
  const text = await res.text();
  let data: any = null;
  try { data = JSON.parse(text); } catch { /* ไม่ใช่ JSON */ }
  return { status: res.status, data, text };
}

const jpeg = (n: number) => new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, n, 1, 2, 3])], { type: "image/jpeg" });

type U = { id: string; token: string };
const u = {} as Record<"seller" | "buyer" | "admin" | "normal", U>;
const created = { requestIds: [] as string[] };
let leaf: number;

beforeAll(async () => {
  // หมวดใบสุดท้ายของ "อุปกรณ์…" (หมวดหนังสือบังคับระบุคณะ/แท็ก จึงไม่ใช้)
  const [r] = await sql`
    WITH RECURSIVE t AS (SELECT id FROM categories WHERE parent_id IS NULL AND name_th LIKE 'อุปกรณ์%'
      UNION ALL SELECT c.id FROM categories c JOIN t ON c.parent_id = t.id)
    SELECT id FROM t WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = t.id) LIMIT 1`;
  leaf = r.id;
  const [a] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified, is_admin) VALUES (${`test-e-${tag}-admin`}, 'ผู้ดูแล e2e', true, true) RETURNING id`;
  u.admin = { id: a.id, token: await signToken(a.id) };
});

afterAll(async () => {
  const ids = Object.values(u).map((x) => x?.id).filter(Boolean);
  if (ids.length) {
    const mine = sql`SELECT id FROM users WHERE id IN ${sql(ids)}`;
    const lst = sql`SELECT id FROM listings WHERE seller_id IN (${mine})`;
    for (const id of created.requestIds) await rm(pathOf(`verifications/${id}`), { recursive: true, force: true });
    await sql`DELETE FROM reports WHERE reporter_id IN ${sql(ids)}`;
    await sql`DELETE FROM reviews WHERE deal_id IN (SELECT id FROM deals WHERE listing_id IN (${lst}))`;
    await sql`DELETE FROM notifications WHERE user_id IN ${sql(ids)}`;
    await sql`DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE listing_id IN (${lst}))`;
    await sql`DELETE FROM deals WHERE listing_id IN (${lst})`;
    await sql`DELETE FROM conversations WHERE listing_id IN (${lst})`;
    await sql`DELETE FROM favorites WHERE user_id IN ${sql(ids)} OR listing_id IN (${lst})`;
    await sql`DELETE FROM listing_images WHERE listing_id IN (${lst})`;
    await sql`DELETE FROM listings WHERE seller_id IN ${sql(ids)}`;
    await sql`DELETE FROM id_card_requests WHERE user_id IN ${sql(ids)}`;
    await sql`DELETE FROM users WHERE id IN ${sql(ids)}`;
  }
});

describe("เส้นทางปกติตั้งแต่สมัครจนลบบัญชี", () => {
  let listingId = "";
  let room = "";
  let dealId = "";

  test("E2E-01 ผู้ขายสมัครด้วยอีเมล แต่ยังใช้งานไม่ได้จนกว่าจะยืนยันตัวตน", async () => {
    const r = await call("POST", "/auth/register", { json: { email: `seller-${tag}@example.test`, password: PASSWORD, displayName: "ผู้ขาย e2e" } });
    expect(r.status).toBe(200);
    u.seller = { id: r.data.user.id, token: r.data.token };
    expect(r.data.user.isVerified).toBe(false);
    const fd = new FormData();
    fd.set("categoryId", String(leaf)); fd.set("title", "ลำโพง e2e"); fd.set("price", "300"); fd.append("images", jpeg(1), "a.jpg");
    expect((await call("POST", "/listings", { token: u.seller.token, form: fd })).status).toBe(403); // BR01
  });

  test("E2E-02 ส่งบัตร+เซลฟี → ผู้ดูแลอนุมัติ → ผู้ขายใช้งานได้", async () => {
    const fd = new FormData();
    fd.set("card", jpeg(1), "card.jpg"); fd.set("selfie", jpeg(2), "selfie.jpg");
    const sub = await call("POST", "/verifications", { token: u.seller.token, form: fd });
    expect(sub.status).toBe(200);
    created.requestIds.push(sub.data.id);
    const nid = withChecksum(`1${Math.floor(Math.random() * 1e11).toString().padStart(11, "0")}`);
    expect((await call("POST", `/admin/verifications/${sub.data.id}/approve`, { token: u.admin.token, json: { nationalId: nid } })).status).toBe(200);
    const me = await call("GET", "/users/me", { token: u.seller.token });
    expect(me.data.isVerified).toBe(true);
  });

  test("E2E-03 ผู้ซื้อ (บัญชีอีกประเภท) เข้าระบบ", async () => {
    const [b] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified) VALUES (${`test-e-${tag}-buyer`}, 'ผู้ซื้อ e2e', true) RETURNING id`;
    u.buyer = { id: b.id, token: await signToken(b.id) };
    expect((await call("GET", "/users/me", { token: u.buyer.token })).status).toBe(200);
  });

  test("E2E-04 ผู้ขายลงขาย → ผู้ซื้อค้นเจอ → กดโปรด", async () => {
    const fd = new FormData();
    fd.set("categoryId", String(leaf)); fd.set("title", `ลำโพงบลูทูธ e2e ${tag}`); fd.set("description", "ใช้น้อย"); fd.set("price", "300"); fd.append("images", jpeg(1), "a.jpg");
    const r = await call("POST", "/listings", { token: u.seller.token, form: fd });
    expect(r.status).toBe(200);
    listingId = r.data.id;
    const found = await call("GET", `/listings?q=${encodeURIComponent(`e2e ${tag}`)}`, { token: u.buyer.token });
    expect(found.data.items.map((i: any) => i.id)).toContain(listingId);
    expect((await call("PUT", `/favorites/${listingId}`, { token: u.buyer.token })).status).toBe(200);
    expect((await call("GET", "/favorites", { token: u.buyer.token })).data.map((i: any) => i.id)).toContain(listingId);
  });

  test("E2E-05 แชต → เสนอซื้อ → ผู้ขายตกลง (ประกาศถูกจอง) → ส่งมอบ → ผู้ซื้อกดได้รับ (ขายแล้ว)", async () => {
    room = (await call("POST", `/listings/${listingId}/conversations`, { token: u.buyer.token })).data.id;
    expect((await call("POST", `/conversations/${room}/messages`, { token: u.buyer.token, json: { body: "ยังอยู่ไหมครับ", clientId: crypto.randomUUID() } })).status).toBe(200);
    dealId = (await call("POST", `/conversations/${room}/deals`, { token: u.buyer.token })).data.id;
    expect((await call("POST", `/deals/${dealId}/accept`, { token: u.seller.token })).status).toBe(200);
    expect((await call("GET", `/listings/${listingId}`, { token: u.buyer.token })).data.status).toBe("RESERVED");
    expect((await call("POST", `/deals/${dealId}/handover`, { token: u.seller.token })).status).toBe(200);
    expect((await call("POST", `/deals/${dealId}/receive`, { token: u.buyer.token })).status).toBe(200);
    expect((await call("GET", `/listings/${listingId}`, { token: u.buyer.token })).data.status).toBe("SOLD");
  });

  test("E2E-06 แจ้งเตือนถึงทั้งสองฝ่ายตามเหตุการณ์ในดีล", async () => {
    const seller = (await call("GET", "/notifications", { token: u.seller.token })).data.items.map((n: any) => n.type);
    const buyer = (await call("GET", "/notifications", { token: u.buyer.token })).data.items.map((n: any) => n.type);
    expect(seller).toContain("DEAL_OFFER");
    expect(seller).toContain("DEAL_COMPLETED");
    expect(buyer).toContain("DEAL_ACCEPTED");
    expect(buyer).toContain("DEAL_HANDED_OVER");
  });

  test("E2E-07 ผู้ซื้อรีวิว → โปรไฟล์ผู้ขายและ Dashboard เปลี่ยนตาม", async () => {
    expect((await call("POST", `/deals/${dealId}/review`, { token: u.buyer.token, json: { rating: 5, comment: "ดีมาก" } })).status).toBe(200);
    const profile = (await call("GET", `/users/${u.seller.id}`, { token: u.buyer.token })).data;
    expect(profile).toMatchObject({ sold: 1, rating: { average: 5, count: 1 } });
    const stats = (await call("GET", "/stats/me", { token: u.seller.token })).data;
    expect(stats.seller).toMatchObject({ sold: 1, soldValue: 300 });
    expect(stats.seller.monthly.at(-1)).toMatchObject({ count: 1, value: 300 });
    expect((await call("GET", "/stats/me", { token: u.buyer.token })).data.buyer).toMatchObject({ bought: 1, spent: 300 });
  });

  test("E2E-08 ผู้ซื้อรายงานผู้ใช้ → เข้าคิวผู้ดูแล → ผู้ดูแลปิด → ผู้รายงานได้แจ้งเตือน", async () => {
    const rep = await call("POST", "/reports", { token: u.buyer.token, json: { targetType: "USER", targetId: u.seller.id, reason: "สแปม" } });
    expect(rep.status).toBe(200);
    const queue = (await call("GET", "/admin/reports", { token: u.admin.token })).data.items;
    expect(queue.map((r: any) => r.id)).toContain(rep.data.id);
    expect((await call("POST", `/admin/reports/${rep.data.id}/close`, { token: u.admin.token, json: { note: "ตรวจแล้ว ไม่พบความผิด" } })).status).toBe(200);
    expect((await call("GET", "/notifications", { token: u.buyer.token })).data.items.map((n: any) => n.type)).toContain("REPORT_CLOSED");
  });

  test("E2E-09 ผู้ซื้อดาวน์โหลดข้อมูลตัวเอง → ลบบัญชี → ใช้ token เดิมไม่ได้ · ดีล/รีวิวของผู้ขายยังอยู่", async () => {
    const exp = await call("GET", "/me/export", { token: u.buyer.token });
    expect(exp.status).toBe(200);
    expect(exp.data.deals).toHaveLength(1);
    expect(exp.data.reviewsGiven).toHaveLength(1);
    expect((await call("DELETE", "/me", { token: u.buyer.token, json: { confirm: true } })).status).toBe(200);
    expect((await call("GET", "/users/me", { token: u.buyer.token })).status).toBe(401);
    expect((await call("GET", `/users/${u.seller.id}`, { token: u.seller.token })).data.rating.count).toBe(1);
  });
});

describe("กรณีผิดพลาด", () => {
  /** เส้นทางที่เปิดให้ทุกคน/ก่อนล็อกอิน — ที่เหลือทุกเส้นต้องปฏิเสธคนไม่มี token */
  const PUBLIC = [/^\/$/, /^\/health$/, /^\/openapi/, /^\/auth\//, /^\/\*$/];
  const sample = (path: string) => path.replace(/:[A-Za-z]+/g, "00000000-0000-0000-0000-000000000000");

  test("ERR-01 ยิงทุกเส้นทางโดยไม่มี token → 401 (หรือ 4xx จากการตรวจรูปแบบ) ห้ามสำเร็จและห้ามล้ม", async () => {
    const routes = (app.routes as any[]).filter((r) => r.method !== "OPTIONS" && r.method !== "HEAD" && !PUBLIC.some((p) => p.test(r.path)));
    expect(routes.length).toBeGreaterThan(50); // กัน false pass ถ้าดึงรายการเส้นทางไม่ได้
    const bad: string[] = [];
    for (const r of routes) {
      if (r.path === "/ws") continue; // WebSocket ต้อง upgrade — ทดสอบแยกใน chat-deals
      const res = await call(r.method, sample(r.path), r.method === "GET" ? {} : { json: {} });
      const ok = res.status === 401 || (res.status >= 400 && res.status < 500 && r.method !== "GET");
      if (!ok) bad.push(`${r.method} ${r.path} → ${res.status}`);
    }
    expect(bad).toEqual([]);
  });

  test("ERR-02 ผู้ใช้ทั่วไปเรียกเส้นทางผู้ดูแลทุกเส้น → 403 (หรือ 4xx จากการตรวจรูปแบบ) ห้ามสำเร็จ", async () => {
    const [x] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified) VALUES (${`test-e-${tag}-normal`}, 'ผู้ใช้ทั่วไป', true) RETURNING id`;
    u.normal = { id: x.id, token: await signToken(x.id) } as U;
    const admin = (app.routes as any[]).filter((r) => r.path.startsWith("/admin/") && r.method !== "OPTIONS");
    expect(admin.length).toBeGreaterThan(15);
    const bad: string[] = [];
    for (const r of admin) {
      const res = await call(r.method, sample(r.path), { token: u.normal.token, ...(r.method === "GET" ? {} : { json: {} }) });
      if (!(res.status === 403 || (res.status >= 400 && res.status < 500 && r.method !== "GET"))) bad.push(`${r.method} ${r.path} → ${res.status}`);
    }
    expect(bad).toEqual([]);
  });

  test("ERR-03 token ปลอม/ลายเซ็นผิด/หมดอายุ/ไม่มี sub → 401", async () => {
    const good = await signToken(u.admin.id);
    const secret = new TextEncoder().encode(env.jwtSecret);
    const expired = await new SignJWT({}).setProtectedHeader({ alg: "HS256" }).setSubject(u.admin.id).setIssuedAt(Math.floor(Date.now() / 1000) - 7200).setExpirationTime(Math.floor(Date.now() / 1000) - 3600).sign(secret);
    const wrongKey = await new SignJWT({}).setProtectedHeader({ alg: "HS256" }).setSubject(u.admin.id).setExpirationTime("1h").sign(new TextEncoder().encode("not-the-real-secret-not-the-real-secret"));
    const noSub = await new SignJWT({}).setProtectedHeader({ alg: "HS256" }).setExpirationTime("1h").sign(secret);
    const tampered = good.slice(0, -4) + (good.endsWith("AAAA") ? "BBBB" : "AAAA");
    for (const [name, t] of Object.entries({ garbage: "abc.def.ghi", expired, wrongKey, noSub, tampered, empty: "" })) {
      const r = await call("GET", "/users/me", { token: t || undefined });
      expect([name, r.status]).toEqual([name, 401]);
    }
    expect((await call("GET", "/users/me", { token: good })).status).toBe(200);
  });

  test("ERR-04 ฟอร์มผิด: ค่าว่าง/ชนิดผิด/ยาวเกิน/ตัวอักษรอันตราย → 4xx ไม่ใช่ 500", async () => {
    const t = u.admin.token;
    const cases: Array<[string, string, Opts]> = [
      ["PATCH", "/users/me", { token: t, json: { displayName: "" } }],
      ["PATCH", "/users/me", { token: t, json: { displayName: "x".repeat(51) } }],
      ["PATCH", "/users/me", { token: t, json: { facultyId: "ไม่ใช่เลข" } }],
      ["POST", "/reports", { token: t, json: { targetType: "LISTING", targetId: "'; DROP TABLE users; --", reason: "สแปม" } }],
      ["POST", "/reports", { token: t, json: { targetType: "NOPE", targetId: "1", reason: "สแปม" } }],
      ["GET", "/listings?minPrice=abc", { token: t }],
      ["GET", "/listings?page=-5&sort=drop", { token: t }],
      ["GET", "/notifications?after=abc", { token: t }],
      ["GET", "/stats/me?months=99", { token: t }],
      ["POST", "/conversations/not-a-uuid/messages", { token: t, json: { body: "x", clientId: "bad" } }],
      ["POST", "/deals/%00/accept", { token: t }],
      ["DELETE", "/me", { token: t, json: { confirm: "yes" } }],
    ];
    for (const [m, p, o] of cases) {
      const r = await call(m, p, o);
      expect([m, p, r.status < 500 && r.status >= 400]).toEqual([m, p, true]);
    }
  });

  test("ERR-05 สมัคร/ล็อกอินผิดรูปแบบหรือรหัสผิด → ข้อความไทยที่แอปแสดงได้ ไม่เปิดเผยว่ามีอีเมลนี้หรือไม่", async () => {
    const wrongPw = await call("POST", "/auth/login", { json: { email: `seller-${tag}@example.test`, password: "wrong-password-1" } });
    const noUser = await call("POST", "/auth/login", { json: { email: `nobody-${tag}@example.test`, password: "wrong-password-1" } });
    expect(wrongPw.status).toBe(401);
    expect(noUser.status).toBe(401);
    expect(wrongPw.data.message).toBe(noUser.data.message); // ข้อความเหมือนกัน กันเดาอีเมล
    expect((await call("POST", "/auth/register", { json: { email: "not-an-email", password: PASSWORD, displayName: "x" } })).status).toBe(422);
    expect((await call("POST", "/auth/register", { json: { email: `short-${tag}@example.test`, password: "123", displayName: "x" } })).status).toBe(422);
  });
});
