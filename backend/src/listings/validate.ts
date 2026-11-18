import type { SQL } from "bun";
import { AppError } from "../errors";
import { buildSearchText } from "../search/tokenize";

export type ListingInput = {
  categoryId: number;
  title: string;
  description: string;
  price: number;
  facultyIds: number[];
  tagIds: number[];
};

const bad = (message: string) => new AppError(422, "VALIDATION", message);

/** "1,2,3" → [1,2,3] (multipart ส่งมาเป็นข้อความ) · ค่าที่ไม่ใช่เลขจำนวนเต็มบวก → 422 */
export function parseIdList(raw: string | undefined, label: string): number[] {
  if (!raw || !raw.trim()) return [];
  const ids = raw.split(",").map((s) => Number(s.trim()));
  if (ids.some((n) => !Number.isInteger(n) || n <= 0)) throw bad(`${label}ไม่ถูกต้อง`);
  return [...new Set(ids)];
}

/**
 * ตรวจประกาศตามกติกา BR04 (ตัวเลขทุกตัวตรงกับ CHECK ในฐานข้อมูล แล้วตรวจกติกาที่ SQL เขียนไม่ได้เพิ่มที่นี่):
 *   ชื่อ 3–100 · ราคา > 0 · รายละเอียด ≤ 2,000 · หมวดต้องเป็นหมวดใบสุดท้าย
 *   หนังสือต้องมีคณะ ≥ 1 · แท็ก ≤ 5 และต้องใช้ได้กับหมวดนั้น
 * คืนข้อมูลที่ทำความสะอาดแล้ว + ข้อความค้นหาที่ตัดคำแล้ว (search_text)
 * `db` เป็น sql หรือ transaction ก็ได้
 */
export async function validateListing(db: SQL, input: ListingInput) {
  const title = input.title.trim();
  const description = input.description ?? "";
  if (title.length < 3 || title.length > 100) throw bad("ชื่อสินค้าต้องยาว 3–100 ตัวอักษร");
  if (description.length > 2000) throw bad("รายละเอียดยาวได้ไม่เกิน 2,000 ตัวอักษร");
  if (!Number.isInteger(input.price) || input.price <= 0 || input.price > 2_147_483_647)
    throw bad("ราคาต้องเป็นจำนวนเต็มมากกว่า 0 บาท");

  // เส้นทางจากหมวดหลักลงมาถึงหมวดที่เลือก + ตรวจว่าเป็นใบสุดท้าย
  const path = Array.from(
    await db`
      WITH RECURSIVE up AS (
        SELECT id, parent_id, name_th, 0 AS depth FROM categories WHERE id = ${input.categoryId}
        UNION ALL
        SELECT c.id, c.parent_id, c.name_th, up.depth + 1 FROM categories c JOIN up ON c.id = up.parent_id
      )
      SELECT id, name_th FROM up ORDER BY depth DESC
    `,
  ) as Array<{ id: number; name_th: string }>;
  if (path.length === 0) throw bad("ไม่พบหมวดที่เลือก");
  const [{ children }] = await db`SELECT count(*)::int AS children FROM categories WHERE parent_id = ${input.categoryId}`;
  if (children > 0) throw bad("กรุณาเลือกหมวดย่อยให้ละเอียดที่สุด");

  const root = path[0]!;
  const isBook = root.name_th.startsWith("หนังสือ");

  // คณะ: หนังสือต้องมี ≥ 1 · หมวดอื่นไม่ใช้ (ทิ้งไป เก็บฐานข้อมูลให้สะอาด)
  const facultyIds = isBook ? input.facultyIds : [];
  if (isBook && facultyIds.length === 0) throw bad("หนังสือต้องเลือกคณะที่ใช้อย่างน้อย 1 คณะ");
  const faculties = facultyIds.length
    ? Array.from(await db`SELECT id, name_th FROM faculties WHERE id IN ${db(facultyIds)}`) as Array<{ id: number; name_th: string }>
    : [];
  if (faculties.length !== facultyIds.length) throw bad("ไม่พบคณะที่เลือก");

  // แท็ก ≤ 5 · ต้องเป็นแท็กทุกหมวด หรือแท็กของหมวดหลักเดียวกับสินค้า
  if (input.tagIds.length > 5) throw bad("เลือกแท็กได้ไม่เกิน 5 แท็ก");
  const tags = input.tagIds.length
    ? Array.from(await db`SELECT id, name_th, category_id FROM tags WHERE id IN ${db(input.tagIds)}`) as Array<{
        id: number;
        name_th: string;
        category_id: number | null;
      }>
    : [];
  if (tags.length !== input.tagIds.length) throw bad("ไม่พบแท็กที่เลือก");
  if (tags.some((t) => t.category_id !== null && t.category_id !== root.id)) throw bad("แท็กไม่ตรงกับหมวดสินค้า");

  const searchText = buildSearchText(
    title,
    description,
    ...path.map((p) => p.name_th),
    ...tags.map((t) => t.name_th),
    ...faculties.map((f) => f.name_th),
  );

  return {
    categoryId: input.categoryId,
    title,
    description,
    price: input.price,
    facultyIds,
    tagIds: input.tagIds,
    searchText,
    isBook,
  };
}
