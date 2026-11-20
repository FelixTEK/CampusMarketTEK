import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";

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
const users = {} as Record<"admin" | "admin2" | "seller" | "b1" | "b2" | "outsider", U>;
let leaf: number;
const listingIds: string[] = [];

async function makeUser(name: keyof typeof users, admin = false) {
  const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified, is_admin) VALUES (${`test-m-${tag}-${name}`}, ${name}, true, ${admin}) RETURNING id`;
  users[name] = { id: u.id, token: await signToken(u.id) };
}

async function newListing(sellerId = users.seller.id, title = "หนังสือแคลคูลัส") {
  const [l] = await sql`INSERT INTO listings (seller_id, category_id, title, price) VALUES (${sellerId}, ${leaf}, ${title}, 300) RETURNING id`;
  listingIds.push(l.id);
  return l.id as string;
}

/** พาดีลไปถึงสถานะที่ต้องการผ่าน API จริง */
async function deal(buyer: U, listingId: string, upTo: "PENDING" | "ACCEPTED" | "HANDED_OVER" | "COMPLETED") {
  const room = (await call("POST", `/listings/${listingId}/conversations`, buyer.token)).data.id as string;
  const d = (await call("POST", `/conversations/${room}/deals`, buyer.token)).data.id as string;
  const steps: [string, U][] = [["accept", users.seller], ["handover", users.seller], ["receive", buyer]];
  const n = { PENDING: 0, ACCEPTED: 1, HANDED_OVER: 2, COMPLETED: 3 }[upTo];
  for (const [a, who] of steps.slice(0, n)) expect((await call("POST", `/deals/${d}/${a}`, who.token)).status).toBe(200);
  return { room, deal: d };
}

beforeAll(async () => {
  const [r] = await sql`SELECT id FROM categories c WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = c.id) LIMIT 1`;
  leaf = r.id;
  await makeUser("admin", true);
  await makeUser("admin2", true);
  for (const n of ["seller", "b1", "b2", "outsider"] as const) await makeUser(n);
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE kmitl_sub LIKE ${`test-m-${tag}-%`}`;
  const lst = sql`SELECT id FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM reports WHERE reporter_id IN (${mine})`;
  await sql`DELETE FROM reviews WHERE deal_id IN (SELECT id FROM deals WHERE listing_id IN (${lst}))`;
  await sql`DELETE FROM notifications WHERE user_id IN (${mine})`;
  await sql`DELETE FROM messages WHERE conversation_id IN (SELECT id FROM conversations WHERE listing_id IN (${lst}))`;
  await sql`DELETE FROM deals WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM conversations WHERE listing_id IN (${lst})`;
  await sql`DELETE FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM users WHERE id IN (${mine})`;
});

describe("รีวิว (BR12)", () => {
  let early: { room: string; deal: string };
  let done: { room: string; deal: string };

  test("รีวิวก่อนดีลสำเร็จไม่ได้ (409)", async () => {
    early = await deal(users.b1, await newListing(), "HANDED_OVER");
    const r = await call("POST", `/deals/${early.deal}/review`, users.b1.token, { rating: 5 });
    expect(r.status).toBe(409);
  });

  test("ผู้ขายรีวิวตัวเองไม่ได้ (403) · คนนอก 404", async () => {
    done = await deal(users.b1, await newListing(), "COMPLETED");
    expect((await call("POST", `/deals/${done.deal}/review`, users.seller.token, { rating: 5 })).status).toBe(403);
    expect((await call("POST", `/deals/${done.deal}/review`, users.outsider.token, { rating: 5 })).status).toBe(404);
    expect((await call("GET", `/deals/${done.deal}/review`, users.outsider.token)).status).toBe(404);
  });

  test("ดาวต้อง 1–5 · ความเห็นไม่เกิน 300 ตัว", async () => {
    for (const rating of [0, 6, 2.5]) expect((await call("POST", `/deals/${done.deal}/review`, users.b1.token, { rating })).status).toBe(422);
    expect((await call("POST", `/deals/${done.deal}/review`, users.b1.token, { rating: 5, comment: "ก".repeat(301) })).status).toBe(422);
  });

  test("รีวิวได้ครั้งเดียว: ซ้ำ/พร้อมกัน → สำเร็จ 1 ที่เหลือ 409", async () => {
    expect((await call("GET", `/deals/${done.deal}/review`, users.b1.token)).data.review).toBeNull();
    const rs = await Promise.all([1, 2, 3].map(() => call("POST", `/deals/${done.deal}/review`, users.b1.token, { rating: 4, comment: "ส่งของไว" })));
    expect(rs.map((r) => r.status).sort()).toEqual([200, 409, 409]);
    const g = (await call("GET", `/deals/${done.deal}/review`, users.seller.token)).data.review; // ผู้ขายก็ดูได้
    expect(g).toMatchObject({ rating: 4, comment: "ส่งของไว" });
  });
});

describe("ตัวเลขผู้ขาย (ข้อ 2.13)", () => {
  test("ปิดแล้วไม่ถึง 3 ประกาศ → closeRate เป็น null (ผู้ขายใหม่) · คะแนนนับรายผู้ซื้อ", async () => {
    // ตอนนี้ seller มี SOLD 1 (จากดีลข้างบน) + ยังไม่มี CANCELLED
    const p1 = (await call("GET", `/users/${users.seller.id}`, users.b2.token)).data;
    expect(p1).toMatchObject({ sold: 1, closeRate: null, rating: { average: 4, count: 1 } });
    expect(p1.reviews[0]).toMatchObject({ rating: 4, comment: "ส่งของไว", buyerName: "b1" });

    // b1 รีวิวดีลที่ 2 ด้วย 2 ดาว → ผู้ซื้อคนเดียวกัน: เฉลี่ยในคน = 3 · b2 ให้ 5 → เฉลี่ยรวม (3+5)/2 = 4 (ไม่ใช่ (4+2+5)/3)
    const d2 = await deal(users.b1, await newListing(), "COMPLETED");
    await call("POST", `/deals/${d2.deal}/review`, users.b1.token, { rating: 2 });
    const d3 = await deal(users.b2, await newListing(), "COMPLETED");
    await call("POST", `/deals/${d3.deal}/review`, users.b2.token, { rating: 5 });
    const p2 = (await call("GET", `/users/${users.seller.id}`, users.b2.token)).data;
    expect(p2.rating).toEqual({ average: 4, count: 3 }); // count = จำนวนรีวิวทั้งหมด
    expect(p2.sold).toBe(3);
    expect(p2.closeRate).toBe(100); // 3 ปิดแล้ว ขายสำเร็จทั้งหมด
  });

  test("ยกเลิกนับในอัตราปิด", async () => {
    const l = await newListing();
    await call("POST", `/listings/${l}/cancel`, users.seller.token, { reason: "เปลี่ยนใจ" });
    const p = (await call("GET", `/users/${users.seller.id}`, users.b2.token)).data;
    expect(p.closeRate).toBe(75); // 3 ÷ (3+1)
  });
});

describe("รายงาน (BR13)", () => {
  let listing: string;
  beforeAll(async () => { listing = await newListing(users.seller.id, "ของน่าสงสัย"); });

  test("เหตุผลต้องมาจากรายการ · ต้องมีเป้าหมายจริง", async () => {
    const base = { targetType: "LISTING", targetId: listing };
    expect((await call("POST", "/reports", users.b1.token, { ...base, reason: "ไม่ชอบหน้า" })).status).toBe(422);
    expect((await call("POST", "/reports", users.b1.token, { ...base, targetId: crypto.randomUUID(), reason: "สแปม" })).status).toBe(404);
    expect((await call("POST", "/reports", users.b1.token, { ...base, targetId: "xyz", reason: "สแปม" })).status).toBe(404);
  });

  test("รายงานตัวเองไม่ได้ (ประกาศ/ผู้ใช้)", async () => {
    expect((await call("POST", "/reports", users.seller.token, { targetType: "LISTING", targetId: listing, reason: "สแปม" })).status).toBe(403);
    expect((await call("POST", "/reports", users.b1.token, { targetType: "USER", targetId: users.b1.id, reason: "สแปม" })).status).toBe(403);
  });

  test("รายงานประกาศ → เก็บสำเนา · ซ้ำไม่ได้ (409) · คนอื่นรายงานซ้ำเป้าหมายเดิมได้", async () => {
    const r = await call("POST", "/reports", users.b1.token, { targetType: "LISTING", targetId: listing, reason: "หลอกลวง / สงสัยเป็นมิจฉาชีพ", detail: "ขอโอนก่อน" });
    expect(r.status).toBe(200);
    const [row] = await sql`SELECT snapshot, status FROM reports WHERE id = ${r.data.id}`;
    expect(row.status).toBe("OPEN");
    expect(row.snapshot).toMatchObject({ listingId: listing, userId: users.seller.id, title: "ของน่าสงสัย", price: 300 });
    expect((await call("POST", "/reports", users.b1.token, { targetType: "LISTING", targetId: listing, reason: "สแปม" })).status).toBe(409);
    expect((await call("POST", "/reports", users.b2.token, { targetType: "LISTING", targetId: listing, reason: "สแปม" })).status).toBe(200);
  });

  test("รายงานข้อความ: เฉพาะผู้ร่วมห้อง · ข้อความของอีกฝ่าย · สำเนา ≤ 11 ข้อความ (รอบข้าง 5)", async () => {
    const { room } = await deal(users.b1, await newListing(), "PENDING");
    for (let i = 1; i <= 12; i++) {
      await call("POST", `/conversations/${room}/messages`, i % 2 ? users.b1.token : users.seller.token, { body: `ข้อความที่ ${i}`, clientId: crypto.randomUUID() });
    }
    const msgs = (await call("GET", `/conversations/${room}/messages`, users.b1.token)).data.items as any[];
    const target = msgs.find((m) => m.body === "ข้อความที่ 6")!; // ของผู้ขาย (คู่)
    const system = msgs.find((m) => m.senderId === null) ?? null;
    const mine = msgs.find((m) => m.body === "ข้อความที่ 5")!; // ของ b1

    expect((await call("POST", "/reports", users.outsider.token, { targetType: "MESSAGE", targetId: String(target.id), reason: "สแปม" })).status).toBe(404);
    expect((await call("POST", "/reports", users.b1.token, { targetType: "MESSAGE", targetId: String(mine.id), reason: "สแปม" })).status).toBe(403);
    if (system) expect((await call("POST", "/reports", users.b1.token, { targetType: "MESSAGE", targetId: String(system.id), reason: "สแปม" })).status).toBe(422);

    const r = await call("POST", "/reports", users.b1.token, { targetType: "MESSAGE", targetId: String(target.id), reason: "คุกคามหรือใช้ถ้อยคำไม่เหมาะสม" });
    expect(r.status).toBe(200);
    const [row] = await sql`SELECT snapshot FROM reports WHERE id = ${r.data.id}`;
    expect(row.snapshot.userId).toBe(users.seller.id);
    expect(row.snapshot.messages.length).toBeLessThanOrEqual(11);
    expect(row.snapshot.messages.some((m: any) => m.id === target.id)).toBe(true);
  });
});

describe("ผู้ดูแล: รายงาน", () => {
  let reportId: string;
  let listing: string;

  beforeAll(async () => {
    listing = await newListing(users.seller.id, "ของถูกรายงานสำหรับผู้ดูแล");
    reportId = (await call("POST", "/reports", users.b1.token, { targetType: "LISTING", targetId: listing, reason: "สแปม" })).data.id;
  });

  test("ผู้ใช้ทั่วไปเรียก /admin/* → 403 · ไม่ล็อกอิน → 401", async () => {
    for (const [m, p] of [["GET", "/admin/reports"], ["GET", `/admin/reports/${reportId}`], ["POST", `/admin/reports/${reportId}/close`], ["POST", `/admin/users/${users.b2.id}/ban`], ["POST", `/admin/users/${users.b2.id}/unban`]] as const) {
      expect((await call(m, p, users.b1.token, m === "POST" ? { note: "x", reason: "x" } : undefined)).status).toBe(403);
      expect((await call(m, p, undefined, m === "POST" ? { note: "x", reason: "x" } : undefined)).status).toBe(401);
    }
  });

  test("คิวรายงาน: เห็นสำเนา ผู้รายงาน จำนวนรายงานอื่นของเป้าหมายเดียวกัน และคู่ซื้อ–ขายซ้ำ", async () => {
    const list = (await call("GET", "/admin/reports", users.admin.token)).data.items as any[];
    const mine = list.find((r) => r.id === reportId)!;
    expect(mine).toMatchObject({ targetType: "LISTING", status: "OPEN", reason: "สแปม", reporter: { id: users.b1.id } });
    expect(mine.snapshot.title).toBe("ของถูกรายงานสำหรับผู้ดูแล");
    // b1 ปิดดีลกับ seller 2 ครั้งแล้ว (จากชุดรีวิว) → เป็นเบาะแสคู่ซ้ำ
    expect(mine.repeatPairs).toContainEqual({ buyerName: "b1", deals: 2 });
    const detail = await call("GET", `/admin/reports/${reportId}`, users.admin.token);
    expect(detail.data.id).toBe(reportId);
  });

  test("ประกาศที่มีรายงานค้างไม่ถูกปิดดีลอัตโนมัติ (BR11)", async () => {
    const { deal: d } = await deal(users.b2, listing, "HANDED_OVER");
    const { autoCompleteDeals } = await import("../src/chat/deals");
    await autoCompleteDeals(0, Date.now() + 60_000);
    expect((await sql`SELECT status FROM deals WHERE id = ${d}`)[0].status).toBe("HANDED_OVER");
  });

  test("ปิดรายงาน: ต้องมีหมายเหตุ · ผู้รายงานได้แจ้งเตือน · ปิดซ้ำ 409", async () => {
    expect((await call("POST", `/admin/reports/${reportId}/close`, users.admin.token, { note: "   " })).status).toBe(422);
    expect((await call("POST", `/admin/reports/${reportId}/close`, users.admin.token, { note: "ตรวจแล้ว ไม่พบความผิด" })).status).toBe(200);
    expect((await call("POST", `/admin/reports/${reportId}/close`, users.admin2.token, { note: "ซ้ำ" })).status).toBe(409);
    const n = (await call("GET", "/notifications", users.b1.token)).data.items.find((i: any) => i.type === "REPORT_CLOSED");
    expect(n).toBeTruthy();
    const closed = (await call("GET", "/admin/reports?status=CLOSED", users.admin.token)).data.items.find((r: any) => r.id === reportId);
    expect(closed).toMatchObject({ status: "CLOSED", adminNote: "ตรวจแล้ว ไม่พบความผิด" });
    expect((await sql`SELECT resolved_by FROM reports WHERE id = ${reportId}`)[0].resolved_by).toBe(users.admin.id);
  });

  test("ผู้ดูแลตัดสินเรื่องของตัวเองไม่ได้ (เป็นผู้ถูกรายงาน หรือเป็นผู้รายงาน)", async () => {
    const own = await newListing(users.admin.id, "ประกาศของผู้ดูแล");
    const r1 = (await call("POST", "/reports", users.b1.token, { targetType: "LISTING", targetId: own, reason: "สแปม" })).data.id;
    expect((await call("POST", `/admin/reports/${r1}/close`, users.admin.token, { note: "ok" })).status).toBe(403);
    expect((await call("POST", `/admin/reports/${r1}/close`, users.admin2.token, { note: "ok" })).status).toBe(200); // ผู้ดูแลคนอื่นปิดได้

    const r2 = (await call("POST", "/reports", users.admin.token, { targetType: "USER", targetId: users.b2.id, reason: "สแปม" })).data.id;
    expect((await call("POST", `/admin/reports/${r2}/close`, users.admin.token, { note: "ok" })).status).toBe(403);
  });
});

describe("ผู้ดูแล: ระงับ/ปลดระงับ (BR15)", () => {
  test("ระงับตัวเอง/ผู้ดูแลคนอื่น/ผู้ใช้ที่ไม่มี ไม่ได้ · ต้องมีเหตุผล", async () => {
    expect((await call("POST", `/admin/users/${users.admin.id}/ban`, users.admin.token, { reason: "x" })).status).toBe(403);
    expect((await call("POST", `/admin/users/${users.admin2.id}/ban`, users.admin.token, { reason: "x" })).status).toBe(403);
    expect((await call("POST", `/admin/users/${crypto.randomUUID()}/ban`, users.admin.token, { reason: "x" })).status).toBe(404);
    expect((await call("POST", `/admin/users/${users.outsider.id}/ban`, users.admin.token, { reason: " " })).status).toBe(422);
  });

  test("ระงับผู้ซื้อ: ใช้งานไม่ได้ทันที · ดีลค้างถูกยกเลิก ประกาศที่ถูกจองกลับเป็นกำลังขาย ผู้ขายได้แจ้งเตือน", async () => {
    const l = await newListing(users.seller.id, "ของที่ถูกจอง");
    const { deal: d } = await deal(users.b2, l, "ACCEPTED");
    expect((await sql`SELECT status FROM listings WHERE id = ${l}`)[0].status).toBe("RESERVED");

    expect((await call("POST", `/admin/users/${users.b2.id}/ban`, users.admin.token, { reason: "หลอกลวงซ้ำ" })).status).toBe(200);
    expect((await call("GET", "/users/me", users.b2.token)).status).toBe(403); // token เดิมใช้ไม่ได้ทันที
    expect((await call("POST", `/admin/users/${users.b2.id}/ban`, users.admin.token, { reason: "ซ้ำ" })).status).toBe(409);
    expect((await sql`SELECT status FROM deals WHERE id = ${d}`)[0].status).toBe("CANCELLED");
    expect((await sql`SELECT status FROM listings WHERE id = ${l}`)[0].status).toBe("AVAILABLE");
    const n = (await call("GET", "/notifications", users.seller.token)).data.items.find((i: any) => i.type === "DEAL_CANCELLED" && i.refId === d);
    expect(n).toBeTruthy();
  });

  test("ระงับผู้ขาย: ประกาศหายจากการค้นหาโดยไม่เปลี่ยนสถานะ · ปลดแล้วกลับมาเอง", async () => {
    const l = await newListing(users.outsider.id, "สินค้าของคนที่จะโดนระงับ");
    const find = async () => ((await call("GET", `/listings?q=${encodeURIComponent("สินค้าของคนที่จะโดนระงับ")}`, users.b1.token)).data.items as any[]).some((i) => i.id === l);
    expect(await find()).toBe(true);
    await call("POST", `/admin/users/${users.outsider.id}/ban`, users.admin.token, { reason: "ทดสอบ" });
    expect(await find()).toBe(false);
    expect((await sql`SELECT status FROM listings WHERE id = ${l}`)[0].status).toBe("AVAILABLE");
    expect((await call("GET", `/admin/users/${users.outsider.id}`, users.admin.token)).data.isBanned).toBe(true);

    expect((await call("POST", `/admin/users/${users.outsider.id}/unban`, users.admin.token)).status).toBe(200);
    expect((await call("POST", `/admin/users/${users.outsider.id}/unban`, users.admin.token)).status).toBe(409);
    expect(await find()).toBe(true);
    expect((await call("GET", "/users/me", users.outsider.token)).status).toBe(200);
  });
});

describe("ผู้ดูแล: รายชื่อผู้ใช้ · ตั้ง/ถอนผู้ดูแล", () => {
  const mk = async (name: string, f: { verified?: boolean; banned?: boolean } = {}) => {
    const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified, is_banned) VALUES (${`test-m-${tag}-adm-${name}`}, ${`ลิสต์${tag}${name}`}, ${f.verified ?? true}, ${f.banned ?? false}) RETURNING id`;
    return { id: u.id as string, token: await signToken(u.id) };
  };

  test("รายชื่อ: ผู้ใช้ทั่วไปเข้าไม่ได้ · ค้นชื่อ · กรองถูกระงับ/ผู้ดูแล · ไม่ส่งอีเมลออกมา", async () => {
    const a = await mk("a"); const bad = await mk("bad", { banned: true });
    expect((await call("GET", "/admin/users", a.token)).status).toBe(403);

    const found = (await call("GET", `/admin/users?q=${encodeURIComponent(`ลิสต์${tag}`)}`, users.admin.token)).data;
    expect(found.items.map((i: any) => i.id).sort()).toEqual([a.id, bad.id].sort());
    expect(found.items.every((i: any) => !("email" in i))).toBe(true);

    const banned = (await call("GET", `/admin/users?filter=banned&q=${encodeURIComponent(`ลิสต์${tag}`)}`, users.admin.token)).data.items;
    expect(banned.map((i: any) => i.id)).toEqual([bad.id]);
    expect(banned[0].isBanned).toBe(true);

    const admins = (await call("GET", `/admin/users?filter=admin&q=${encodeURIComponent(`${"admin"}`)}`, users.admin.token)).data.items;
    expect(admins.length).toBeGreaterThanOrEqual(2);
    expect(admins.every((i: any) => i.isAdmin)).toBe(true);

    // อักขระพิเศษของ LIKE ถูกมองเป็นตัวอักษรธรรมดา (ไม่ใช่ wildcard)
    expect((await call("GET", `/admin/users?q=${encodeURIComponent("%")}`, users.admin.token)).data.items.length).toBe(0);
    expect((await call("GET", "/admin/users?filter=ไม่มีแบบนี้&q=zzzz", users.admin.token)).status).toBe(200);
  });

  test("ตั้งเป็นผู้ดูแล: มีผลทันที · ซ้ำ 409 · ไม่ยืนยัน/ถูกระงับ 409 · ไม่มีผู้ใช้ 404 · ผู้ใช้ทั่วไปทำไม่ได้ 403", async () => {
    const x = await mk("x"); const unv = await mk("unv", { verified: false }); const bad = await mk("bad2", { banned: true });
    expect((await call("GET", "/admin/reports?status=OPEN", x.token)).status).toBe(403); // ยังไม่ใช่ผู้ดูแล
    expect((await call("POST", `/admin/users/${x.id}/promote`, x.token)).status).toBe(403); // ตั้งตัวเองไม่ได้ ถ้าไม่ใช่ผู้ดูแล

    expect((await call("POST", `/admin/users/${x.id}/promote`, users.admin.token)).status).toBe(200);
    expect((await call("GET", "/admin/reports?status=OPEN", x.token)).status).toBe(200); // ใช้สิทธิ์ได้ทันที ไม่ต้องล็อกอินใหม่
    expect((await call("POST", `/admin/users/${x.id}/promote`, users.admin.token)).status).toBe(409);
    expect((await call("POST", `/admin/users/${unv.id}/promote`, users.admin.token)).status).toBe(409);
    expect((await call("POST", `/admin/users/${bad.id}/promote`, users.admin.token)).status).toBe(409);
    expect((await call("POST", `/admin/users/${crypto.randomUUID()}/promote`, users.admin.token)).status).toBe(404);
    expect((await call("POST", "/admin/users/ไม่ใช่uuid/promote", users.admin.token)).status).toBe(404);
  });

  test("ถอนสิทธิ์: ถอนตัวเองไม่ได้ · ถอนคนอื่นมีผลทันที · ถอนซ้ำ 409 · แล้วระงับได้ตามปกติ", async () => {
    const y = await mk("y");
    await call("POST", `/admin/users/${y.id}/promote`, users.admin.token);
    expect((await call("POST", `/admin/users/${y.id}/ban`, users.admin.token, { reason: "x" })).status).toBe(403); // ผู้ดูแลระงับกันไม่ได้

    expect((await call("POST", `/admin/users/${users.admin.id}/demote`, users.admin.token)).status).toBe(403); // ตัวเอง
    expect((await call("POST", `/admin/users/${y.id}/demote`, users.admin.token)).status).toBe(200);
    expect((await call("GET", "/admin/reports?status=OPEN", y.token)).status).toBe(403); // เสียสิทธิ์ทันที
    expect((await call("POST", `/admin/users/${y.id}/demote`, users.admin.token)).status).toBe(409);
    expect((await call("POST", `/admin/users/${y.id}/ban`, users.admin.token, { reason: "ทดสอบ" })).status).toBe(200);
    expect((await call("POST", `/admin/users/${y.id}/promote`, users.admin.token)).status).toBe(409); // ถูกระงับแล้วตั้งไม่ได้
  });
});
