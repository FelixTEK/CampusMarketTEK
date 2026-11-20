// ข้อมูลสาธิตสำหรับดู UI และเดโม: ผู้ขาย 20 คน (หนังสือ 8 · อุปกรณ์คอมพิวเตอร์ 8 · เครื่องเขียน 4) และผู้ซื้อ 10 คน
// ทั้งสองกลุ่มเป็นสมาชิก สจล. ครึ่งหนึ่ง และบุคคลภายนอกอีกครึ่งหนึ่ง · ประกาศ 64 รายการ สภาพสินค้าต่างกัน ราคาตามสภาพ
//
//   cd backend && bun scripts/seed-demo.ts            ล้างข้อมูลสาธิตเดิมแล้วสร้างใหม่ (รันซ้ำได้)
//   cd backend && bun scripts/seed-demo.ts --remove   ล้างอย่างเดียว
//
// ไม่ต้องเปิด backend (เรียก API ในโปรเซสเดียวกัน) แต่ต้องมีฐานข้อมูล · สุ่มด้วยค่าตั้งต้นคงที่ → ได้ข้อมูลชุดเดิมทุกครั้ง
// ล้างเฉพาะบัญชีที่มีอีเมล *@example.test และบัญชีของนักศึกษาสาธิต 15 คน (kmitl_sub = dummy-kmitl-<รหัส>) พร้อมประกาศ แชต ดีล รีวิว แจ้งเตือนของพวกเขา — ไม่แตะบัญชีอื่น
// บัญชีบุคคลภายนอกล็อกอินด้วยอีเมล + รหัสผ่าน TestPass-12345 · นักศึกษา 15 คนมาจาก DEMO_STUDENTS ใน src/kmitl/dummy.ts (ข้อมูลชุดเดียวกับ SSO จำลอง) จึงล็อกอินด้วยปุ่ม KMITL ได้ตรงกับบัญชีและประกาศเหล่านี้
import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { DEMO_STUDENTS } from "../src/kmitl/dummy";
import { removeListingImages } from "../src/listings/storage";

const IMG_DIR = join(import.meta.dir, "..", "seed", "images");
const PASSWORD = "TestPass-12345";

// ── สุ่มแบบกำหนดค่าตั้งต้น ──────────────────────────────────────────────
let state = 2569;
const rand = () => {
  state |= 0; state = (state + 0x6d2b79f5) | 0;
  let t = Math.imul(state ^ (state >>> 15), 1 | state);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const chance = (p: number) => rand() < p;
const pick = <T,>(a: T[]): T => a[Math.floor(rand() * a.length)]!;
const shuffle = <T,>(a: T[]): T[] => { const r = [...a]; for (let i = r.length - 1; i > 0; i--) { const j = Math.floor(rand() * (i + 1)); [r[i], r[j]] = [r[j]!, r[i]!]; } return r; };

// ── ล้างข้อมูลสาธิตเดิม ──────────────────────────────────────────────────
async function reset(): Promise<number> {
  const subs = DEMO_STUDENTS.map((s) => `dummy-kmitl-${s.student_id}`);
  // 'seed-kmitl-%' = รูปแบบรุ่นก่อนหน้าของ seed (ล้างให้ด้วย)
  const users = await sql`SELECT id FROM users WHERE email LIKE '%@example.test' OR kmitl_sub LIKE 'seed-kmitl-%' OR kmitl_sub IN ${sql(subs)}`;
  const uid: string[] = users.map((u: any) => u.id);
  if (uid.length === 0) return 0;
  const lid: string[] = (await sql`SELECT id FROM listings WHERE seller_id IN ${sql(uid)}`).map((l: any) => l.id);
  const cid: string[] = (await sql`SELECT id FROM conversations WHERE buyer_id IN ${sql(uid)} ${lid.length ? sql`OR listing_id IN ${sql(lid)}` : sql``}`).map((c: any) => c.id);
  const did: string[] = (await sql`SELECT id FROM deals WHERE buyer_id IN ${sql(uid)} ${lid.length ? sql`OR listing_id IN ${sql(lid)}` : sql``}`).map((d: any) => d.id);

  await sql.begin(async (tx) => {
    await tx`DELETE FROM reports WHERE reporter_id IN ${tx(uid)} OR (target_type = 'USER' AND target_id IN ${tx(uid)})`;
    if (lid.length) await tx`DELETE FROM reports WHERE target_type = 'LISTING' AND target_id IN ${tx(lid)}`;
    if (cid.length) await tx`DELETE FROM reports WHERE target_type = 'MESSAGE' AND target_id IN (SELECT id::text FROM messages WHERE conversation_id IN ${tx(cid)})`;
    await tx`DELETE FROM notifications WHERE user_id IN ${tx(uid)}`;
    if (did.length) await tx`DELETE FROM reviews WHERE deal_id IN ${tx(did)}`;
    if (cid.length) await tx`DELETE FROM messages WHERE conversation_id IN ${tx(cid)}`;
    await tx`DELETE FROM messages WHERE sender_id IN ${tx(uid)}`;
    if (did.length) await tx`DELETE FROM deals WHERE id IN ${tx(did)}`;
    if (cid.length) await tx`DELETE FROM conversations WHERE id IN ${tx(cid)}`;
    await tx`DELETE FROM favorites WHERE user_id IN ${tx(uid)}`;
    if (lid.length) await tx`DELETE FROM favorites WHERE listing_id IN ${tx(lid)}`;
    await tx`DELETE FROM id_card_requests WHERE user_id IN ${tx(uid)}`;
    if (lid.length) await tx`DELETE FROM listings WHERE id IN ${tx(lid)}`;
    await tx`DELETE FROM users WHERE id IN ${tx(uid)}`;
  });
  for (const id of lid) await removeListingImages(id);
  return uid.length;
}

if (process.argv.includes("--remove")) {
  console.log(`ล้างบัญชีสาธิต ${await reset()} บัญชี`);
  await sql.close();
  process.exit(0);
}

// ── บัญชีผู้ใช้ ──────────────────────────────────────────────────────────
type Person = { first: string; last: string; slug?: string };
const OUTSIDERS: Person[] = [
  { first: "สมชาย", last: "เจริญผล", slug: "somchai.c" }, { first: "สุวิทย์", last: "พึ่งบุญ", slug: "suwit.p" },
  { first: "วิภาวี", last: "ศรีทอง", slug: "wipawee.s" }, { first: "ประเสริฐ", last: "ทรัพย์มาก", slug: "prasert.s" },
  { first: "นิตยา", last: "ลือชา", slug: "nittaya.l" }, { first: "อนุสรณ์", last: "ชัยมงคล", slug: "anuson.c" },
  { first: "พัชรินทร์", last: "แสงอรุณ", slug: "patcharin.s" }, { first: "มานะ", last: "ใจเย็น", slug: "mana.j" },
  { first: "ศิริพร", last: "คงเจริญ", slug: "siriporn.k" }, { first: "ดวงใจ", last: "บุญมา", slug: "duangjai.b" },
  { first: "กิตติศักดิ์", last: "ปัญญาดี", slug: "kittisak.p" }, { first: "รัตนา", last: "สุขเกษม", slug: "rattana.s" },
  { first: "จิรายุ", last: "พรหมมา", slug: "chirayu.p" }, { first: "สุภาพร", last: "อ่อนละมุน", slug: "supaporn.o" },
  { first: "วัชระ", last: "เกษมสุข", slug: "watchara.k" },
];

type Account = { id: string; token: string; name: string; kmitl: boolean; login: string };
const outsiderHash = await Bun.password.hash(PASSWORD, "argon2id");
const lockedHash = await Bun.password.hash(randomBytes(32).toString("hex"), "argon2id"); // บัญชี สจล. ที่มีอีเมลล็อกอินด้วยรหัสผ่านไม่ได้

async function makeKmitl(st: Record<string, unknown>): Promise<Account> {
  const sid = String(st.student_id);
  const facultyId = sid.slice(2, 4) === "01" ? 1 : 5; // 01 วิศวกรรมศาสตร์ · 05 วิทยาศาสตร์
  const name = `${st.first_name_th} ${st.last_name_th}`;
  const [u] = await sql`
    INSERT INTO users (kmitl_sub, display_name, faculty_id, is_verified, email, password_hash)
    VALUES (${`dummy-kmitl-${sid}`}, ${name}, ${facultyId}, true, ${`${sid}@kmitl.ac.th`}, ${lockedHash}) RETURNING id`;
  return { id: u.id, token: await signToken(u.id), name, kmitl: true, login: `${sid}@kmitl.ac.th (กดปุ่ม KMITL แล้วเลือกคนนี้)` };
}
async function makeOutsider(p: Person): Promise<Account> {
  const name = `${p.first} ${p.last}`;
  const email = `${p.slug}@example.test`;
  const [u] = await sql`
    INSERT INTO users (email, password_hash, display_name, is_verified) VALUES (${email}, ${outsiderHash}, ${name}, true) RETURNING id`;
  return { id: u.id, token: await signToken(u.id), name, kmitl: false, login: email };
}

console.log(`ล้างข้อมูลสาธิตเดิม ${await reset()} บัญชี`);
const sellerPool = shuffle([
  ...(await Promise.all(DEMO_STUDENTS.slice(0, 10).map(makeKmitl))),
  ...(await Promise.all(OUTSIDERS.slice(0, 10).map(makeOutsider))),
]);
const buyers = [
  ...(await Promise.all(DEMO_STUDENTS.slice(10, 15).map(makeKmitl))),
  ...(await Promise.all(OUTSIDERS.slice(10, 15).map(makeOutsider))),
];
// สุ่มว่าใครขายหมวดไหน: หนังสือ 8 · อุปกรณ์คอมพิวเตอร์ 8 · เครื่องเขียน 4
const bookSellers = sellerPool.slice(0, 8), compSellers = sellerPool.slice(8, 16), statSellers = sellerPool.slice(16, 20);

// ── แค็ตตาล็อกสินค้า ─────────────────────────────────────────────────────
const ENG = 1, SCI = 5, IT = 6, BUS = 8, ART = 9; // รหัสคณะในตาราง faculties
type Item = { leaf: string; title: string; spec: string; price: number; faculties?: number[] };

const BOOKS: Item[] = [
  { leaf: "คณิตศาสตร์", title: "Thomas' Calculus (ฉบับภาษาอังกฤษ)", spec: "ผู้แต่ง Thomas, Weir, Hass · แคลคูลัส 1–3 ครบเล่ม", price: 650, faculties: [ENG, SCI, IT] },
  { leaf: "คณิตศาสตร์", title: "Discrete Mathematics and Its Applications", spec: "ผู้แต่ง Kenneth H. Rosen · ฉบับภาษาอังกฤษ", price: 700, faculties: [IT, SCI, ENG] },
  { leaf: "คณิตศาสตร์", title: "Linear Algebra and Its Applications", spec: "ผู้แต่ง David C. Lay · ฉบับภาษาอังกฤษ", price: 600, faculties: [ENG, SCI, IT] },
  { leaf: "คณิตศาสตร์", title: "Elementary Differential Equations and Boundary Value Problems", spec: "ผู้แต่ง Boyce และ DiPrima · ฉบับภาษาอังกฤษ", price: 550, faculties: [ENG, SCI] },
  { leaf: "คณิตศาสตร์", title: "Probability and Statistics for Engineers and Scientists", spec: "ผู้แต่ง Walpole, Myers · ฉบับภาษาอังกฤษ", price: 520, faculties: [ENG, SCI, IT] },
  { leaf: "ฟิสิกส์", title: "Fundamentals of Physics", spec: "ผู้แต่ง Halliday, Resnick, Walker · ฉบับภาษาอังกฤษ", price: 750, faculties: [ENG, SCI] },
  { leaf: "ฟิสิกส์", title: "University Physics with Modern Physics", spec: "ผู้แต่ง Young และ Freedman · ฉบับภาษาอังกฤษ", price: 800, faculties: [ENG, SCI] },
  { leaf: "เคมี", title: "Organic Chemistry", spec: "ผู้แต่ง L. G. Wade · ฉบับภาษาอังกฤษ", price: 650, faculties: [SCI, ENG] },
  { leaf: "เคมี", title: "Chemistry (Raymond Chang)", spec: "General Chemistry · ฉบับภาษาอังกฤษ", price: 600, faculties: [SCI, ENG] },
  { leaf: "ชีววิทยา", title: "Campbell Biology", spec: "ผู้แต่ง Reece และคณะ · ฉบับภาษาอังกฤษ", price: 900, faculties: [SCI] },
  { leaf: "วิทยาการคอมพิวเตอร์", title: "Introduction to Algorithms", spec: "ผู้แต่ง Cormen, Leiserson, Rivest, Stein (CLRS) · ฉบับภาษาอังกฤษ", price: 1100, faculties: [IT, SCI, ENG] },
  { leaf: "วิทยาการคอมพิวเตอร์", title: "Operating System Concepts", spec: "ผู้แต่ง Silberschatz, Galvin, Gagne · ฉบับภาษาอังกฤษ", price: 850, faculties: [IT, SCI, ENG] },
  { leaf: "วิทยาการคอมพิวเตอร์", title: "Computer Networking: A Top-Down Approach", spec: "ผู้แต่ง Kurose และ Ross · ฉบับภาษาอังกฤษ", price: 800, faculties: [IT, SCI, ENG] },
  { leaf: "วิทยาการคอมพิวเตอร์", title: "Database System Concepts", spec: "ผู้แต่ง Silberschatz, Korth, Sudarshan · ฉบับภาษาอังกฤษ", price: 850, faculties: [IT, SCI, ENG] },
  { leaf: "การเขียนโปรแกรม", title: "Clean Code", spec: "ผู้แต่ง Robert C. Martin · ฉบับภาษาอังกฤษ", price: 500, faculties: [IT, SCI, ENG] },
  { leaf: "การเขียนโปรแกรม", title: "Head First Java", spec: "ผู้แต่ง Kathy Sierra และ Bert Bates · ฉบับภาษาอังกฤษ", price: 450, faculties: [IT, SCI, ENG] },
  { leaf: "การเขียนโปรแกรม", title: "Python Crash Course", spec: "ผู้แต่ง Eric Matthes · ฉบับภาษาอังกฤษ", price: 450, faculties: [IT, SCI, ENG] },
  { leaf: "การเขียนโปรแกรม", title: "Design Patterns: Elements of Reusable Object-Oriented Software", spec: "ผู้แต่ง Gamma, Helm, Johnson, Vlissides · ฉบับภาษาอังกฤษ", price: 650, faculties: [IT, SCI, ENG] },
  { leaf: "ปัญญาประดิษฐ์และมัลติมีเดีย", title: "Artificial Intelligence: A Modern Approach", spec: "ผู้แต่ง Stuart Russell และ Peter Norvig · ฉบับภาษาอังกฤษ", price: 1100, faculties: [IT, SCI, ENG] },
  { leaf: "วิศวกรรมศาสตร์", title: "Engineering Mechanics: Statics", spec: "ผู้แต่ง R. C. Hibbeler · ฉบับภาษาอังกฤษ", price: 650, faculties: [ENG] },
  { leaf: "วิศวกรรมศาสตร์", title: "Mechanics of Materials", spec: "ผู้แต่ง R. C. Hibbeler · ฉบับภาษาอังกฤษ", price: 700, faculties: [ENG] },
  { leaf: "เศรษฐศาสตร์", title: "Principles of Economics", spec: "ผู้แต่ง N. Gregory Mankiw · ฉบับภาษาอังกฤษ", price: 600, faculties: [BUS, ART] },
  { leaf: "การจัดการธุรกิจ", title: "Marketing Management", spec: "ผู้แต่ง Philip Kotler และ Kevin Lane Keller · ฉบับภาษาอังกฤษ", price: 650, faculties: [BUS] },
  { leaf: "ภาษาอังกฤษ", title: "English Grammar in Use", spec: "ผู้แต่ง Raymond Murphy · พร้อมแบบฝึกหัดท้ายบท", price: 280, faculties: [ART] },
];

const COMPUTERS: Item[] = [
  { leaf: "MacBook", title: "MacBook Air 13 นิ้ว ชิป M1 RAM 8GB SSD 256GB", spec: "ชิป Apple M1 · หน้าจอ 13.3 นิ้ว · แบตเตอรี่ใช้งานได้ตามปกติ", price: 22000 },
  { leaf: "iPad", title: "iPad (รุ่นที่ 9) 64GB Wi-Fi สีสเปซเกรย์", spec: "หน้าจอ 10.2 นิ้ว · พื้นที่เก็บข้อมูล 64GB · รุ่น Wi-Fi", price: 7500 },
  { leaf: "iPhone", title: "iPhone 12 128GB สีน้ำเงิน", spec: "ความจุ 128GB · ปลดล็อกจาก iCloud แล้ว · ไม่ติดสัญญา", price: 12500 },
  { leaf: "อุปกรณ์เสริม Apple", title: "Apple Pencil (รุ่นที่ 2)", spec: "ใช้กับ iPad Pro และ iPad Air ที่รองรับ · ชาร์จแบบแม่เหล็ก", price: 3500 },
  { leaf: "Gaming", title: "ASUS TUF Gaming A15 Ryzen 7 RTX 3050", spec: "Ryzen 7 · RAM 16GB · SSD 512GB · การ์ดจอ RTX 3050 · จอ 15.6 นิ้ว 144Hz", price: 25900 },
  { leaf: "ทั่วไป / ธุรกิจ / Workstation", title: "Lenovo ThinkPad T480 Core i5 RAM 16GB SSD 256GB", spec: "Core i5 รุ่น 8 · RAM 16GB · SSD 256GB · จอ 14 นิ้ว · เหมาะกับงานเอกสารและเขียนโปรแกรม", price: 9500 },
  { leaf: "Monitor", title: "จอคอมพิวเตอร์ Dell 24 นิ้ว IPS Full HD", spec: "แผง IPS 1920×1080 · รีเฟรช 60Hz · พอร์ต HDMI และ VGA", price: 2900 },
  { leaf: "Printer", title: "เครื่องพิมพ์อิงค์เจ็ท Canon PIXMA G2010 (ระบบแท็งก์)", spec: "พิมพ์ สแกน ถ่ายเอกสาร · ระบบแท็งก์หมึกติดตั้งมาจากโรงงาน", price: 2800 },
  { leaf: "ลำโพง", title: "ลำโพงบลูทูธ JBL Flip 5", spec: "กันน้ำ IPX7 · เชื่อมต่อบลูทูธ · เล่นต่อเนื่องได้ประมาณ 12 ชั่วโมง", price: 2200 },
  { leaf: "หูฟัง", title: "หูฟังไร้สาย Sony WH-CH520", spec: "ครอบหู บลูทูธ 5.2 · ใช้งานต่อเนื่องได้นาน · มีไมโครโฟนในตัว", price: 1100 },
  { leaf: "ไมโครโฟน", title: "ไมโครโฟน USB Blue Yeti", spec: "เสียบ USB ใช้งานได้ทันที · เหมาะกับประชุมออนไลน์และบันทึกเสียง", price: 3200 },
  { leaf: "จอยเกม", title: "จอยเกม Xbox Wireless Controller", spec: "ใช้กับ Windows และ Xbox · เชื่อมต่อบลูทูธหรือสาย USB-C", price: 1200 },
  { leaf: "คีย์บอร์ด", title: "คีย์บอร์ด Mechanical Keychron K2 (Brown Switch)", spec: "ไร้สาย/มีสาย · แป้นพิมพ์ 75% · รองรับ Windows และ macOS", price: 2400 },
  { leaf: "เมาส์", title: "เมาส์ไร้สาย Logitech MX Master 3S", spec: "เซ็นเซอร์ 8000 DPI · ชาร์จผ่าน USB-C · เชื่อมต่อได้หลายอุปกรณ์", price: 2400 },
  { leaf: "การ์ดจอ", title: "การ์ดจอ NVIDIA GeForce RTX 3060 12GB", spec: "หน่วยความจำ 12GB GDDR6 · พอร์ต HDMI และ DisplayPort · ไม่เคยใช้ขุดเหรียญ", price: 7500 },
  { leaf: "CPU", title: "AMD Ryzen 5 5600", spec: "6 คอร์ 12 เธรด · ซ็อกเก็ต AM4 · มีพัดลมซีพียูให้ในชุด", price: 3200 },
  { leaf: "Disk (SSD / HDD)", title: "SSD Samsung 970 EVO Plus 500GB NVMe M.2", spec: "ความจุ 500GB · NVMe M.2 2280 · สุขภาพไดรฟ์ตรวจสอบได้ตอนนัดรับ", price: 1500 },
  { leaf: "RAM", title: "RAM DDR4 16GB (8GB×2) บัส 3200MHz", spec: "แรมคู่ 2 แถว · สำหรับเดสก์ท็อป · ใช้งานได้ปกติ ผ่านการทดสอบแล้ว", price: 1200 },
  { leaf: "Mainboard", title: "เมนบอร์ด MSI B550M PRO-VDH WIFI (AM4)", spec: "ซ็อกเก็ต AM4 · Micro-ATX · มี Wi-Fi ในตัว · พร้อมแผ่นหลังเคส", price: 2500 },
  { leaf: "PSU", title: "พาวเวอร์ซัพพลาย Corsair RM650 80+ Gold", spec: "กำลังไฟ 650W · สายแบบถอดได้ (Modular) · รับประกันตามผู้ผลิต", price: 2800 },
  { leaf: "เคส / ชุดระบายความร้อน", title: "เคสคอมพิวเตอร์ Cooler Master MasterBox Q300L", spec: "Micro-ATX · ฝาข้างอะคริลิก · พร้อมพัดลมด้านหลัง 1 ตัว", price: 1200 },
  { leaf: "แท็บเล็ต Android", title: "Samsung Galaxy Tab S6 Lite 64GB", spec: "หน้าจอ 10.4 นิ้ว · พร้อมปากกา S Pen · รุ่น Wi-Fi", price: 6500 },
  { leaf: "มือถือ Android", title: "Samsung Galaxy A54 5G 128GB", spec: "RAM 8GB · ความจุ 128GB · ปลดล็อกบัญชี Google แล้ว", price: 7500 },
  { leaf: "อื่น ๆ", title: "Raspberry Pi 4 Model B 4GB พร้อมเคสและอะแดปเตอร์", spec: "RAM 4GB · มีเคสพร้อมพัดลมและอะแดปเตอร์ไฟ · เหมาะกับงานโปรเจกต์และเรียนรู้ลินุกซ์", price: 2200 },
];

const STATIONERY: Item[] = [
  { leaf: "ปากกา", title: "ปากกาลูกลื่น Pilot G-2 0.5 มม. ชุด 10 ด้าม", spec: "หมึกสีน้ำเงิน 5 ด้าม สีดำ 5 ด้าม · ปากกาแบบกด", price: 180 },
  { leaf: "ปากกา", title: "ปากกาเน้นข้อความ Stabilo Boss ชุด 6 สี", spec: "หัวตัด 2–5 มม. · สีพาสเทล 6 สี", price: 220 },
  { leaf: "ดินสอไม้", title: "ดินสอไม้ Faber-Castell 2B กล่อง 12 แท่ง", spec: "ไส้ 2B เหมาะกับการเขียนและระบายคำตอบข้อสอบ", price: 90 },
  { leaf: "ดินสอกด", title: "ดินสอกด Rotring 600 ขนาด 0.5 มม.", spec: "ตัวโลหะ · ด้ามจับมีลาย · ใช้งานได้ปกติ", price: 650 },
  { leaf: "ไส้ดินสอ / ไส้ปากกา", title: "ไส้ดินสอกด Pentel Ain Stein 0.5 มม. HB 6 หลอด", spec: "หลอดละ 40 เส้น · ความแข็ง HB", price: 120 },
  { leaf: "ยางลบ", title: "ยางลบ Staedtler Mars Plastic 5 ก้อน", spec: "ลบสะอาด ไม่ทิ้งคราบ · ไม่มีส่วนผสมของ PVC", price: 80 },
  { leaf: "ลิควิด", title: "เทปลบคำผิด Tombow MONO 5 มม. 6 ชิ้น", spec: "ความยาวเทปชิ้นละประมาณ 6 เมตร · ตัวเครื่องแบบกดลบ", price: 150 },
  { leaf: "แม็ก", title: "เครื่องเย็บกระดาษ MAX HD-10 พร้อมลวด", spec: "เย็บได้ประมาณ 20 แผ่น · แถมลวดเย็บ 1 กล่อง", price: 120 },
  { leaf: "สมุด", title: "สมุดโน้ตสันห่วง A4 ปกแข็ง 5 เล่ม", spec: "กระดาษถนอมสายตา เล่มละ 80 แผ่น · เส้นบรรทัด", price: 250 },
  { leaf: "กระดาษ", title: "กระดาษ A4 Double A 80 แกรม 5 รีม", spec: "รีมละ 500 แผ่น · เหมาะกับงานพิมพ์และถ่ายเอกสาร", price: 450 },
  { leaf: "วงเวียน", title: "วงเวียนวาดแบบ Staedtler Mars comfort", spec: "ปรับรัศมีด้วยสกรูกลาง · พร้อมกล่องโลหะและไส้ดินสอสำรอง", price: 350 },
  { leaf: "ไม้บรรทัด", title: "ชุดไม้บรรทัดและฉาก (ไม้บรรทัด 30 ซม. ฉาก 45° และ 60°)", spec: "พลาสติกใส ขีดบอกระยะชัดเจน · ชุดละ 3 ชิ้น", price: 120 },
  { leaf: "ตลับเมตร", title: "ตลับเมตร Stanley 5 เมตร", spec: "ความยาว 5 ม. กว้าง 19 มม. · มีตัวล็อกสาย", price: 200 },
  { leaf: "กระเป๋าดินสอ", title: "กระเป๋าดินสอผ้าแคนวาส ซิปคู่", spec: "จุเครื่องเขียนได้มาก · มีช่องแยกชั้นในตัว", price: 150 },
  { leaf: "อื่น ๆ", title: "เครื่องคิดเลข Casio fx-991EX ClassWiz", spec: "ใช้ในห้องสอบได้ตามประกาศของสถาบันที่อนุญาต · รองรับฟังก์ชันวิทยาศาสตร์ครบ", price: 650 },
  { leaf: "อื่น ๆ", title: "เครื่องคิดเลข Casio fx-570ES Plus", spec: "ฟังก์ชันวิทยาศาสตร์ · ใช้พลังงานแสงอาทิตย์ร่วมกับถ่าน", price: 500 },
];

// ── สภาพสินค้า ────────────────────────────────────────────────────────────
type Cond = { code: "A" | "B" | "C" | "D" | "E"; label: string; factor: number; weight: number };
const CONDS: Cond[] = [
  { code: "A", label: "ใหม่ ยังไม่แกะ/ไม่เคยใช้งาน", factor: 0.9, weight: 15 },
  { code: "B", label: "ใหม่มาก ใช้งานเพียงเล็กน้อย", factor: 0.8, weight: 25 },
  { code: "C", label: "สภาพดี ใช้งานได้ปกติ", factor: 0.65, weight: 30 },
  { code: "D", label: "สภาพพอใช้ มีร่องรอยการใช้งาน", factor: 0.5, weight: 20 },
  { code: "E", label: "มีตำหนิชัดเจน ลดราคาตามสภาพ", factor: 0.4, weight: 10 },
];
const pickCond = (): Cond => {
  let r = rand() * 100;
  for (const c of CONDS) { if ((r -= c.weight) < 0) return c; }
  return CONDS[2]!;
};
const DEFECT: Record<"book" | "computer" | "stationery", Record<Cond["code"], string> > = {
  book: { A: "ไม่มี ยังไม่เคยเปิดอ่าน", B: "ไม่มีรอยขีดเขียน", C: "มีไฮไลต์บางหน้า", D: "มีรอยดินสอและขอบปกเก่าตามการใช้งาน", E: "ปกมีรอยพับ มีรอยปากกาหลายหน้า แต่เนื้อหาอ่านได้ครบ" },
  computer: { A: "ไม่มี ตัวเครื่องไม่มีรอย", B: "ไม่มีรอยที่เห็นได้ชัด", C: "มีรอยจากการใช้งานเล็กน้อย", D: "มีรอยขีดข่วนชัดเจนตามตัวเครื่อง ใช้งานได้ปกติทุกฟังก์ชัน", E: "มีตำหนิด้านรูปลักษณ์ชัดเจน (ดูรูปประกอบ) ใช้งานได้ปกติ" },
  stationery: { A: "ไม่มี ยังอยู่ในบรรจุภัณฑ์เดิม", B: "ไม่มี ใช้งานน้อยมาก", C: "ใช้งานแล้วเล็กน้อย", D: "มีร่องรอยการใช้งาน บรรจุภัณฑ์เก่า", E: "บรรจุภัณฑ์ชำรุด ตัวสินค้ายังใช้งานได้" },
};

// ── ข้อมูลอ้างอิงจากฐานข้อมูล ─────────────────────────────────────────────
const cats: any[] = await sql`SELECT id, parent_id, name_th FROM categories`;
const tagRows: any[] = await sql`SELECT id, name_th FROM tags`;
const kids = (id: number) => cats.filter((c) => c.parent_id === id);
function leafId(rootPrefix: string, leafName: string): number {
  const root = cats.find((c) => c.parent_id === null && c.name_th.startsWith(rootPrefix));
  if (!root) throw new Error(`ไม่พบหมวดหลัก ${rootPrefix}`);
  const under = (id: number): any[] => kids(id).flatMap((c) => [c, ...under(c.id)]);
  const hits = under(root.id).filter((c) => !kids(c.id).length && c.name_th.replace(/^\d+ /, "") === leafName);
  if (hits.length !== 1) throw new Error(`หมวด ${rootPrefix} › ${leafName} พบ ${hits.length} รายการ (ต้องเป็น 1)`);
  return hits[0].id;
}
const tagId = (n: string) => {
  const t = tagRows.find((x) => x.name_th === n);
  if (!t) throw new Error(`ไม่พบแท็ก ${n}`);
  return t.id as number;
};

function tagsFor(kind: "book" | "computer" | "stationery", it: Item, c: Cond): string[] {
  const tags: string[] = [];
  const fresh = c.code === "A" || c.code === "B";
  if (kind === "book") {
    if (fresh) tags.push("ไม่มีรอยขีดเขียน");
    if (["คณิตศาสตร์", "ฟิสิกส์", "เคมี"].includes(it.leaf) && chance(0.35)) tags.push("มีเฉลย");
  } else if (kind === "computer") {
    if (fresh && chance(0.7)) tags.push("มีกล่อง");
    if (fresh && chance(0.6)) tags.push("มีใบเสร็จ / หลักฐานการซื้อ");
    if (c.code === "A" && chance(0.5)) tags.push("ประกันศูนย์");
    if (["Gaming", "การ์ดจอ", "จอยเกม"].includes(it.leaf)) tags.push("ใช้เล่นเกม");
  } else {
    if (c.code === "A") tags.push("ยังไม่แกะ", "ครบชุด");
    else if (c.code === "B" && chance(0.5)) tags.push("ครบชุด");
  }
  if ((c.code === "D" || c.code === "E") && chance(0.35)) tags.push("ขายด่วน");
  return tags.slice(0, 5);
}

async function post(seller: Account, kind: "book" | "computer" | "stationery", it: Item): Promise<void> {
  const rootPrefix = kind === "book" ? "หนังสือ" : kind === "computer" ? "อุปกรณ์" : "เครื่องเขียน";
  const cond = pickCond();
  const price = Math.max(10, Math.round((it.price * cond.factor) / 10) * 10);
  const description = [
    `สภาพ: ${cond.label}`,
    it.spec,
    `ตำหนิ: ${DEFECT[kind][cond.code]}`,
    "นัดรับ: ภายในสถาบัน สถานที่และเวลาตามที่ตกลงในแชต",
  ].join("\n");

  const fd = new FormData();
  fd.set("categoryId", String(leafId(rootPrefix, it.leaf)));
  fd.set("title", it.title);
  fd.set("description", description);
  fd.set("price", String(price));
  if (it.faculties?.length) fd.set("facultyIds", it.faculties.join(","));
  const tags = tagsFor(kind, it, cond);
  if (tags.length) fd.set("tagIds", tags.map(tagId).join(","));
  const nImages = chance(0.4) ? 2 : 1;
  const first = Math.floor(rand() * 4) + 1;
  for (let i = 0; i < nImages; i++) {
    const n = ((first + i - 1) % 4) + 1;
    fd.append("images", new Blob([readFileSync(join(IMG_DIR, `${kind}-${n}.jpg`))], { type: "image/jpeg" }), `${kind}-${n}.jpg`);
  }
  const res = await app.handle(new Request("http://localhost/listings", { method: "POST", headers: { authorization: `Bearer ${seller.token}` }, body: fd }));
  if (res.status !== 200) throw new Error(`ลงประกาศ "${it.title}" ไม่สำเร็จ: ${res.status} ${await res.text()}`);
  const { id } = (await res.json()) as { id: string };
  // กระจายวันที่ลงใน 30 วันล่าสุดให้เหมือนใช้งานจริง
  const daysAgo = rand() * 30;
  // FormData แปลงบรรทัดใหม่เป็น CRLF — คืนเป็น LF เหมือนที่แอปส่งจริง
  await sql`UPDATE listings SET description = replace(description, E'\\r', ''), created_at = now() - ${daysAgo}::float8 * interval '1 day', updated_at = now() - ${daysAgo}::float8 * interval '1 day' WHERE id = ${id}`;
}

const plan: Array<[Account[], "book" | "computer" | "stationery", Item[], number]> = [
  [bookSellers, "book", shuffle(BOOKS), 3],
  [compSellers, "computer", shuffle(COMPUTERS), 3],
  [statSellers, "stationery", shuffle(STATIONERY), 4],
];
let total = 0;
for (const [sellers, kind, items, each] of plan) {
  for (const [i, s] of sellers.entries()) {
    for (const it of items.slice(i * each, (i + 1) * each)) { await post(s, kind, it); total++; }
  }
}

// ── สรุป ─────────────────────────────────────────────────────────────────
console.log(`\nสร้างประกาศ ${total} รายการ`);
const row = (a: Account) => `  ${a.name.padEnd(22)} ${a.kmitl ? "สจล.  " : "คนนอก"}  ${a.login}`;
console.log("\nผู้ขายหนังสือ (8)"); bookSellers.forEach((a) => console.log(row(a)));
console.log("\nผู้ขายอุปกรณ์คอมพิวเตอร์ (8)"); compSellers.forEach((a) => console.log(row(a)));
console.log("\nผู้ขายเครื่องเขียน (4)"); statSellers.forEach((a) => console.log(row(a)));
console.log("\nผู้ซื้อ (10)"); buyers.forEach((a) => console.log(row(a)));
console.log(`\nรหัสผ่านบัญชีบุคคลภายนอกทุกบัญชี: ${PASSWORD}`);
await sql.close();
process.exit(0);
