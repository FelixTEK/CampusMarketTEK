// สร้างผู้ใช้ทดสอบที่ยืนยันตัวตนแล้ว แล้วพิมพ์ JWT ออกมา (ไว้ใส่ -PdevToken ตอนรันแอป debug)
// ใช้เฉพาะตอนพัฒนา — เฟส 3 มีหน้าเข้าสู่ระบบจริงแล้ว ใช้เมื่ออยากข้ามหน้าล็อกอินเท่านั้น
//
//   bun scripts/dev-token.ts          ผู้ใช้ทั่วไป
//   bun scripts/dev-token.ts admin    ผู้ดูแล (is_admin)
//   bun scripts/dev-admin.ts <อีเมล> <รหัสผ่าน>  สร้างผู้ดูแลที่ล็อกอินด้วยอีเมลได้ (ดูไฟล์นั้น)
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";

const admin = process.argv[2] === "admin";
const sub = admin ? "dev-test-admin" : "dev-test-user";

const [user] = await sql`
  INSERT INTO users (kmitl_sub, display_name, is_verified, is_admin)
  VALUES (${sub}, ${admin ? "ผู้ดูแลทดสอบ" : "ผู้ใช้ทดสอบ"}, true, ${admin})
  ON CONFLICT (kmitl_sub) DO UPDATE
    SET is_verified = true, is_admin = ${admin}, is_banned = false, deleted_at = NULL
  RETURNING id
`;

console.log(await signToken(user.id));
await sql.close();
