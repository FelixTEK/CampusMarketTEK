// สร้างข้อมูลสาธิตผ่าน API จริง (ผู้ขาย 1 คน + ผู้ซื้อ 1 คน + ประกาศ 12 รายการ) — ไว้ดู UI และเดโม
// ใช้: backend ต้องรันอยู่ แล้ว `bun scripts/seed-demo.ts <โฟลเดอร์ที่มี test-card.jpg และ test-selfie.jpg>`
// ใช้เฉพาะตอนพัฒนา · ลบทีหลังได้ที่ผู้ใช้ *@example.test (ประกาศ แชต ดีลของพวกเขา)
// หมวดหมู่ของแต่ละสินค้าระบุเป็นชื่อหมวด (ไม่ใช่ลำดับ) เพื่อให้สินค้ากับหมวดตรงกันเสมอ
import { readFileSync } from "node:fs";

const B = "http://localhost:3000";
const S = process.argv[2]!; // โฟลเดอร์ที่มีรูปทดสอบ
const img = (n: string) => new Blob([readFileSync(`${S}/${n}`)], { type: "image/jpeg" });

async function api(method: string, path: string, token?: string, body?: unknown) {
  const r = await fetch(B + path, {
    method,
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body && !(body instanceof FormData) ? { "content-type": "application/json" } : {}) },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} → ${r.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function user(email: string, name: string) {
  const reg = await api("POST", "/auth/register", undefined, { email, password: "TestPass-12345", displayName: name }).catch(async () =>
    api("POST", "/auth/login", undefined, { email, password: "TestPass-12345" }));
  await api("POST", "/auth/thaid/mock", reg.token).catch(() => {});
  return reg.token as string;
}

const seller = await user("seller2@example.test", "ต้น");
await user("buyer1@example.test", "มิ้นท์");

const cats: any[] = await api("GET", "/categories", seller);
const faculties: any[] = await api("GET", "/faculties", seller);
const tags: any[] = await api("GET", "/tags", seller);
const children = (id: number) => cats.filter((c) => c.parentId === id);
/** หมวดใบ (ไม่มีหมวดย่อย) ตามชื่อ ภายใต้หมวดหลักที่ชื่อขึ้นต้นด้วย rootName — ไม่พบหรือซ้ำให้ error ทันที ไม่เดา */
const leaf = (rootName: string, leafName: string): number => {
  const root = cats.find((c) => c.parentId === null && c.nameTh.startsWith(rootName));
  if (!root) throw new Error(`ไม่พบหมวดหลัก ${rootName}`);
  const under = (id: number): any[] => children(id).flatMap((c) => [c, ...under(c.id)]);
  const hits = under(root.id).filter((c) => !children(c.id).length && c.nameTh.replace(/^\d+ /, "") === leafName);
  if (hits.length !== 1) throw new Error(`หมวด ${rootName} › ${leafName} พบ ${hits.length} รายการ (ต้องเป็น 1)`);
  return hits[0].id;
};
const tag = (n: string) => tags.find((t) => t.nameTh === n)?.id;
const fac = (i: number) => faculties[i].id;

const items: Array<[number, string, string, number, number[], string[], string[]]> = [
  [leaf("หนังสือ", "คณิตศาสตร์"), "แคลคูลัส 1 (Thomas) ฉบับที่ 14", "มีไฮไลต์เล็กน้อย ใช้กับวิชา 01006xxx", 250, [fac(0), fac(4), fac(5)], ["มีเฉลย"], ["test-card.jpg", "test-selfie.jpg"]],
  [leaf("หนังสือ", "ฟิสิกส์"), "หนังสือฟิสิกส์ ปี 1 เล่ม 1", "ฟิสิกส์ทั่วไป สภาพดี", 180, [fac(0)], ["ไม่มีรอยขีดเขียน"], ["test-selfie.jpg"]],
  [leaf("หนังสือ", "เคมี"), "ตำราเคมีอินทรีย์ Organic Chemistry", "ฉบับภาษาอังกฤษ", 350, [fac(4)], [], ["test-card.jpg"]],
  [leaf("อุปกรณ์", "Gaming"), "Lenovo Legion 5 Pro Gaming", "Ryzen 7 RAM 16GB SSD 512GB RTX 3060 โน้ตบุ๊คเกมมิ่ง", 27900, [], ["ใช้เล่นเกม", "ประกันศูนย์"], ["test-card.jpg", "test-selfie.jpg"]],
  [leaf("อุปกรณ์", "เมาส์"), "Logitech G102 เมาส์เกมมิ่ง มีกล่อง", "8000 DPI สาย USB ใช้งาน 1 ปี", 290, [], ["มีกล่อง"], ["test-selfie.jpg"]],
  [leaf("อุปกรณ์", "Monitor"), "จอ 24 นิ้ว IPS 75Hz", "ไม่มีจุดเสีย", 1900, [], [], ["test-card.jpg"]],
  [leaf("อุปกรณ์", "iPhone"), "iPhone 13 128GB สีดำ", "แบตเตอรี่ 88%", 4900, [], ["มีใบเสร็จ / หลักฐานการซื้อ"], ["test-selfie.jpg"]],
  [leaf("อุปกรณ์", "คีย์บอร์ด"), "คีย์บอร์ด Mechanical Keychron K2", "Brown switch", 2400, [], ["ขายด่วน"], ["test-card.jpg"]],
  [leaf("เครื่องเขียน", "ปากกา"), "ชุดปากกา Pilot + ไส้ 10 ด้าม", "ใหม่ยังไม่แกะ", 120, [], ["ครบชุด", "ยังไม่แกะ"], ["test-selfie.jpg"]],
  [leaf("เครื่องเขียน", "อื่น ๆ"), "เครื่องคิดเลข Casio fx-991", "ใช้ในห้องสอบได้", 450, [], [], ["test-card.jpg"]],
  [leaf("เครื่องเขียน", "สมุด"), "สมุดโน้ตสันห่วง A4 5 เล่ม", "", 95, [], [], ["test-selfie.jpg"]],
  [leaf("อุปกรณ์", "อื่น ๆ"), "เก้าอี้เกมมิ่ง ปรับเอนได้", "นั่งสบาย", 1500, [], ["ใช้เล่นเกม"], ["test-card.jpg"]],
];

for (const [cat, title, desc, price, facs, tagNames, files] of items) {
  const fd = new FormData();
  fd.set("categoryId", String(cat)); fd.set("title", title); fd.set("description", desc); fd.set("price", String(price));
  if (facs.length) fd.set("facultyIds", facs.join(","));
  const ids = tagNames.map(tag).filter(Boolean);
  if (ids.length) fd.set("tagIds", ids.join(","));
  for (const f of files) fd.append("images", img(f), f);
  const r = await api("POST", "/listings", seller, fd);
  console.log("ลงแล้ว", r.id.slice(0, 8), title);
}
