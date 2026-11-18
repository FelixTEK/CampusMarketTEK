import { Elysia, t } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { sql } from "../db";
import { AppError, notFound } from "../errors";
import { imageUrl } from "../listings/storage";
import { parseIdList } from "../listings/validate";
import { tokenize } from "../search/tokenize";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SORTS = ["relevant", "new", "price_asc", "price_desc"] as const;
type Sort = (typeof SORTS)[number];

/** ตัดตัวอักษรพิเศษของ LIKE ออก เพื่อให้ค้นข้อความตรง ๆ ได้ (เช่น 100% หรือ a_b) */
const escapeLike = (s: string) => s.replace(/[\\%_]/g, (c) => `\\${c}`);

/**
 * คำค้นของ tsquery: ตัดคำด้วย Intl.Segmenter เหมือนตอนเก็บ search_text แล้วจับแบบ "ขึ้นต้นด้วย" (:*) ทุกคำ (AND)
 * ตัดอักขระที่เป็นตัวดำเนินการของ tsquery ออก (' & | ! ( ) :) กันคำสั่งหลุดเข้าไป — ค่าทั้งหมดถูกส่งเป็น parameter อยู่แล้ว
 */
export function buildTsQuery(q: string): string | null {
  const parts = tokenize(q)
    .map((tok) => tok.replace(/[^\p{L}\p{N}\p{M}]/gu, ""))
    .filter(Boolean);
  return parts.length ? parts.map((p) => `${p}:*`).join(" & ") : null;
}

const PAGE_SIZE = 20;

export type ListingFilters = {
  q?: string;
  category?: number;
  faculty?: number;
  tagIds: number[];
  minPrice?: number;
  maxPrice?: number;
};

/**
 * WHERE ร่วมของรายการสินค้า:
 *   แสดงเฉพาะ กำลังขาย/ถูกจอง ของผู้ขายที่ยังใช้งานได้ (BR15, BR16)
 *   หมวดรวมหมวดย่อยทั้งหมด · แท็กต้องมีครบทุกตัว · ค้นข้อความ = full-text 'simple' (ขึ้นต้นคำ) หรือชื่อมีข้อความนั้น (pg_trgm)
 */
function whereClause(f: ListingFilters, tsq: string | null) {
  const like = f.q ? `%${escapeLike(f.q.trim())}%` : null;
  return sql`
    l.status IN ('AVAILABLE', 'RESERVED')
    AND NOT u.is_banned AND u.deleted_at IS NULL
    ${f.category ? sql`AND l.category_id IN (SELECT id FROM tree)` : sql``}
    ${f.faculty ? sql`AND EXISTS (SELECT 1 FROM listing_faculties lf WHERE lf.listing_id = l.id AND lf.faculty_id = ${f.faculty})` : sql``}
    ${f.tagIds.length ? sql`AND (SELECT count(*) FROM listing_tags lt WHERE lt.listing_id = l.id AND lt.tag_id IN ${sql(f.tagIds)}) = ${f.tagIds.length}` : sql``}
    ${f.minPrice !== undefined ? sql`AND l.price >= ${f.minPrice}` : sql``}
    ${f.maxPrice !== undefined ? sql`AND l.price <= ${f.maxPrice}` : sql``}
    ${like ? sql`AND (${tsq ? sql`to_tsvector('simple', l.search_text) @@ to_tsquery('simple', ${tsq}) OR ` : sql``}l.title ILIKE ${like})` : sql``}
  `;
}

const treeCte = (category?: number) =>
  category
    ? sql`WITH RECURSIVE tree AS (
        SELECT id FROM categories WHERE id = ${category}
        UNION ALL SELECT c.id FROM categories c JOIN tree t ON c.parent_id = t.id
      )`
    : sql``;

function parseFilters(query: Record<string, any>): ListingFilters {
  const price = (v: unknown, label: string) => {
    if (v === undefined || v === "") return undefined;
    const n = Number(v);
    if (!Number.isInteger(n) || n < 0) throw new AppError(422, "VALIDATION", `${label}ไม่ถูกต้อง`);
    return n;
  };
  const q = typeof query.q === "string" ? query.q.trim().slice(0, 100) : undefined;
  const f: ListingFilters = {
    q: q || undefined,
    category: query.category ? Number(query.category) : undefined,
    faculty: query.faculty ? Number(query.faculty) : undefined,
    tagIds: parseIdList(query.tags, "แท็ก").slice(0, 5),
    minPrice: price(query.minPrice, "ราคาต่ำสุด"),
    maxPrice: price(query.maxPrice, "ราคาสูงสุด"),
  };
  if (f.minPrice !== undefined && f.maxPrice !== undefined && f.minPrice > f.maxPrice)
    throw new AppError(422, "VALIDATION", "ราคาต่ำสุดต้องไม่เกินราคาสูงสุด");
  return f;
}

/** แถวสินค้าแบบการ์ด (หน้าแรก / ผลค้นหา / รายการโปรด) */
function toCard(r: any) {
  return {
    id: r.id,
    title: r.title,
    price: r.price,
    status: r.status,
    categoryId: r.category_id,
    cover: r.cover ? imageUrl(r.cover) : null,
    sellerName: r.seller_name,
    sellerFaculty: r.seller_faculty,
    createdAt: r.created_at,
    isFavorite: r.is_favorite,
  };
}

const cardSelect = (me: string) => sql`
  l.id, l.title, l.price, l.status, l.category_id, l.created_at,
  u.display_name AS seller_name, f.name_th AS seller_faculty,
  (SELECT image_key FROM listing_images i WHERE i.listing_id = l.id ORDER BY position LIMIT 1) AS cover,
  EXISTS (SELECT 1 FROM favorites fv WHERE fv.listing_id = l.id AND fv.user_id = ${me}) AS is_favorite
`;

const queryShape = {
  q: t.Optional(t.String()),
  category: t.Optional(t.String()),
  faculty: t.Optional(t.String()),
  tags: t.Optional(t.String()),
  minPrice: t.Optional(t.String()),
  maxPrice: t.Optional(t.String()),
  sort: t.Optional(t.String()),
  page: t.Optional(t.String()),
};

export const browseRoutes = new Elysia({ name: "routes-browse" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      // ── ค้นหา / กรอง / เรียง (20 รายการต่อหน้า) ───────────────────────
      .get(
        "/listings",
        async ({ query, user }) => {
          const f = parseFilters(query);
          const sort = (query.sort ?? (f.q ? "relevant" : "new")) as Sort;
          if (!SORTS.includes(sort)) throw new AppError(422, "VALIDATION", "การเรียงไม่ถูกต้อง");
          const page = Math.max(1, Math.floor(Number(query.page ?? 1)) || 1);
          const tsq = f.q ? buildTsQuery(f.q) : null;

          // ตรงชื่อก่อน แล้วค่อยเรียงตามความเกี่ยวข้องของ full-text แล้วใหม่สุด
          const order =
            sort === "price_asc" ? sql`l.price ASC, l.created_at DESC`
            : sort === "price_desc" ? sql`l.price DESC, l.created_at DESC`
            : sort === "relevant" && f.q
              ? sql`(l.title ILIKE ${`%${escapeLike(f.q)}%`}) DESC,
                    ${tsq ? sql`ts_rank(to_tsvector('simple', l.search_text), to_tsquery('simple', ${tsq})) DESC,` : sql``}
                    l.created_at DESC`
              : sql`l.created_at DESC`;

          const rows = Array.from(await sql`
            ${treeCte(f.category)}
            SELECT ${cardSelect(user!.id)}
            FROM listings l
            JOIN users u ON u.id = l.seller_id
            LEFT JOIN faculties f ON f.id = u.faculty_id
            WHERE ${whereClause(f, tsq)}
            ORDER BY ${order}, l.id
            LIMIT ${PAGE_SIZE + 1} OFFSET ${(page - 1) * PAGE_SIZE}
          `);
          return { items: rows.slice(0, PAGE_SIZE).map(toCard), page, hasMore: rows.length > PAGE_SIZE };
        },
        { query: t.Object(queryShape) },
      )

      // จำนวนผลลัพธ์ตามตัวกรอง (ปุ่ม "แสดง N รายการ" ในหน้าตัวกรอง)
      .get(
        "/listings/count",
        async ({ query }) => {
          const f = parseFilters(query);
          const tsq = f.q ? buildTsQuery(f.q) : null;
          const [{ count }] = await sql`
            ${treeCte(f.category)}
            SELECT count(*)::int AS count
            FROM listings l JOIN users u ON u.id = l.seller_id
            WHERE ${whereClause(f, tsq)}
          `;
          return { count };
        },
        { query: t.Object(queryShape) },
      )

      // ── รายการโปรด ──────────────────────────────────────────────────────
      // ขายแล้วยังเห็นในรายการโปรดได้ (BR16) · ยกเลิก/ตีกลับ/ถอด หรือผู้ขายถูกระงับ จะไม่แสดง
      .get("/favorites", async ({ user }) => {
        const rows = Array.from(await sql`
          SELECT ${cardSelect(user!.id)}, fav.created_at AS favorited_at
          FROM favorites fav
          JOIN listings l ON l.id = fav.listing_id
          JOIN users u ON u.id = l.seller_id
          LEFT JOIN faculties f ON f.id = u.faculty_id
          WHERE fav.user_id = ${user!.id}
            AND l.status IN ('AVAILABLE', 'RESERVED', 'SOLD')
            AND NOT u.is_banned AND u.deleted_at IS NULL
          ORDER BY fav.created_at DESC
          LIMIT 200
        `);
        return rows.map(toCard);
      })

      // เพิ่ม/เอาออก: ทำซ้ำได้ผลเท่าเดิม (แอปส่งซ้ำตอนเน็ตกลับมาได้โดยไม่พัง)
      .put("/favorites/:listingId", async ({ params, user }) => {
        if (!UUID.test(params.listingId)) throw notFound("ไม่พบประกาศ");
        const [l] = await sql`
          SELECT l.status, l.seller_id, u.is_banned, (u.deleted_at IS NOT NULL) AS deleted
          FROM listings l JOIN users u ON u.id = l.seller_id WHERE l.id = ${params.listingId}`;
        const visible = l && !l.is_banned && !l.deleted && ["AVAILABLE", "RESERVED", "SOLD"].includes(l.status);
        if (!visible) throw notFound("ไม่พบประกาศ");
        await sql`
          INSERT INTO favorites (user_id, listing_id) VALUES (${user!.id}, ${params.listingId})
          ON CONFLICT (user_id, listing_id) DO NOTHING`;
        return { favorite: true };
      })

      .delete("/favorites/:listingId", async ({ params, user }) => {
        if (!UUID.test(params.listingId)) throw notFound("ไม่พบประกาศ");
        await sql`DELETE FROM favorites WHERE user_id = ${user!.id} AND listing_id = ${params.listingId}`;
        return { favorite: false };
      }),
  );
