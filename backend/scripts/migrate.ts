// รันไฟล์ .sql ใน migrations/ ตามลำดับชื่อไฟล์ (001_xxx.sql, 002_xxx.sql, ...)
// ไฟล์ที่เคยรันแล้วถูกจดไว้ในตาราง schema_migrations และจะไม่ถูกรันซ้ำ
// แต่ละไฟล์รันใน transaction: ถ้าพังกลางไฟล์ จะย้อนกลับทั้งไฟล์
//
// ใช้งาน: bun migrate

import { readdir } from "node:fs/promises";
import { join } from "node:path";
import { sql } from "../src/db";

const dir = join(import.meta.dir, "..", "migrations");

await sql`
  CREATE TABLE IF NOT EXISTS schema_migrations (
    name       text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
  )
`;

const applied = new Set(
  (await sql`SELECT name FROM schema_migrations`).map(
    (row: { name: string }) => row.name,
  ),
);

const files = (await readdir(dir)).filter((f) => f.endsWith(".sql")).sort();
let count = 0;

for (const name of files) {
  if (applied.has(name)) continue;

  const text = await Bun.file(join(dir, name)).text();
  await sql.begin(async (tx) => {
    // .simple() = อนุญาตหลายคำสั่งในไฟล์เดียว (ไฟล์ migration ไม่มี input จากผู้ใช้)
    await tx.unsafe(text).simple();
    await tx`INSERT INTO schema_migrations (name) VALUES (${name})`;
  });

  console.log(`รันแล้ว  ${name}`);
  count++;
}

console.log(
  count === 0
    ? `ฐานข้อมูลเป็นปัจจุบันแล้ว (${files.length} ไฟล์)`
    : `เสร็จ: รันใหม่ ${count} ไฟล์`,
);

await sql.close();
