import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);

async function call(path: string, token?: string) {
  const res = await app.handle(new Request(`http://localhost${path}`, { headers: token ? { authorization: `Bearer ${token}` } : {} }));
  return { status: res.status, data: (res.headers.get("content-type") ?? "").includes("json") ? await res.json() : (null as any) };
}

type U = { id: string; token: string };
const users = {} as Record<"seller" | "buyer" | "admin", U>;
let leaf: number;

const monthKey = (monthsAgo: number) => {
  // เดือนตามเวลาไทย (UTC+7) เหมือนที่ server ใช้
  const d = new Date(Date.now() + 7 * 3_600_000);
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - monthsAgo);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
};

/** ประกาศที่ขายแล้ว + ดีลสำเร็จ ย้อนหลัง monthsAgo เดือน (กลางเดือนกันขอบเขต) */
async function sold(price: number, monthsAgo: number) {
  const [l] = await sql`INSERT INTO listings (seller_id, category_id, title, price, status) VALUES (${users.seller.id}, ${leaf}, 'ของที่ขายแล้ว', ${price}, 'SOLD') RETURNING id`;
  await sql`INSERT INTO deals (listing_id, buyer_id, status, completed_at)
            VALUES (${l.id}, ${users.buyer.id}, 'COMPLETED', date_trunc('month', now()) - make_interval(months => ${monthsAgo}::int) + interval '14 days')`;
}

beforeAll(async () => {
  const [r] = await sql`SELECT id FROM categories c WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = c.id) LIMIT 1`;
  leaf = r.id;
  for (const n of ["seller", "buyer", "admin"] as const) {
    const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified, is_admin) VALUES (${`test-s-${tag}-${n}`}, ${n}, true, ${n === "admin"}) RETURNING id`;
    users[n] = { id: u.id, token: await signToken(u.id) };
  }
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE kmitl_sub LIKE ${`test-s-${tag}-%`}`;
  await sql`DELETE FROM deals WHERE listing_id IN (SELECT id FROM listings WHERE seller_id IN (${mine}))`;
  await sql`DELETE FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM users WHERE id IN (${mine})`;
});

describe("Dashboard ผู้ใช้ (GET /stats/me)", () => {
  test("ต้องล็อกอิน · เดือนต้อง 1–12", async () => {
    expect((await call("/stats/me")).status).toBe(401);
    expect((await call("/stats/me?months=0", users.seller.token)).status).toBe(422);
    expect((await call("/stats/me?months=13", users.seller.token)).status).toBe(422);
  });

  test("ผู้ใช้ใหม่: ทุกอย่างเป็น 0 และมีครบทุกเดือนในช่วง", async () => {
    const s = (await call("/stats/me?months=4", users.seller.token)).data;
    expect(s.seller.sold).toBe(0);
    expect(s.seller.soldValue).toBe(0);
    expect(s.seller.monthly.map((m: any) => m.month)).toEqual([monthKey(3), monthKey(2), monthKey(1), monthKey(0)]);
    expect(s.seller.monthly.every((m: any) => m.count === 0 && m.value === 0)).toBe(true);
    expect(s.seller.byStatus).toMatchObject({ AVAILABLE: 0, SOLD: 0, CANCELLED: 0 });
  });

  test("ยอดขายรายเดือนตรงกับดีลที่สำเร็จ (นับตามเดือนที่ดีลสำเร็จ) และฝั่งผู้ซื้อ", async () => {
    await sold(500, 0);
    await sold(300, 0);
    await sold(1000, 2);
    await sold(200, 5); // นอกช่วง 4 เดือน แต่ยังนับรวมในยอดตลอดกาล
    await sql`INSERT INTO listings (seller_id, category_id, title, price) VALUES (${users.seller.id}, ${leaf}, 'ยังขายอยู่', 50)`;
    await sql`INSERT INTO listings (seller_id, category_id, title, price, status) VALUES (${users.seller.id}, ${leaf}, 'ยกเลิก', 50, 'CANCELLED')`;

    const s = (await call("/stats/me?months=4", users.seller.token)).data;
    const byMonth = Object.fromEntries(s.seller.monthly.map((m: any) => [m.month, m]));
    expect(byMonth[monthKey(0)]).toEqual({ month: monthKey(0), count: 2, value: 800 });
    expect(byMonth[monthKey(1)]).toEqual({ month: monthKey(1), count: 0, value: 0 }); // เดือนที่ไม่มีข้อมูลเป็น 0
    expect(byMonth[monthKey(2)]).toEqual({ month: monthKey(2), count: 1, value: 1000 });
    expect(byMonth[monthKey(5)]).toBeUndefined();
    expect(s.seller).toMatchObject({ sold: 4, soldValue: 2000, closeRate: 80 }); // 4 ÷ (4+1)
    expect(s.seller.byStatus).toMatchObject({ AVAILABLE: 1, SOLD: 4, CANCELLED: 1 });

    const b = (await call("/stats/me?months=4", users.buyer.token)).data;
    expect(b.buyer).toMatchObject({ bought: 4, spent: 2000 });
    expect(b.buyer.monthly.reduce((a: number, m: any) => a + m.count, 0)).toBe(3); // 3 ดีลอยู่ในช่วง 4 เดือน
    expect(b.seller.sold).toBe(0); // ผู้ซื้อไม่ได้ขาย
  });

  test("ดีลที่ยังไม่สำเร็จไม่นับเป็นยอด", async () => {
    const [l] = await sql`INSERT INTO listings (seller_id, category_id, title, price, status) VALUES (${users.seller.id}, ${leaf}, 'ถูกจอง', 9999, 'RESERVED') RETURNING id`;
    await sql`INSERT INTO deals (listing_id, buyer_id, status) VALUES (${l.id}, ${users.buyer.id}, 'HANDED_OVER')`;
    const s = (await call("/stats/me", users.seller.token)).data;
    expect(s.seller.soldValue).toBe(2000);
    expect((await call("/stats/me", users.buyer.token)).data.buyer.spent).toBe(2000);
  });
});

describe("Dashboard ผู้ดูแล (GET /admin/stats)", () => {
  test("ผู้ใช้ทั่วไป 403 · ไม่ล็อกอิน 401", async () => {
    expect((await call("/admin/stats", users.seller.token)).status).toBe(403);
    expect((await call("/admin/stats")).status).toBe(401);
  });

  test("ตัวเลขรวมตรงกับข้อมูลจริงในฐานข้อมูล", async () => {
    const s = (await call("/admin/stats?months=3", users.admin.token)).data;
    const [u] = await sql`SELECT count(*)::int AS n FROM users WHERE deleted_at IS NULL`;
    const [d] = await sql`SELECT count(*)::int AS n FROM deals WHERE status = 'COMPLETED'`;
    const [v] = await sql`SELECT coalesce(sum(l.price), 0)::int AS n FROM deals d JOIN listings l ON l.id = d.listing_id WHERE d.status = 'COMPLETED'`;
    expect(s.users.total).toBe(u.n);
    expect(s.deals.COMPLETED).toBe(d.n);
    expect(s.totalValue).toBe(v.n);
    expect(s.users.kmitl + s.users.outsiders).toBe(s.users.total);
    expect(s.monthly).toHaveLength(3);
    expect(s.newUsers).toHaveLength(3);
    expect(s.monthly.find((m: any) => m.month === monthKey(0)).count).toBeGreaterThanOrEqual(2); // ดีลของเทสต์นี้
    expect(s.newUsers.find((m: any) => m.month === monthKey(0)).count).toBeGreaterThanOrEqual(3);
    expect(Object.keys(s.listings).sort()).toEqual(["AVAILABLE", "CANCELLED", "RESERVED", "RETURNED", "SOLD", "TAKEN_DOWN"]);
  });
});
