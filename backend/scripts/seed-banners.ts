// ใส่แบนเนอร์ตัวอย่าง 5 ใบจาก backend/seed/banners/*.jpg (รูป 2:1 ย่อไว้ที่ 1456×720 ไม่เกิน 2 MB)
// ใช้: cd backend && bun scripts/seed-banners.ts
// รันซ้ำได้: ใบที่มีชื่อเดียวกันอยู่แล้วจะข้าม · ลบทิ้ง: bun scripts/seed-banners.ts --remove
// เขียนตรงฐานข้อมูลและโฟลเดอร์ uploads (เหมือน dev-admin.ts) เพราะไม่ต้องรู้รหัสผ่านผู้ดูแล — ตรวจรูปด้วยตัวตรวจเดียวกับ API
import { copyFile, mkdir, readFile, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { assertBannerImage } from "../src/banners/image";
import { sql } from "../src/db";
import { UPLOAD_DIR } from "../src/listings/storage";

const DIR = join(import.meta.dir, "..", "seed", "banners");

// ลำดับตาม sort · ใบสุดท้ายเป็นโฆษณาของแพลตฟอร์มเอง (ชวนลงโฆษณา)
const BANNERS: Array<{ file: string; title: string }> = [
  { file: "banner-1.jpg", title: "ข้าวแกงป้าใจ โรงอาหารพระเทพ" },
  { file: "banner-2.jpg", title: "KPhone 15 Pro" },
  { file: "banner-3.jpg", title: "เกม Grand Treat Aunt Auto" },
  { file: "banner-4.jpg", title: "แจ้งเตือนภัย มิจฉาชีพอ้างค้างชำระค่าเทอม" },
  { file: "banner-5.jpg", title: "สนใจโฆษณากับ Campus Market" },
];
const TAG = "[seed] "; // นำหน้าชื่อ ไว้แยกจากแบนเนอร์จริงตอนลบ

if (process.argv.includes("--remove")) {
  const rows = await sql`DELETE FROM ad_banners WHERE title LIKE ${TAG + "%"} RETURNING image_key`;
  for (const r of rows) await rm(join(UPLOAD_DIR, r.image_key), { force: true });
  console.log(`ลบแบนเนอร์ seed ${rows.length} ใบ`);
  await sql.close();
  process.exit(0);
}

const starts = new Date(Date.now() - 24 * 3600_000);
const ends = new Date(Date.now() + 365 * 24 * 3600_000);
let added = 0;

for (const [i, b] of BANNERS.entries()) {
  const title = TAG + b.title;
  const [exists] = await sql`SELECT id FROM ad_banners WHERE title = ${title}`;
  if (exists) { console.log(`ข้าม (มีแล้ว): ${b.title}`); continue; }

  const src = join(DIR, b.file);
  assertBannerImage(new Uint8Array(await readFile(src))); // 2:1 + กว้าง ≥ 600 + เป็น JPEG/PNG จริง
  const key = `banners/${crypto.randomUUID()}.jpg`;
  const dest = join(UPLOAD_DIR, key);
  await mkdir(dirname(dest), { recursive: true });
  await copyFile(src, dest);
  try {
    await sql`INSERT INTO ad_banners (title, image_key, link_url, starts_at, ends_at, sort)
              VALUES (${title}, ${key}, ${null}, ${starts}, ${ends}, ${(i + 1) * 10})`;
  } catch (e) {
    await rm(dest, { force: true });
    throw e;
  }
  added++;
  console.log(`เพิ่ม: ${b.title}`);
}
console.log(`เสร็จ — เพิ่ม ${added} ใบ`);
await sql.close();
