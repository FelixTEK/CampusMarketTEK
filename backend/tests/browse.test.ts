import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { validateListing } from "../src/listings/validate";
import { buildTsQuery } from "../src/routes/browse";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูลที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);
const sub = (n: string) => `test-b-${tag}-${n}`;
type Json = Record<string, any>;

async function call(method: string, path: string, token?: string) {
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers: token ? { authorization: `Bearer ${token}` } : {} }));
  const isJson = (res.headers.get("content-type") ?? "").includes("json");
  return { status: res.status, data: (isJson ? await res.json() : null) as any };
}

const users = {} as Record<"seller" | "buyer" | "banned", { id: string; token: string }>;
let bookLeaf: number, itLeaf: number, penLeaf: number, bookRoot: number, itRoot: number;
let fac1: number, fac5: number;
const tagId = {} as Record<string, number>;
const ids: Record<string, string> = {}; // ชื่อสั้น -> listing id

async function leafOf(rootPrefix: string) {
  const [r] = await sql`
    WITH RECURSIVE t AS (
      SELECT id, id AS root FROM categories WHERE parent_id IS NULL AND name_th LIKE ${`${rootPrefix}%`}
      UNION ALL SELECT c.id, t.root FROM categories c JOIN t ON c.parent_id = t.id)
    SELECT id, root FROM t WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = t.id) AND id <> root LIMIT 1`;
  return r as { id: number; root: number };
}

/** สร้างประกาศด้วยเส้นทางเดียวกับ server (validateListing สร้าง search_text ที่ตัดคำแล้ว) */
async function seed(key: string, sellerId: string, o: { cat: number; title: string; description?: string; price: number; faculties?: number[]; tags?: string[]; status?: string }) {
  const data = await validateListing(sql, {
    categoryId: o.cat, title: o.title, description: o.description ?? "", price: o.price,
    facultyIds: o.faculties ?? [], tagIds: (o.tags ?? []).map((n) => tagId[n]!),
  });
  const [row] = await sql`
    INSERT INTO listings (seller_id, category_id, title, description, price, status, status_reason, search_text)
    VALUES (${sellerId}, ${data.categoryId}, ${data.title}, ${data.description}, ${data.price}, ${o.status ?? "AVAILABLE"},
            ${o.status === "RETURNED" || o.status === "TAKEN_DOWN" ? "x" : null}, ${data.searchText}) RETURNING id`;
  for (const f of data.facultyIds) await sql`INSERT INTO listing_faculties (listing_id, faculty_id) VALUES (${row.id}, ${f})`;
  for (const t of data.tagIds) await sql`INSERT INTO listing_tags (listing_id, tag_id) VALUES (${row.id}, ${t})`;
  ids[key] = row.id;
  return row.id as string;
}

async function makeUser(name: keyof typeof users, banned = false) {
  const [u] = await sql`INSERT INTO users (kmitl_sub, display_name, is_verified, is_banned) VALUES (${sub(name)}, ${name}, true, ${banned}) RETURNING id`;
  users[name] = { id: u.id, token: await signToken(u.id) };
}

const search = async (q: string, extra = "") =>
  (await call("GET", `/listings?q=${encodeURIComponent(q)}${extra}`, users.buyer.token)).data.items as Json[];
const titles = (items: Json[]) => items.map((i) => i.title as string);

beforeAll(async () => {
  const b = await leafOf("หนังสือ");
  const it = await leafOf("อุปกรณ์คอมพิวเตอร์");
  const pen = await leafOf("เครื่องเขียน");
  bookLeaf = b.id; bookRoot = b.root; itLeaf = it.id; itRoot = it.root; penLeaf = pen.id;
  const f = await sql`SELECT id FROM faculties ORDER BY id`;
  fac1 = f[0].id; fac5 = f[4].id;
  for (const r of await sql`SELECT id, name_th FROM tags`) tagId[r.name_th] = r.id;

  await makeUser("seller"); await makeUser("buyer"); await makeUser("banned", true);
  const s = users.seller.id;
  const L = (key: string, o: Parameters<typeof seed>[2]) => seed(key, s, o);

  await L("calc", { cat: bookLeaf, title: "แคลคูลัส 1 (Thomas) ฉบับที่ 14", description: "มีไฮไลต์เล็กน้อย รหัสวิชา 01006xxx", price: 250, faculties: [fac1, fac5], tags: ["มีเฉลย"] });
  await L("legion", { cat: itLeaf, title: "Lenovo Legion 5 Pro Gaming", description: "Ryzen 7 RAM 16GB SSD 512GB RTX 3060 โน้ตบุ๊คเกมมิ่ง", price: 27900, tags: ["ใช้เล่นเกม", "ประกันศูนย์"] });
  await L("g102", { cat: itLeaf, title: "Logitech G102 เมาส์เกมมิ่ง มีกล่อง", description: "8000 DPI สาย USB", price: 290, tags: ["มีกล่อง"] });
  await L("phys", { cat: bookLeaf, title: "หนังสือฟิสิกส์ ปี 1 เล่ม 1", description: "ฟิสิกส์ทั่วไป", price: 180, faculties: [fac1] });
  await L("pilot", { cat: penLeaf, title: "ชุดปากกา Pilot + ไส้ 10 ด้าม", price: 120, tags: ["ครบชุด"] });
  await L("monitor", { cat: itLeaf, title: "จอ 24 นิ้ว IPS 75Hz", price: 1900 });
  await L("iphone", { cat: itLeaf, title: "iPhone 13 128GB สีดำ", price: 4900 });
  await L("chair", { cat: itLeaf, title: "เก้าอี้เกมมิ่ง ปรับเอนได้", price: 1500 });
  await L("chem", { cat: bookLeaf, title: "ตำราเคมีอินทรีย์ Organic Chemistry", price: 350, faculties: [fac5] });
  await L("keys", { cat: itLeaf, title: "คีย์บอร์ด Mechanical Keychron K2", price: 2400 });
  // ไม่ควรโผล่ในผลค้นหา
  await L("cancelled", { cat: itLeaf, title: "ของที่ยกเลิก แคลคูลัส", price: 10, status: "CANCELLED" });
  await L("returned", { cat: itLeaf, title: "ของที่ถูกตีกลับ แคลคูลัส", price: 10, status: "RETURNED" });
  await seed("banned", users.banned.id, { cat: itLeaf, title: "แคลคูลัส ของคนถูกระงับ", price: 10 });
  await L("sold", { cat: itLeaf, title: "ของที่ขายแล้ว แคลคูลัส", price: 10, status: "SOLD" });
  await L("reserved", { cat: itLeaf, title: "ของที่ถูกจอง ลำโพงบลูทูธ", price: 700, status: "RESERVED" });
  for (let i = 1; i <= 25; i++) await L(`bic${i}`, { cat: penLeaf, title: `ปากกาลูกลื่น เบอร์ ${i}`, price: 10 + i });
});

afterAll(async () => {
  const mine = sql`SELECT id FROM users WHERE kmitl_sub LIKE ${`test-b-${tag}-%`}`;
  await sql`DELETE FROM favorites WHERE user_id IN (${mine})`;
  await sql`DELETE FROM listings WHERE seller_id IN (${mine})`;
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`test-b-${tag}-%`}`;
});

describe("คำค้น → tsquery", () => {
  test("ตัดคำ แล้วจับแบบขึ้นต้นด้วย (:*) ทุกคำ", () => {
    expect(buildTsQuery("g102")).toBe("g102:*");
    expect(buildTsQuery("iPhone 13")).toBe("iphone:* & 13:*");
    expect(buildTsQuery("หนังสือฟิสิกส์ปี 1")!.split(" & ").length).toBeGreaterThan(2);
  });
  test("ตัวอักษรพิเศษของ tsquery ถูกกรอง — ไม่มีอะไรเหลือก็ได้ null", () => {
    expect(buildTsQuery("a & b | !c (d) 'e'")).toBe("a:* & b:* & c:* & d:* & e:*");
    expect(buildTsQuery("&|!():*'")).toBeNull();
    expect(buildTsQuery("   ")).toBeNull();
  });
});

describe("ชุดคำค้นทดสอบ (ภาษาไทย/อังกฤษ/ตัวเลข/พิมพ์ตกหล่น)", () => {
  // [คำค้น, ชื่อสั้นของประกาศที่ต้องเจอ]
  // พารามิเตอร์เพิ่มเติมเฉพาะบางคำค้น (เรียงราคาให้ชุด Pilot ซึ่งแพงสุดขึ้นก่อน)
  const EXTRA: Record<string, string> = { ปากกา: "&sort=price_desc" };
  const MUST: Array<[string, string[]]> = [
    ["แคล", ["calc"]],
    ["แคลคูลัส", ["calc"]],
    ["แคลคูลัส 1", ["calc"]],
    ["01006xxx", ["calc"]],                         // รหัสวิชาในรายละเอียด
    ["โน้ตบุ๊ค เกม", ["legion"]],                    // คำไทยหลายคำ ในรายละเอียด
    ["g102", ["g102"]],
    ["G102", ["g102"]],                             // ตัวพิมพ์ใหญ่
    ["logitech", ["g102"]],
    ["เมาส์", ["g102"]],
    ["เมาส์เกมมิ่ง", ["g102"]],
    ["หนังสือฟิสิกส์ปี 1", ["phys"]],               // ไม่เว้นวรรค ต่างจากชื่อที่ลง
    ["ฟิสิกส์", ["phys"]],
    ["ฟิสิกส", ["phys"]],                           // พิมพ์ตกการันต์
    ["ปากกา", ["pilot"]],                           // (ดู EXTRA: ปากกาลูกลื่น 25 อันสำหรับทดสอบแบ่งหน้าก็ตรงด้วย)
    ["pilot", ["pilot"]],
    ["iphone", ["iphone"]],
    ["iphone 13", ["iphone"]],
    ["128gb", ["iphone"]],
    ["จอ 24", ["monitor"]],
    ["ips", ["monitor"]],
    ["75hz", ["monitor"]],
    ["เก้าอี้", ["chair"]],
    ["เกม", ["legion", "g102", "chair"]],           // เกมมิ่ง/เล่นเกม ทั้งชื่อ รายละเอียด แท็ก
    ["เกมมิ่ง", ["g102", "chair", "legion"]],
    ["คีย์บอร์ด", ["keys"]],
    ["keychron k2", ["keys"]],
    ["เคมี", ["chem"]],
    ["organic", ["chem"]],
    ["rtx 3060", ["legion"]],
    ["ryzen 7", ["legion"]],
    ["หนังสือ", ["calc", "phys", "chem"]],           // ชื่อหมวดถูกรวมใน search_text
    ["ประกันศูนย์", ["legion"]],                     // ชื่อแท็ก
    ["ลำโพง", ["reserved"]],                        // ประกาศ "ถูกจอง" ยังค้นเจอ (BR16)
  ];

  test.each(MUST)("ค้น %p ต้องเจอ %p", async (q, expected) => {
    const found = titles(await search(q, EXTRA[q] ?? ""));
    for (const key of expected) {
      const [row] = await sql`SELECT title FROM listings WHERE id = ${ids[key]!}`;
      expect(found).toContain(row.title);
    }
  });

  test("ค้นเจอเฉพาะของที่เกี่ยวข้อง ไม่ลากทุกอย่างมา", async () => {
    const found = titles(await search("g102"));
    expect(found).toHaveLength(1);
    expect(titles(await search("เกม"))).not.toContain("ชุดปากกา Pilot + ไส้ 10 ด้าม");
  });

  test("ประกาศที่ยกเลิก/ตีกลับ/ผู้ขายถูกระงับ ไม่โผล่ · ขายแล้วไม่โผล่ในผลค้นหา (BR15, BR16)", async () => {
    const found = titles(await search("แคลคูลัส"));
    expect(found).toEqual(["แคลคูลัส 1 (Thomas) ฉบับที่ 14"]);
  });

  test("คำค้นที่ไม่มีของ / ตัวอักษรอันตราย → ว่าง ไม่ error ไม่ SQL injection", async () => {
    for (const q of ["ไม่มีของนี้แน่นอน", "100%", "a_b", "'; DROP TABLE listings; --", "&|!()", "\\", "%"]) {
      const r = await call("GET", `/listings?q=${encodeURIComponent(q)}`, users.buyer.token);
      expect(r.status).toBe(200);
      expect(r.data.items).toHaveLength(0);
    }
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM listings WHERE seller_id = ${users.seller.id}`;
    expect(n).toBeGreaterThan(30); // ตารางยังอยู่ครบ
  });

  test("คำค้นว่าง/ช่องว่าง = ไม่กรอง ได้รายการล่าสุด", async () => {
    const r = await call("GET", `/listings?q=%20%20`, users.buyer.token);
    expect(r.data.items.length).toBe(20);
  });
});

describe("ตัวกรอง การเรียง และแบ่งหน้า", () => {
  const list = async (qs: string) => (await call("GET", `/listings?${qs}`, users.buyer.token)).data;

  test("หมวดหลักรวมหมวดย่อยทั้งหมด · หมวดใบสุดท้ายเจาะจง", async () => {
    const books = titles((await list(`category=${bookRoot}`)).items);
    expect(books).toEqual(expect.arrayContaining(["แคลคูลัส 1 (Thomas) ฉบับที่ 14", "หนังสือฟิสิกส์ ปี 1 เล่ม 1", "ตำราเคมีอินทรีย์ Organic Chemistry"]));
    expect(books).not.toContain("Logitech G102 เมาส์เกมมิ่ง มีกล่อง");
    expect(titles((await list(`category=${itRoot}`)).items)).toContain("Lenovo Legion 5 Pro Gaming");
    expect(titles((await list(`category=${penLeaf}&q=pilot`)).items)).toHaveLength(1);
  });

  test("คณะ: หนังสือที่ระบุคณะนั้น", async () => {
    const f1 = titles((await list(`faculty=${fac1}`)).items);
    expect(f1.sort()).toEqual(["หนังสือฟิสิกส์ ปี 1 เล่ม 1", "แคลคูลัส 1 (Thomas) ฉบับที่ 14"].sort());
    expect(titles((await list(`faculty=${fac5}`)).items)).toContain("ตำราเคมีอินทรีย์ Organic Chemistry");
  });

  test("แท็กต้องมีครบทุกตัวที่เลือก (AND)", async () => {
    expect(titles((await list(`tags=${tagId["ใช้เล่นเกม"]}`)).items)).toEqual(["Lenovo Legion 5 Pro Gaming"]);
    expect(titles((await list(`tags=${tagId["ใช้เล่นเกม"]},${tagId["ประกันศูนย์"]}`)).items)).toEqual(["Lenovo Legion 5 Pro Gaming"]);
    expect((await list(`tags=${tagId["ใช้เล่นเกม"]},${tagId["มีกล่อง"]}`)).items).toHaveLength(0);
  });

  test("ช่วงราคา และเรียงราคา", async () => {
    const mid = (await list(`minPrice=1000&maxPrice=3000&category=${itRoot}`)).items as Json[];
    expect(mid.every((i) => i.price >= 1000 && i.price <= 3000)).toBe(true);
    expect(mid.map((i) => i.price).sort((a, b) => a - b)).toEqual([1500, 1900, 2400]); // เก้าอี้ · จอ · คีย์บอร์ด (ลำโพง 700 และเมาส์ 290 อยู่นอกช่วง)
  });

  test("เรียงราคาน้อย→มาก และมาก→น้อย", async () => {
    const asc = ((await list(`category=${itRoot}&sort=price_asc`)).items as Json[]).map((i) => i.price);
    expect(asc).toEqual([...asc].sort((a, b) => a - b));
    const desc = ((await list(`category=${itRoot}&sort=price_desc`)).items as Json[]).map((i) => i.price);
    expect(desc).toEqual([...desc].sort((a, b) => b - a));
    expect(desc[0]).toBe(27900);
  });

  test("ค่าผิด → 422", async () => {
    expect((await call("GET", "/listings?minPrice=abc", users.buyer.token)).status).toBe(422);
    expect((await call("GET", "/listings?minPrice=500&maxPrice=100", users.buyer.token)).status).toBe(422);
    expect((await call("GET", "/listings?sort=random", users.buyer.token)).status).toBe(422);
    expect((await call("GET", "/listings?tags=1,x", users.buyer.token)).status).toBe(422);
  });

  test("แบ่งหน้า 20 ต่อหน้า: หน้า 1 มี hasMore หน้าสุดท้ายไม่มี · ไม่ซ้ำกัน", async () => {
    const p1 = await list(`q=${encodeURIComponent("ปากกาลูกลื่น")}&page=1`);
    const p2 = await list(`q=${encodeURIComponent("ปากกาลูกลื่น")}&page=2`);
    expect(p1.items).toHaveLength(20);
    expect(p1.hasMore).toBe(true);
    expect(p2.items).toHaveLength(5);
    expect(p2.hasMore).toBe(false);
    const all = [...p1.items, ...p2.items].map((i: Json) => i.id);
    expect(new Set(all).size).toBe(25);
  });

  test("/listings/count ตรงกับจำนวนจริงของตัวกรองเดียวกัน", async () => {
    const q = `category=${penLeaf}&q=${encodeURIComponent("ปากกาลูกลื่น")}&maxPrice=20`;
    const count = (await call("GET", `/listings/count?${q}`, users.buyer.token)).data.count;
    const items = (await list(q)).items;
    expect(count).toBe(items.length);
    expect(count).toBe(10); // ราคา 11..20
  });

  test("การ์ดมีข้อมูลที่หน้าแรกต้องใช้ · ไม่มี token → 401", async () => {
    const item = (await list(`q=g102`)).items[0] as Json;
    expect(Object.keys(item).sort()).toEqual(["categoryId", "cover", "createdAt", "id", "isFavorite", "price", "sellerFaculty", "sellerName", "status", "title"]);
    expect(item.sellerName).toBe("seller");
    expect((await call("GET", "/listings")).status).toBe(401);
    expect((await call("GET", "/listings/count")).status).toBe(401);
  });
});

describe("รายการโปรด", () => {
  test("PUT ซ้ำได้ผลเท่าเดิม · isFavorite ขึ้นในผลค้นหา · DELETE ซ้ำได้", async () => {
    const id = ids.g102!;
    const t = users.buyer.token;
    expect((await call("PUT", `/favorites/${id}`, t)).data).toEqual({ favorite: true });
    expect((await call("PUT", `/favorites/${id}`, t)).status).toBe(200);
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM favorites WHERE user_id = ${users.buyer.id} AND listing_id = ${id}`;
    expect(n).toBe(1);
    expect(((await search("g102"))[0] as Json).isFavorite).toBe(true);
    expect(((await call("GET", "/listings?q=g102", users.seller.token)).data.items[0] as Json).isFavorite).toBe(false); // ของคนอื่นไม่เกี่ยว

    expect((await call("DELETE", `/favorites/${id}`, t)).data).toEqual({ favorite: false });
    expect((await call("DELETE", `/favorites/${id}`, t)).status).toBe(200);
    expect(((await search("g102"))[0] as Json).isFavorite).toBe(false);
  });

  test("รายการโปรด: ขายแล้วยังเห็น (BR16) · ยกเลิก/ตีกลับ/ผู้ขายถูกระงับ ไม่เห็น · ใหม่สุดก่อน", async () => {
    const t = users.buyer.token;
    for (const key of ["g102", "sold", "cancelled", "banned", "returned"]) await sql`INSERT INTO favorites (user_id, listing_id) VALUES (${users.buyer.id}, ${ids[key]!}) ON CONFLICT DO NOTHING`;
    const favs = (await call("GET", "/favorites", t)).data as Json[];
    expect(titles(favs).sort()).toEqual(["Logitech G102 เมาส์เกมมิ่ง มีกล่อง", "ของที่ขายแล้ว แคลคูลัส"].sort());
    expect(favs.every((f) => f.isFavorite)).toBe(true);
    expect(favs.find((f) => f.status === "SOLD")).toBeDefined();
  });

  test("เพิ่มโปรดกับประกาศที่ไม่มี/ถูกซ่อน → 404 · id มั่ว → 404 · ไม่มี token → 401", async () => {
    const t = users.buyer.token;
    expect((await call("PUT", `/favorites/${ids.cancelled}`, t)).status).toBe(404);
    expect((await call("PUT", `/favorites/${ids.banned}`, t)).status).toBe(404);
    expect((await call("PUT", `/favorites/${crypto.randomUUID()}`, t)).status).toBe(404);
    expect((await call("PUT", "/favorites/not-a-uuid", t)).status).toBe(404);
    expect((await call("PUT", `/favorites/${ids.g102}`)).status).toBe(401);
    expect((await call("GET", "/favorites")).status).toBe(401);
  });
});
