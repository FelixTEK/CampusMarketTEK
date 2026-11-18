import { Elysia, t } from "elysia";
import { auth, requireVerified, type AuthUser } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { PUBLIC_STATUSES, assertTransition, type ListingStatus } from "../listings/status";
import { imageUrl, readImage, readImages, removeListingImages, saveImages } from "../listings/storage";
import { parseIdList, validateListing } from "../listings/validate";
import { notify } from "../notifications";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATUSES: ListingStatus[] = ["AVAILABLE", "RESERVED", "SOLD", "CANCELLED", "RETURNED", "TAKEN_DOWN"];
const mustBeUuid = (id: string) => {
  if (!UUID.test(id)) throw notFound("ไม่พบประกาศ");
};

/** ล็อกแถวประกาศไว้ใน transaction (ป้องกันสองคนเปลี่ยนสถานะพร้อมกัน) */
export async function lockListing(tx: any, id: string) {
  const [l] = await tx`SELECT id, seller_id, status, title FROM listings WHERE id = ${id} FOR UPDATE`;
  if (!l) throw notFound("ไม่พบประกาศ");
  return l as { id: string; seller_id: string; status: ListingStatus; title: string };
}

/** ยกเลิกดีลที่ค้างของประกาศนี้ (ในสถานะที่ระบุ) แล้วแจ้งผู้ซื้อ — คืนจำนวนที่ยกเลิก */
export async function cancelOpenDeals(tx: any, listingId: string, statuses: string[], message: string, type: string) {
  const rows = await tx`
    UPDATE deals SET status = 'CANCELLED', updated_at = now()
    WHERE listing_id = ${listingId} AND status IN ${tx(statuses)}
    RETURNING id, buyer_id
  `;
  for (const d of rows) await notify(tx, d.buyer_id, type, message, d.id);
  return rows.length as number;
}

/** ผู้ดูทั่วไปเห็นเฉพาะ กำลังขาย/ถูกจอง/ขายแล้ว ของผู้ขายที่ยังใช้งานได้ · เจ้าของและผู้ดูแลเห็นทุกสถานะ (BR15, BR16) */
function canView(l: { seller_id: string; status: ListingStatus; seller_banned: boolean; seller_deleted: boolean }, viewer: AuthUser) {
  if (l.seller_id === viewer.id || viewer.isAdmin) return true;
  if (l.seller_banned || l.seller_deleted) return false;
  return PUBLIC_STATUSES.includes(l.status);
}

async function loadDetail(id: string, viewer: AuthUser) {
  const [l] = await sql`
    SELECT l.id, l.seller_id, l.category_id, l.title, l.description, l.price, l.status, l.status_reason,
           l.created_at, l.updated_at,
           u.display_name AS seller_name, u.is_banned AS seller_banned, (u.deleted_at IS NOT NULL) AS seller_deleted,
           (u.kmitl_sub IS NOT NULL) AS seller_kmitl, f.name_th AS seller_faculty
    FROM listings l
    JOIN users u ON u.id = l.seller_id
    LEFT JOIN faculties f ON f.id = u.faculty_id
    WHERE l.id = ${id}
  `;
  if (!l || !canView(l, viewer)) throw notFound("ไม่พบประกาศ");

  const [images, faculties, tags, path] = await Promise.all([
    sql`SELECT image_key FROM listing_images WHERE listing_id = ${id} ORDER BY position`,
    sql`SELECT f.id, f.name_th AS "nameTh" FROM listing_faculties lf JOIN faculties f ON f.id = lf.faculty_id WHERE lf.listing_id = ${id} ORDER BY f.id`,
    sql`SELECT t.id, t.name_th AS "nameTh" FROM listing_tags lt JOIN tags t ON t.id = lt.tag_id WHERE lt.listing_id = ${id} ORDER BY t.id`,
    sql`
      WITH RECURSIVE up AS (
        SELECT id, parent_id, name_th, 0 AS depth FROM categories WHERE id = ${l.category_id}
        UNION ALL
        SELECT c.id, c.parent_id, c.name_th, up.depth + 1 FROM categories c JOIN up ON c.id = up.parent_id
      )
      SELECT id, name_th AS "nameTh" FROM up ORDER BY depth DESC
    `,
  ]);

  const isOwner = l.seller_id === viewer.id;
  return {
    id: l.id,
    title: l.title,
    description: l.description,
    price: l.price,
    status: l.status as ListingStatus,
    // เหตุผลที่ตีกลับ/ถอด/ยกเลิก เห็นเฉพาะเจ้าของและผู้ดูแล
    statusReason: isOwner || viewer.isAdmin ? l.status_reason : null,
    createdAt: l.created_at,
    updatedAt: l.updated_at,
    categoryId: l.category_id,
    categoryPath: Array.from(path),
    images: Array.from(images).map((r: any) => imageUrl(r.image_key)),
    faculties: Array.from(faculties),
    tags: Array.from(tags),
    seller: { id: l.seller_id, displayName: l.seller_name, faculty: l.seller_faculty, isKmitl: l.seller_kmitl },
    isOwner,
  };
}

const imageType = ["image/jpeg", "image/png"];

export const listingRoutes = new Elysia({ name: "routes-listings" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      // ── ลงประกาศ (multipart: ข้อมูล + รูป 1–5) ───────────────────────────
      .post(
        "/listings",
        async ({ user, body }) => {
          const me = user!;
          const files = Array.isArray(body.images) ? body.images : [body.images];
          const images = await readImages(files);
          const data = await validateListing(sql, {
            categoryId: body.categoryId,
            title: body.title,
            description: body.description ?? "",
            price: body.price,
            facultyIds: parseIdList(body.facultyIds, "คณะ"),
            tagIds: parseIdList(body.tagIds, "แท็ก"),
          });

          const id = crypto.randomUUID();
          const keys = await saveImages(id, images);
          try {
            await sql.begin(async (tx) => {
              await tx`
                INSERT INTO listings (id, seller_id, category_id, title, description, price, search_text)
                VALUES (${id}, ${me.id}, ${data.categoryId}, ${data.title}, ${data.description}, ${data.price}, ${data.searchText})
              `;
              for (const [position, key] of keys.entries())
                await tx`INSERT INTO listing_images (listing_id, position, image_key) VALUES (${id}, ${position}, ${key})`;
              for (const fid of data.facultyIds)
                await tx`INSERT INTO listing_faculties (listing_id, faculty_id) VALUES (${id}, ${fid})`;
              for (const tid of data.tagIds)
                await tx`INSERT INTO listing_tags (listing_id, tag_id) VALUES (${id}, ${tid})`;
            });
          } catch (e) {
            await removeListingImages(id); // ไม่ทิ้งรูปค้างถ้าบันทึกฐานข้อมูลไม่สำเร็จ
            throw e;
          }
          return { id, status: "AVAILABLE" as const };
        },
        {
          body: t.Object({
            categoryId: t.Numeric(),
            title: t.String(),
            description: t.Optional(t.String()),
            price: t.Numeric(),
            facultyIds: t.Optional(t.String()),
            tagIds: t.Optional(t.String()),
            images: t.Files({ type: imageType, maxSize: "6m" }),
          }),
        },
      )

      // ── ประกาศของฉัน (เรียงล่าสุดก่อน) ───────────────────────────────────
      .get(
        "/users/me/listings",
        async ({ user, query }) => {
          const status = query.status;
          if (status !== undefined && !STATUSES.includes(status as ListingStatus))
            throw new AppError(422, "VALIDATION", "สถานะไม่ถูกต้อง");
          const rows = await sql`
            SELECT l.id, l.title, l.price, l.status, l.status_reason, l.category_id, l.created_at, l.updated_at,
                   (SELECT image_key FROM listing_images i WHERE i.listing_id = l.id ORDER BY position LIMIT 1) AS cover
            FROM listings l
            WHERE l.seller_id = ${user!.id} ${status ? sql`AND l.status = ${status}` : sql``}
            ORDER BY l.updated_at DESC
            LIMIT 200
          `;
          return Array.from(rows).map((r: any) => ({
            id: r.id,
            title: r.title,
            price: r.price,
            status: r.status,
            statusReason: r.status_reason,
            categoryId: r.category_id,
            cover: r.cover ? imageUrl(r.cover) : null,
            createdAt: r.created_at,
            updatedAt: r.updated_at,
          }));
        },
        { query: t.Object({ status: t.Optional(t.String()) }) },
      )

      .get("/listings/:id", async ({ params, user }) => {
        mustBeUuid(params.id);
        return await loadDetail(params.id, user!);
      })

      // ── แก้ไข: เฉพาะเจ้าของ และเฉพาะตอน กำลังขาย / ถูกตีกลับ (BR05, BR24) ──
      .patch(
        "/listings/:id",
        async ({ params, body, user }) => {
          mustBeUuid(params.id);
          await sql.begin(async (tx) => {
            const l = await lockListing(tx, params.id);
            if (l.seller_id !== user!.id) throw forbidden("NOT_OWNER", "แก้ไขได้เฉพาะเจ้าของประกาศ");
            const why: Partial<Record<ListingStatus, string>> = {
              RESERVED: "ประกาศที่ถูกจองแล้วแก้ไขไม่ได้ (ยกเลิกดีลก่อน)",
              SOLD: "ประกาศที่ขายแล้วแก้ไขไม่ได้",
              CANCELLED: "ประกาศที่ยกเลิกแล้วต้องลงขายอีกครั้งก่อนจึงจะแก้ไขได้",
              TAKEN_DOWN: "ประกาศที่ถูกถอดแก้ไขไม่ได้",
            };
            if (why[l.status]) throw conflict(why[l.status]!);

            // รวมค่าเดิมกับค่าที่แก้ แล้วตรวจทั้งชุดใหม่ (เช่น เปลี่ยนหมวดเป็นหนังสือต้องมีคณะ)
            const [cur] = await tx`SELECT category_id, title, description, price FROM listings WHERE id = ${params.id}`;
            const curFaculties = Array.from(await tx`SELECT faculty_id FROM listing_faculties WHERE listing_id = ${params.id}`).map((r: any) => r.faculty_id as number);
            const curTags = Array.from(await tx`SELECT tag_id FROM listing_tags WHERE listing_id = ${params.id}`).map((r: any) => r.tag_id as number);
            const data = await validateListing(tx, {
              categoryId: body.categoryId ?? cur.category_id,
              title: body.title ?? cur.title,
              description: body.description ?? cur.description,
              price: body.price ?? cur.price,
              facultyIds: body.facultyIds ? [...new Set(body.facultyIds)] : curFaculties,
              tagIds: body.tagIds ? [...new Set(body.tagIds)] : curTags,
            });

            await tx`
              UPDATE listings
              SET category_id = ${data.categoryId}, title = ${data.title}, description = ${data.description},
                  price = ${data.price}, search_text = ${data.searchText}, updated_at = now()
              WHERE id = ${params.id}
            `;
            await tx`DELETE FROM listing_faculties WHERE listing_id = ${params.id}`;
            for (const fid of data.facultyIds)
              await tx`INSERT INTO listing_faculties (listing_id, faculty_id) VALUES (${params.id}, ${fid})`;
            await tx`DELETE FROM listing_tags WHERE listing_id = ${params.id}`;
            for (const tid of data.tagIds)
              await tx`INSERT INTO listing_tags (listing_id, tag_id) VALUES (${params.id}, ${tid})`;
          });
          return await loadDetail(params.id, user!);
        },
        {
          body: t.Object({
            categoryId: t.Optional(t.Integer()),
            title: t.Optional(t.String()),
            description: t.Optional(t.String()),
            price: t.Optional(t.Integer()),
            facultyIds: t.Optional(t.Array(t.Integer())),
            tagIds: t.Optional(t.Array(t.Integer())),
          }),
        },
      )

      // ── ยกเลิก: เฉพาะตอน กำลังขาย · ถามเหตุผล · ข้อเสนอที่รอตอบถูกยกเลิกและแจ้งผู้ซื้อ (BR22) ──
      .post(
        "/listings/:id/cancel",
        async ({ params, body, user }) => {
          mustBeUuid(params.id);
          const reason = body.reason.trim();
          if (reason.length < 1 || reason.length > 200)
            throw new AppError(422, "VALIDATION", "กรุณาระบุเหตุผล (ไม่เกิน 200 ตัวอักษร)");

          await sql.begin(async (tx) => {
            const l = await lockListing(tx, params.id);
            if (l.seller_id !== user!.id) throw forbidden("NOT_OWNER", "ยกเลิกได้เฉพาะเจ้าของประกาศ");
            if (l.status === "RESERVED")
              throw conflict("ประกาศนี้ถูกจองแล้ว กรุณายกเลิกดีลก่อนจึงจะยกเลิกประกาศได้");
            assertTransition(l.status, "CANCELLED", "owner");
            await tx`UPDATE listings SET status = 'CANCELLED', status_reason = ${reason}, updated_at = now() WHERE id = ${l.id}`;
            await cancelOpenDeals(tx, l.id, ["PENDING"], `ผู้ขายยกเลิกประกาศ "${l.title}"`, "LISTING_CANCELLED");
          });
          return await loadDetail(params.id, user!);
        },
        { body: t.Object({ reason: t.String() }) },
      )

      // ── ลงขายอีกครั้ง (ยกเลิกแล้ว → กำลังขาย) ───────────────────────────
      .post("/listings/:id/relist", async ({ params, user }) => {
        mustBeUuid(params.id);
        await ownerTransition(params.id, user!.id, "CANCELLED", "ลงขายอีกครั้งได้เฉพาะประกาศที่ยกเลิกแล้ว");
        return await loadDetail(params.id, user!);
      })

      // ── ส่งใหม่หลังแก้ตามที่ผู้ดูแลตีกลับ (ถูกตีกลับ → กำลังขาย) ──────────
      .post("/listings/:id/resubmit", async ({ params, user }) => {
        mustBeUuid(params.id);
        await ownerTransition(params.id, user!.id, "RETURNED", "ส่งใหม่ได้เฉพาะประกาศที่ถูกตีกลับ");
        return await loadDetail(params.id, user!);
      })

      // ── รูปสินค้า (ต้องล็อกอิน · เห็นได้ตามสิทธิ์เดียวกับตัวประกาศ) ────────
      .get("/uploads/listings/:listingId/:file", async ({ params, user }) => {
        if (!UUID.test(params.listingId) || !/^[0-4]\.(jpg|png)$/.test(params.file)) throw notFound();
        const [l] = await sql`
          SELECT l.seller_id, l.status, u.is_banned AS seller_banned, (u.deleted_at IS NOT NULL) AS seller_deleted
          FROM listings l JOIN users u ON u.id = l.seller_id WHERE l.id = ${params.listingId}
        `;
        if (!l || !canView(l, user!)) throw notFound();
        const bytes = await readImage(`listings/${params.listingId}/${params.file}`);
        if (!bytes) throw notFound();
        return new Response(new Uint8Array(bytes), {
          headers: {
            "content-type": params.file.endsWith(".png") ? "image/png" : "image/jpeg",
            "cache-control": "private, max-age=86400",
          },
        });
      }),
  );

/** เจ้าของเปลี่ยนสถานะ from → AVAILABLE (relist / resubmit) และล้างเหตุผลเดิม */
async function ownerTransition(id: string, userId: string, from: ListingStatus, wrongStateMessage: string) {
  await sql.begin(async (tx) => {
    const l = await lockListing(tx, id);
    if (l.seller_id !== userId) throw forbidden("NOT_OWNER", "ทำได้เฉพาะเจ้าของประกาศ");
    if (l.status !== from) throw conflict(wrongStateMessage);
    assertTransition(l.status, "AVAILABLE", "owner");
    await tx`UPDATE listings SET status = 'AVAILABLE', status_reason = NULL, updated_at = now() WHERE id = ${id}`;
  });
}
