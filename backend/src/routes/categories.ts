import { Elysia } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { sql } from "../db";

/**
 * GET /categories — หมวดทั้งหมดจากตาราง categories (หมวดหลักก่อน แล้วหมวดย่อย)
 * ฟิลด์ตรงกับคอลัมน์ในฐานข้อมูล (parent_id → parentId, name_th → nameTh)
 */
export const categoryRoutes = new Elysia({ name: "routes-categories" })
  .use(auth)
  .get(
    "/categories",
    async () => {
      const rows = await sql`
        SELECT id, parent_id AS "parentId", name_th AS "nameTh", sort
        FROM categories
        ORDER BY parent_id NULLS FIRST, sort, id
      `;
      return Array.from(rows);
    },
    { beforeHandle: requireVerified },
  );
