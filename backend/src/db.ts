import { SQL } from "bun";
import { env } from "./env";

// ตัวเชื่อม PostgreSQL ตัวเดียวของทั้งแอป (มี connection pool ในตัว)
// ใช้แบบ tagged template เสมอ: sql`SELECT * FROM users WHERE id = ${id}`
// ค่าใน ${} ถูกส่งเป็น parameter แยกจากคำสั่ง จึงกัน SQL injection ให้เอง
export const sql = new SQL(env.databaseUrl);
