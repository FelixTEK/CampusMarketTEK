import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { removeListingImages } from "../src/listings/storage";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ข้อมูล/ไฟล์ที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);
type Json = Record<string, any>;

async function call(method: string, path: string, opts: { token?: string; json?: unknown; form?: FormData } = {}) {
  const headers: Record<string, string> = {};
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  let body: RequestInit["body"];
  if (opts.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.form) body = opts.form;
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body }));
  const isJson = (res.headers.get("content-type") ?? "").includes("json");
  return { res, status: res.status, data: (isJson ? await res.json() : null) as any };
}

const jpeg = (n: number) => new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, n, 1, 2, 3])], { type: "image/jpeg" });

type Fields = { categoryId: number; title?: string; description?: string; price?: string; facultyIds?: string; tagIds?: string; images?: number };
function form(f: Fields) {
  const fd = new FormData();
  fd.set("categoryId", String(f.categoryId));
  fd.set("title", f.title ?? "แคลคูลัส 1 ฉบับที่ 14");
  if (f.description !== undefined) fd.set("description", f.description);
  fd.set("price", f.price ?? "250");
  if (f.facultyIds) fd.set("facultyIds", f.facultyIds);
  if (f.tagIds) fd.set("tagIds", f.tagIds);
  for (let i = 0; i < (f.images ?? 1); i++) fd.append("images", jpeg(i + 1), `p${i}.jpg`);
  return fd;
}

let bookLeaf: number, bookParent: number, itLeaf: number, faculty1: number, faculty2: number;
let bookTag: number, itTag: number, anyTag: number;
const users = {} as Record<"seller" | "other" | "admin" | "banned", { id: string; token: string }>;
const createdIds: string[] = [];

async function makeUser(name: keyof typeof users, flags: { admin?: boolean; banned?: boolean } = {}) {
  const [u] = await sql`
    INSERT INTO users (kmitl_sub, display_name, is_verified, is_admin, is_banned)
    VALUES (${`test-l-${tag}-${name}`}, ${name}, true, ${flags.admin ?? false}, ${flags.banned ?? false}) RETURNING id`;
  users[name] = { id: u.id, token: await signToken(u.id) };
}

async function post(token: string, f: Fields) {
  const r = await call("POST", "/listings", { token, form: form(f) });
  if (r.status === 200) createdIds.push(r.data.id);
  return r;
}

beforeAll(async () => {
  // หมวดใบสุดท้ายของหนังสือ / ของอุปกรณ์คอมพิวเตอร์ จากข้อมูลจริงในฐานข้อมูล
  const leaf = async (rootName: string) => {
    const [r] = await sql`
      WITH RECURSIVE t AS (
        SELECT id, parent_id, id AS root FROM categories WHERE parent_id IS NULL AND name_th LIKE ${`${rootName}%`}
        UNION ALL SELECT c.id, c.parent_id, t.root FROM categories c JOIN t ON c.parent_id = t.id)
      SELECT id, parent_id FROM t WHERE NOT EXISTS (SELECT 1 FROM categories x WHERE x.parent_id = t.id) AND parent_id IS NOT NULL LIMIT 1`;
    return r as { id: number; parent_id: number };
  };
  const b = await leaf("หนังสือ");
  bookLeaf = b.id;
  bookParent = b.parent_id;
  itLeaf = (await leaf("อุปกรณ์คอมพิวเตอร์")).id;
  const f = await sql`SELECT id FROM faculties ORDER BY id LIMIT 2`;
  faculty1 = f[0].id;
  faculty2 = f[1].id;
  bookTag = (await sql`SELECT id FROM tags WHERE category_id = (SELECT id FROM categories WHERE name_th LIKE 'หนังสือ%' AND parent_id IS NULL) LIMIT 1`)[0].id;
  itTag = (await sql`SELECT id FROM tags WHERE category_id = (SELECT id FROM categories WHERE name_th LIKE 'อุปกรณ์%' AND parent_id IS NULL) LIMIT 1`)[0].id;
  anyTag = (await sql`SELECT id FROM tags WHERE category_id IS NULL LIMIT 1`)[0].id;

  await makeUser("seller");
  await makeUser("other");
  await makeUser("admin", { admin: true });
  await makeUser("banned", { banned: true });
});

afterAll(async () => {
  for (const id of createdIds) await removeListingImages(id);
  await sql`DELETE FROM notifications WHERE user_id IN (SELECT id FROM users WHERE kmitl_sub LIKE ${`test-l-${tag}-%`})`;
  await sql`DELETE FROM deals WHERE listing_id IN (SELECT id FROM listings WHERE seller_id IN (SELECT id FROM users WHERE kmitl_sub LIKE ${`test-l-${tag}-%`}))`;
  await sql`DELETE FROM listings WHERE seller_id IN (SELECT id FROM users WHERE kmitl_sub LIKE ${`test-l-${tag}-%`})`;
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`test-l-${tag}-%`}`;
});

describe("ลงประกาศ (BR04)", () => {
  test("หนังสือ: สร้างสำเร็จ ขึ้นกำลังขายทันที มีรูป คณะ แท็ก และ search_text ตัดคำแล้ว", async () => {
    const r = await post(users.seller.token, { categoryId: bookLeaf, description: "มีไฮไลต์เล็กน้อย", facultyIds: `${faculty1},${faculty2}`, tagIds: `${bookTag},${anyTag}`, images: 2 });
    expect(r.status).toBe(200);
    expect(r.data.status).toBe("AVAILABLE");

    const d = (await call("GET", `/listings/${r.data.id}`, { token: users.seller.token })).data;
    expect(d).toMatchObject({ title: "แคลคูลัส 1 ฉบับที่ 14", price: 250, status: "AVAILABLE", isOwner: true });
    expect(d.images).toHaveLength(2);
    expect(d.faculties).toHaveLength(2);
    expect(d.tags).toHaveLength(2);
    expect(d.categoryPath.length).toBeGreaterThanOrEqual(2);
    expect(d.categoryPath.at(-1).id).toBe(bookLeaf);

    const [row] = await sql`SELECT search_text FROM listings WHERE id = ${r.data.id}`;
    expect(row.search_text).toContain("หนังสือ");   // ชื่อหมวดหลักถูกรวมไว้ให้ค้นเจอ
    expect(row.search_text).toContain("ไฮไลต์");
  });

  test("หมวดอื่น (ไม่ใช่หนังสือ) ไม่ต้องมีคณะ และคณะที่ส่งมาถูกทิ้ง", async () => {
    const r = await post(users.seller.token, { categoryId: itLeaf, title: "Logitech G102", price: "290", facultyIds: String(faculty1), tagIds: String(itTag) });
    expect(r.status).toBe(200);
    expect((await call("GET", `/listings/${r.data.id}`, { token: users.seller.token })).data.faculties).toHaveLength(0);
  });

  test.each([
    ["ชื่อสั้นไป", { title: "ab" }],
    ["ชื่อยาวเกิน 100", { title: "x".repeat(101) }],
    ["ราคา 0", { price: "0" }],
    ["ราคาติดลบ", { price: "-5" }],
    ["ราคาทศนิยม", { price: "10.5" }],
    ["รายละเอียดเกิน 2000", { description: "x".repeat(2001) }],
    ["หมวดที่ไม่ใช่ใบสุดท้าย", { categoryId: -1 }],
    ["หนังสือไม่มีคณะ", { facultyIds: "" }],
    ["แท็กเกิน 5", { facultyIds: String(faculty1), tagIds: "1,2,3,4,5,6" }],
    ["แท็กของหมวดอื่น", { facultyIds: String(faculty1), tagIds: String(itTag) }],
    ["คณะที่ไม่มี", { facultyIds: "9999" }],
  ] as Array<[string, Partial<Fields>]>)("%s → 422", async (_n, patch) => {
    const cat = patch.categoryId === -1 ? bookParent : bookLeaf; // bookParent = หมวดกลาง ไม่ใช่ใบสุดท้าย
    const r = await post(users.seller.token, { categoryId: cat, facultyIds: String(faculty1), ...patch });
    expect(r.status).toBe(422);
    expect(r.data.code).toBe("VALIDATION");
  });

  test("ไม่มีรูป / รูปเกิน 5 / ไฟล์ไม่ใช่รูป → 422", async () => {
    expect((await post(users.seller.token, { categoryId: itLeaf, images: 0 })).status).toBe(422);
    expect((await post(users.seller.token, { categoryId: itLeaf, images: 6 })).status).toBe(422);
    const fd = form({ categoryId: itLeaf, images: 0 });
    fd.append("images", new Blob(["<html>"], { type: "image/jpeg" }), "x.jpg");
    expect((await call("POST", "/listings", { token: users.seller.token, form: fd })).status).toBe(422);
  });

  test("ยังไม่ยืนยันตัวตน/ไม่มี token → 403/401", async () => {
    expect((await call("POST", "/listings", { form: form({ categoryId: itLeaf }) })).status).toBe(401);
    const [u] = await sql`INSERT INTO users (kmitl_sub, display_name) VALUES (${`test-l-${tag}-unv`}, 'unv') RETURNING id`;
    const r = await call("POST", "/listings", { token: await signToken(u.id), form: form({ categoryId: itLeaf }) });
    expect(r.status).toBe(403);
  });
});

describe("การมองเห็น (BR15, BR16) และรูปภาพ", () => {
  let id: string;
  test("ผู้อื่นเห็นประกาศกำลังขายและโหลดรูปได้ แต่ไม่มี token โหลดรูปไม่ได้", async () => {
    id = (await post(users.seller.token, { categoryId: itLeaf, title: "เมาส์ไร้สาย" })).data.id;
    const d = (await call("GET", `/listings/${id}`, { token: users.other.token })).data;
    expect(d.isOwner).toBe(false);
    expect(d.statusReason).toBeNull();
    expect(d.seller.displayName).toBe("seller");
    const img = await call("GET", d.images[0], { token: users.other.token });
    expect(img.status).toBe(200);
    expect(img.res.headers.get("content-type")).toBe("image/jpeg");
    expect((await call("GET", d.images[0])).status).toBe(401);
  });

  test("ยกเลิก/ตีกลับ/ถอดแล้ว คนอื่นเปิดไม่ได้ (404) เจ้าของและผู้ดูแลยังเห็น", async () => {
    const hidden = (await post(users.seller.token, { categoryId: itLeaf, title: "ของที่จะยกเลิก" })).data.id;
    await call("POST", `/listings/${hidden}/cancel`, { token: users.seller.token, json: { reason: "เปลี่ยนใจ" } });
    expect((await call("GET", `/listings/${hidden}`, { token: users.other.token })).status).toBe(404);
    const img = `/uploads/listings/${hidden}/0.jpg`;
    expect((await call("GET", img, { token: users.other.token })).status).toBe(404);
    expect((await call("GET", `/listings/${hidden}`, { token: users.seller.token })).data.statusReason).toBe("เปลี่ยนใจ");
    expect((await call("GET", `/listings/${hidden}`, { token: users.admin.token })).status).toBe(200);
  });

  test("ผู้ขายถูกระงับ → ประกาศถูกซ่อนจากคนอื่น (ไม่เปลี่ยนสถานะ) (BR15)", async () => {
    const lid = (await post(users.banned.token, { categoryId: itLeaf, title: "ของผู้ถูกระงับ" }))
    // ผู้ใช้ที่ถูกระงับลงประกาศไม่ได้ (403) — จำลองโดยแทรกประกาศตรง ๆ แล้วตรวจการซ่อน
    expect(lid.status).toBe(403);
    const [row] = await sql`
      INSERT INTO listings (seller_id, category_id, title, price) VALUES (${users.banned.id}, ${itLeaf}, 'ของผู้ถูกระงับ', 100) RETURNING id`;
    expect((await call("GET", `/listings/${row.id}`, { token: users.other.token })).status).toBe(404);
    expect((await call("GET", `/listings/${row.id}`, { token: users.admin.token })).status).toBe(200);
  });

  test("path รูป/id ที่มั่ว → 404", async () => {
    expect((await call("GET", "/listings/not-a-uuid", { token: users.other.token })).status).toBe(404);
    expect((await call("GET", `/uploads/listings/${id}/..%2F..%2Fenv.jpg`, { token: users.other.token })).status).toBe(404);
    expect((await call("GET", `/uploads/listings/${id}/9.jpg`, { token: users.other.token })).status).toBe(404);
  });
});

describe("แก้ไข (BR05, BR24)", () => {
  let id: string;
  beforeAll(async () => {
    id = (await post(users.seller.token, { categoryId: bookLeaf, title: "ฟิสิกส์ 1", facultyIds: String(faculty1) })).data.id;
  });

  test("เจ้าของแก้ชื่อ/ราคาได้ และ search_text อัปเดตตาม", async () => {
    const r = await call("PATCH", `/listings/${id}`, { token: users.seller.token, json: { title: "ฟิสิกส์ทั่วไป เล่ม 1", price: 300 } });
    expect(r.status).toBe(200);
    expect(r.data).toMatchObject({ title: "ฟิสิกส์ทั่วไป เล่ม 1", price: 300 });
    const [row] = await sql`SELECT search_text FROM listings WHERE id = ${id}`;
    expect(row.search_text).toContain("ทั่วไป");
  });

  test("คนอื่นแก้ไม่ได้ (403) แม้เป็นผู้ดูแล (BR05)", async () => {
    expect((await call("PATCH", `/listings/${id}`, { token: users.other.token, json: { price: 1 } })).status).toBe(403);
    expect((await call("PATCH", `/listings/${id}`, { token: users.admin.token, json: { price: 1 } })).status).toBe(403);
  });

  test("ค่าผิดกติกา → 422 และไม่เปลี่ยนข้อมูล · เปลี่ยนเป็นหมวดที่ไม่ใช่หนังสือโดยคณะถูกทิ้ง", async () => {
    expect((await call("PATCH", `/listings/${id}`, { token: users.seller.token, json: { price: 0 } })).status).toBe(422);
    expect((await call("PATCH", `/listings/${id}`, { token: users.seller.token, json: { facultyIds: [] } })).status).toBe(422); // หนังสือต้องมีคณะ
    const [row] = await sql`SELECT price FROM listings WHERE id = ${id}`;
    expect(row.price).toBe(300);
  });

  test("ถูกจอง/ขายแล้ว/ยกเลิกแล้ว/ถูกถอด แก้ไม่ได้ → 409", async () => {
    for (const status of ["RESERVED", "SOLD", "CANCELLED", "TAKEN_DOWN"]) {
      await sql`UPDATE listings SET status = ${status}, status_reason = ${status === "TAKEN_DOWN" ? "x" : null} WHERE id = ${id}`;
      const r = await call("PATCH", `/listings/${id}`, { token: users.seller.token, json: { price: 5 } });
      expect(r.status).toBe(409);
    }
    await sql`UPDATE listings SET status = 'AVAILABLE', status_reason = NULL WHERE id = ${id}`;
  });
});

describe("ยกเลิก / ลงขายอีกครั้ง (BR22)", () => {
  test("ยกเลิกต้องมีเหตุผล · ข้อเสนอที่รอตอบถูกยกเลิกและแจ้งผู้ซื้อ · ลงขายอีกครั้งล้างเหตุผล", async () => {
    const id = (await post(users.seller.token, { categoryId: itLeaf, title: "คีย์บอร์ดมือสอง" })).data.id;
    await sql`INSERT INTO deals (listing_id, buyer_id) VALUES (${id}, ${users.other.id})`;

    expect((await call("POST", `/listings/${id}/cancel`, { token: users.seller.token, json: { reason: "  " } })).status).toBe(422);
    expect((await call("POST", `/listings/${id}/cancel`, { token: users.other.token, json: { reason: "x" } })).status).toBe(403);

    const c = await call("POST", `/listings/${id}/cancel`, { token: users.seller.token, json: { reason: "ขายได้แล้วที่อื่น" } });
    expect(c.status).toBe(200);
    expect(c.data).toMatchObject({ status: "CANCELLED", statusReason: "ขายได้แล้วที่อื่น" });

    const [deal] = await sql`SELECT status FROM deals WHERE listing_id = ${id}`;
    expect(deal.status).toBe("CANCELLED");
    const [n] = await sql`SELECT type FROM notifications WHERE user_id = ${users.other.id} AND type = 'LISTING_CANCELLED'`;
    expect(n).toBeDefined();

    // ยกเลิกซ้ำไม่ได้
    expect((await call("POST", `/listings/${id}/cancel`, { token: users.seller.token, json: { reason: "อีกที" } })).status).toBe(409);
    // ลงขายอีกครั้ง
    const r = await call("POST", `/listings/${id}/relist`, { token: users.seller.token });
    expect(r.data).toMatchObject({ status: "AVAILABLE", statusReason: null });
    expect((await call("POST", `/listings/${id}/relist`, { token: users.seller.token })).status).toBe(409);
  });

  test("ถูกจองแล้วยกเลิกประกาศตรง ๆ ไม่ได้ ต้องยกเลิกดีลก่อน · ขายแล้วทำอะไรไม่ได้ (BR22, BR24)", async () => {
    const id = (await post(users.seller.token, { categoryId: itLeaf, title: "จอมอนิเตอร์" })).data.id;
    await sql`UPDATE listings SET status = 'RESERVED' WHERE id = ${id}`;
    const r = await call("POST", `/listings/${id}/cancel`, { token: users.seller.token, json: { reason: "x" } });
    expect(r.status).toBe(409);
    expect(r.data.message).toContain("ยกเลิกดีลก่อน");

    await sql`UPDATE listings SET status = 'SOLD' WHERE id = ${id}`;
    for (const path of ["cancel", "relist", "resubmit"])
      expect((await call("POST", `/listings/${id}/${path}`, { token: users.seller.token, json: { reason: "x" } })).status).toBe(409);
  });
});

describe("ผู้ดูแลตีกลับ / ถอด / กู้คืน (BR14, BR23)", () => {
  test("ผู้ใช้ทั่วไปเรียก /admin/listings → 403", async () => {
    const id = (await post(users.seller.token, { categoryId: itLeaf, title: "ของที่ไม่ควรโดนแตะ" })).data.id;
    expect((await call("POST", `/admin/listings/${id}/return`, { token: users.other.token, json: { reason: "x" } })).status).toBe(403);
    expect((await call("POST", `/admin/listings/${id}/takedown`, { token: users.seller.token, json: { reason: "x" } })).status).toBe(403);
  });

  test("ตีกลับ → ผู้ขายเห็นเหตุผลและได้แจ้งเตือน · ดีลที่ค้างถูกยกเลิก · แก้แล้วส่งใหม่ได้", async () => {
    const id = (await post(users.seller.token, { categoryId: itLeaf, title: "ลำโพงบลูทูธ" })).data.id;
    await sql`INSERT INTO deals (listing_id, buyer_id, status) VALUES (${id}, ${users.other.id}, 'PENDING')`;

    expect((await call("POST", `/admin/listings/${id}/return`, { token: users.admin.token, json: { reason: "  " } })).status).toBe(422);
    expect((await call("POST", `/admin/listings/${id}/return`, { token: users.admin.token, json: { reason: "ราคาไม่ตรงกับสภาพสินค้า" } })).status).toBe(200);

    const d = (await call("GET", `/listings/${id}`, { token: users.seller.token })).data;
    expect(d).toMatchObject({ status: "RETURNED", statusReason: "ราคาไม่ตรงกับสภาพสินค้า" });
    const [deal] = await sql`SELECT status FROM deals WHERE listing_id = ${id}`;
    expect(deal.status).toBe("CANCELLED");
    const [n] = await sql`SELECT body FROM notifications WHERE user_id = ${users.seller.id} AND type = 'LISTING_RETURNED'`;
    expect(n.body).toContain("ราคาไม่ตรงกับสภาพสินค้า");

    // ระหว่างถูกตีกลับ: แก้ไขได้ · ส่งใหม่ผ่าน
    expect((await call("PATCH", `/listings/${id}`, { token: users.seller.token, json: { price: 500 } })).status).toBe(200);
    const re = await call("POST", `/listings/${id}/resubmit`, { token: users.seller.token });
    expect(re.data).toMatchObject({ status: "AVAILABLE", statusReason: null });
  });

  test("ถอด → กู้คืนได้เฉพาะผู้ดูแล · ผู้ขายลงใหม่/ส่งใหม่เองไม่ได้", async () => {
    const id = (await post(users.seller.token, { categoryId: itLeaf, title: "เมนบอร์ดเก่า" })).data.id;
    expect((await call("POST", `/admin/listings/${id}/takedown`, { token: users.admin.token, json: { reason: "สินค้าผิดกฎหมาย" } })).status).toBe(200);
    expect((await call("POST", `/listings/${id}/relist`, { token: users.seller.token })).status).toBe(409);
    expect((await call("POST", `/listings/${id}/resubmit`, { token: users.seller.token })).status).toBe(409);
    expect((await call("POST", `/admin/listings/${id}/restore`, { token: users.admin.token })).status).toBe(200);
    expect((await call("GET", `/listings/${id}`, { token: users.seller.token })).data.status).toBe("AVAILABLE");
  });

  test("ผู้ดูแลตัดสินประกาศของตัวเองไม่ได้ · ขายแล้วตีกลับไม่ได้", async () => {
    const mine = (await post(users.admin.token, { categoryId: itLeaf, title: "ของผู้ดูแลเอง" })).data.id;
    const r = await call("POST", `/admin/listings/${mine}/return`, { token: users.admin.token, json: { reason: "x" } });
    expect(r.status).toBe(403);
    expect(r.data.code).toBe("OWN_LISTING");

    const sold = (await post(users.seller.token, { categoryId: itLeaf, title: "ของที่ขายแล้ว" })).data.id;
    await sql`UPDATE listings SET status = 'SOLD' WHERE id = ${sold}`;
    expect((await call("POST", `/admin/listings/${sold}/return`, { token: users.admin.token, json: { reason: "x" } })).status).toBe(409);
  });
});

describe("ประกาศของฉัน และแท็ก", () => {
  test("แสดงเฉพาะของตัวเอง เรียงล่าสุดก่อน กรองตามสถานะได้ มีรูปปก", async () => {
    const all = (await call("GET", "/users/me/listings", { token: users.seller.token })).data as Json[];
    expect(all.length).toBeGreaterThan(3);
    expect(all.every((l) => l.cover?.startsWith("/uploads/listings/") || l.cover === null)).toBe(true);
    const cancelled = (await call("GET", "/users/me/listings?status=CANCELLED", { token: users.seller.token })).data as Json[];
    expect(cancelled.every((l) => l.status === "CANCELLED")).toBe(true);
    expect((await call("GET", "/users/me/listings?status=NOPE", { token: users.seller.token })).status).toBe(422);
    expect(((await call("GET", "/users/me/listings", { token: users.other.token })).data as Json[]).length).toBe(0);
  });

  test("GET /tags ส่งทั้ง 14 แท็กพร้อมหมวดที่ใช้ได้", async () => {
    const tags = (await call("GET", "/tags", { token: users.other.token })).data as Json[];
    expect(tags).toHaveLength(14);
    expect(tags.some((t) => t.categoryId === null)).toBe(true);
  });
});
