// สร้าง (หรือรีเซ็ตรหัสผ่าน) ผู้ดูแลที่ล็อกอินด้วยอีเมลได้ — ไม่มีหน้าสมัครผู้ดูแลในแอปโดยตั้งใจ
// ตั้งสิทธิ์ผู้ดูแลที่ฐานข้อมูลโดยตรงเท่านั้น (ตามแผนเฟส 3)
//
//   bun scripts/dev-admin.ts admin@campusmarket.test "รหัสผ่านอย่างน้อย 8 ตัว"
import { sql } from "../src/db";

const [email, password] = process.argv.slice(2);
if (!email || !password || password.length < 8) {
  console.error("ใช้: bun scripts/dev-admin.ts <อีเมล> <รหัสผ่าน 8 ตัวขึ้นไป>");
  process.exit(1);
}

const hash = await Bun.password.hash(password, "argon2id");
await sql`
  INSERT INTO users (email, password_hash, display_name, is_verified, is_admin)
  VALUES (${email.toLowerCase()}, ${hash}, 'ผู้ดูแลระบบ', true, true)
  ON CONFLICT (email) DO UPDATE
    SET password_hash = ${hash}, is_verified = true, is_admin = true, is_banned = false
`;
console.log(`ผู้ดูแล ${email.toLowerCase()} พร้อมใช้งาน`);
await sql.close();
