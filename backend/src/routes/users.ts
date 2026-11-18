import { Elysia, t } from "elysia";
import { auth, requireAuth, requireVerified } from "../auth/plugin";
import { sql } from "../db";
import { AppError, notFound } from "../errors";
import { loadMe } from "../users/dto";
import { sellerStats } from "../users/stats";

export const userRoutes = new Elysia({ name: "routes-users" })
  .use(auth)

  // รายชื่อคณะ (16 คณะ) ไว้ให้เลือกในโปรไฟล์ — ต้องล็อกอิน แต่ยังไม่ต้องยืนยันตัวตน
  .get(
    "/faculties",
    async () => Array.from(await sql`SELECT id, name_th AS "nameTh" FROM faculties ORDER BY id`),
    { beforeHandle: requireAuth },
  )

  .get(
    "/users/me",
    async ({ user }) => {
      const me = await loadMe(user!.id);
      if (!me) throw notFound();
      return me;
    },
    { beforeHandle: requireAuth },
  )

  .patch(
    "/users/me",
    async ({ user, body }) => {
      if (body.displayName !== undefined) {
        const name = body.displayName.trim();
        if (name.length < 1 || name.length > 50)
          throw new AppError(422, "VALIDATION", "ชื่อที่แสดงต้องยาว 1–50 ตัวอักษร");
        await sql`UPDATE users SET display_name = ${name} WHERE id = ${user!.id}`;
      }
      if (body.facultyId !== undefined) {
        if (body.facultyId !== null) {
          const [f] = await sql`SELECT 1 FROM faculties WHERE id = ${body.facultyId}`;
          if (!f) throw new AppError(422, "VALIDATION", "ไม่พบคณะที่เลือก");
        }
        await sql`UPDATE users SET faculty_id = ${body.facultyId} WHERE id = ${user!.id}`;
      }
      return await loadMe(user!.id);
    },
    {
      beforeHandle: requireAuth,
      body: t.Object({
        displayName: t.Optional(t.String()),
        facultyId: t.Optional(t.Nullable(t.Integer())),
      }),
    },
  )

  // โปรไฟล์สาธารณะ: ไม่คืนข้อมูลลับ (อีเมล แฮช sub) — สถิติผู้ขายมาในเฟส 8
  .get(
    "/users/:id",
    async ({ params }) => {
      if (!/^[0-9a-f-]{36}$/i.test(params.id)) throw notFound();
      const [u] = await sql`
        SELECT u.id, u.display_name, f.name_th AS faculty, u.kmitl_sub IS NOT NULL AS is_kmitl
        FROM users u LEFT JOIN faculties f ON f.id = u.faculty_id
        WHERE u.id = ${params.id} AND u.deleted_at IS NULL AND u.is_verified AND NOT u.is_banned
      `;
      if (!u) throw notFound("ไม่พบผู้ใช้");
      return { id: u.id, displayName: u.display_name, faculty: u.faculty, isKmitl: u.is_kmitl, ...(await sellerStats(u.id)) };
    },
    { beforeHandle: requireVerified },
  );
