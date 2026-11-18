// รัน `bun test` บนฐานข้อมูลแยก "<ชื่อฐานเดิม>_test" ที่สะอาด — ข้อมูลสาธิตในฐานหลักไม่ถูกแตะและไม่ทำให้เทสต์ค้นหา/ตัวกรองเพี้ยน
// ใช้งาน: bun run test:clean            (เพิ่มอาร์กิวเมนต์ต่อท้ายได้ เช่น bun run test:clean tests/e2e.test.ts)
// ครั้งแรกจะสร้างฐานข้อมูล + รัน migration ให้เอง (ต้องใช้ผู้ใช้ฐานข้อมูลที่สร้างฐานและ extension ได้ ซึ่ง Docker postgres ปกติทำได้)
import { SQL } from "bun";

const raw = process.env.DATABASE_URL;
if (!raw) throw new Error("ไม่พบ DATABASE_URL (ตั้งใน backend/.env)");

const withDb = (db: string) => {
  const u = new URL(raw);
  u.pathname = `/${db}`;
  return u.toString();
};
const mainDb = decodeURIComponent(new URL(raw).pathname.slice(1));
const testDb = mainDb.endsWith("_test") ? mainDb : `${mainDb}_test`;

const admin = new SQL(withDb("postgres"));
const [exists] = await admin`SELECT 1 AS ok FROM pg_database WHERE datname = ${testDb}`;
if (!exists) {
  await admin.unsafe(`CREATE DATABASE "${testDb.replace(/"/g, "")}"`);
  console.log(`สร้างฐานข้อมูลทดสอบ ${testDb} แล้ว`);
}
await admin.close();

const env = { ...process.env, DATABASE_URL: withDb(testDb) };
const run = async (cmd: string[]) => (await Bun.spawn(cmd, { env, stdout: "inherit", stderr: "inherit" }).exited);

if ((await run(["bun", "scripts/migrate.ts"])) !== 0) process.exit(1); // migration ที่รันแล้วถูกข้ามเอง
console.log(`\nรันเทสต์บน ${testDb}\n`);
process.exit(await run(["bun", "test", ...process.argv.slice(2)]));
