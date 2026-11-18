import { Elysia } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { sql } from "../db";

/** GET /tags — แท็กทั้งหมด (categoryId = หมวดหลักที่ใช้ได้ · null = ใช้ได้ทุกหมวด) */
export const catalogRoutes = new Elysia({ name: "routes-catalog" })
  .use(auth)
  .get(
    "/tags",
    async () =>
      Array.from(await sql`SELECT id, name_th AS "nameTh", category_id AS "categoryId" FROM tags ORDER BY id`),
    { beforeHandle: requireVerified },
  );
