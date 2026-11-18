import { mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { Elysia, t } from "elysia";
import { auth, requireAdmin, requireVerified } from "../auth/plugin";
import { assertBannerImage } from "../banners/image";
import { sql } from "../db";
import { AppError, notFound } from "../errors";
import { readImage, UPLOAD_DIR, imageUrl } from "../listings/storage";

/** แสดงพร้อมกันได้ไม่เกิน 6 ใบ (BR18) */
export const MAX_LIVE_BANNERS = 6;
const LINK_RE = /^(https:\/\/|campusmarket:\/\/)/;
const FILE_RE = /^[0-9a-f-]{36}\.(jpg|png)$/;
const SMALLINT = 32767;
/** id เป็น smallint: เลขนอกช่วงคือไม่มีแน่นอน (404 ไม่ใช่ 500) */
const bannerId = (v: string) => {
  if (!/^\d{1,5}$/.test(v) || Number(v) > SMALLINT) throw notFound("ไม่พบแบนเนอร์");
  return Number(v);
};

const parseDate = (v: string, what: string) => {
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) throw new AppError(422, "VALIDATION", `${what}ไม่ใช่วันที่ที่ถูกต้อง`);
  return d;
};
const cleanLink = (v: string | null | undefined) => {
  const link = v?.trim() || null;
  if (link && (!LINK_RE.test(link) || link.length > 500)) throw new AppError(422, "VALIDATION", "ลิงก์ต้องขึ้นต้นด้วย https:// หรือ campusmarket://");
  return link;
};
const cleanTitle = (v: string) => {
  const title = v.trim();
  if (title.length < 1 || title.length > 100) throw new AppError(422, "VALIDATION", "ชื่อแบนเนอร์ต้องยาว 1–100 ตัวอักษร");
  return title;
};
const cleanSort = (v: number | undefined) => {
  const n = v ?? 100;
  if (!Number.isInteger(n) || n < 0 || n > SMALLINT) throw new AppError(422, "VALIDATION", "ลำดับต้องเป็นจำนวนเต็ม 0–32767");
  return n;
};

const status = (r: any, now = Date.now()) =>
  !r.is_active ? "OFF" : now < new Date(r.starts_at).getTime() ? "SCHEDULED" : now > new Date(r.ends_at).getTime() ? "EXPIRED" : "LIVE";

const adminDto = (r: any) => ({
  id: r.id as number,
  title: r.title as string,
  imageUrl: imageUrl(r.image_key),
  linkUrl: (r.link_url ?? null) as string | null,
  startsAt: new Date(r.starts_at).toISOString(),
  endsAt: new Date(r.ends_at).toISOString(),
  isActive: r.is_active as boolean,
  sort: r.sort as number,
  status: status(r),
});

export const bannerRoutes = new Elysia({ name: "routes-banners" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      // ที่แสดงอยู่ตอนนี้: เปิดอยู่ + อยู่ในช่วงวัน · เรียงตาม sort แล้ว id · ไม่เกิน 6 · ไม่ส่งชื่อ (ชื่อไว้ให้ผู้ดูแลจำ)
      .get("/banners", async () => {
        const rows = await sql`
          SELECT id, image_key, link_url FROM ad_banners
          WHERE is_active AND now() >= starts_at AND now() <= ends_at
          ORDER BY sort, id LIMIT ${MAX_LIVE_BANNERS}`;
        return { items: rows.map((r: any) => ({ id: r.id as number, imageUrl: imageUrl(r.image_key), linkUrl: (r.link_url ?? null) as string | null })) };
      })
      .get("/uploads/banners/:file", async ({ params }) => {
        if (!FILE_RE.test(params.file)) throw notFound();
        const bytes = await readImage(`banners/${params.file}`);
        if (!bytes) throw notFound();
        return new Response(new Uint8Array(bytes), {
          headers: { "content-type": params.file.endsWith(".png") ? "image/png" : "image/jpeg", "cache-control": "private, max-age=3600" },
        });
      }),
  );

export const adminBannerRoutes = new Elysia({ name: "routes-admin-banners", prefix: "/admin/banners" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app
      .get("/", async () => {
        const rows = await sql`SELECT * FROM ad_banners ORDER BY sort, id`;
        const items = rows.map((r: any) => adminDto(r));
        return { items, live: items.filter((b: { status: string }) => b.status === "LIVE").length, max: MAX_LIVE_BANNERS };
      })
      .post(
        "/",
        async ({ body }) => {
          const title = cleanTitle(body.title);
          const link = cleanLink(body.linkUrl);
          const starts = parseDate(body.startsAt, "วันเริ่ม");
          const ends = parseDate(body.endsAt, "วันสิ้นสุด");
          if (ends <= starts) throw new AppError(422, "VALIDATION", "วันสิ้นสุดต้องหลังวันเริ่ม");
          const sort = cleanSort(body.sort);
          const bytes = new Uint8Array(await body.image.arrayBuffer());
          const type = assertBannerImage(bytes); // ชนิดจากไบต์จริง + สัดส่วน 2:1 + กว้าง ≥ 600

          const key = `banners/${crypto.randomUUID()}.${type === "image/png" ? "png" : "jpg"}`;
          const path = join(UPLOAD_DIR, key);
          await mkdir(dirname(path), { recursive: true });
          await writeFile(path, bytes);
          try {
            const [r] = await sql`
              INSERT INTO ad_banners (title, image_key, link_url, starts_at, ends_at, sort)
              VALUES (${title}, ${key}, ${link}, ${starts}, ${ends}, ${sort}) RETURNING *`;
            return adminDto(r);
          } catch (e) {
            await rm(path, { force: true }); // ไม่ทิ้งไฟล์ค้างถ้าบันทึกไม่สำเร็จ
            throw e;
          }
        },
        {
          body: t.Object({
            title: t.String(),
            linkUrl: t.Optional(t.String()),
            startsAt: t.String(),
            endsAt: t.String(),
            sort: t.Optional(t.Numeric()),
            image: t.File({ type: ["image/jpeg", "image/png"], maxSize: "2m" }),
          }),
        },
      )
      .patch(
        "/:id",
        async ({ params, body }) => {
          const id = bannerId(params.id);
          const [cur] = await sql`SELECT * FROM ad_banners WHERE id = ${id}`;
          if (!cur) throw notFound("ไม่พบแบนเนอร์");
          const title = body.title !== undefined ? cleanTitle(body.title) : cur.title;
          const link = body.linkUrl !== undefined ? cleanLink(body.linkUrl) : cur.link_url;
          const starts = body.startsAt !== undefined ? parseDate(body.startsAt, "วันเริ่ม") : new Date(cur.starts_at);
          const ends = body.endsAt !== undefined ? parseDate(body.endsAt, "วันสิ้นสุด") : new Date(cur.ends_at);
          if (ends <= starts) throw new AppError(422, "VALIDATION", "วันสิ้นสุดต้องหลังวันเริ่ม");
          const sort = body.sort !== undefined ? cleanSort(body.sort) : cur.sort;
          const active = body.isActive ?? cur.is_active;
          const [r] = await sql`
            UPDATE ad_banners SET title = ${title}, link_url = ${link}, starts_at = ${starts}, ends_at = ${ends}, sort = ${sort}, is_active = ${active}
            WHERE id = ${id} RETURNING *`;
          return adminDto(r);
        },
        {
          body: t.Object({
            title: t.Optional(t.String()),
            linkUrl: t.Optional(t.Nullable(t.String())),
            startsAt: t.Optional(t.String()),
            endsAt: t.Optional(t.String()),
            sort: t.Optional(t.Integer()),
            isActive: t.Optional(t.Boolean()),
          }),
        },
      )
      .delete("/:id", async ({ params }) => {
        const [r] = await sql`DELETE FROM ad_banners WHERE id = ${bannerId(params.id)} RETURNING image_key`;
        if (!r) throw notFound("ไม่พบแบนเนอร์");
        await rm(join(UPLOAD_DIR, r.image_key), { force: true });
        return { ok: true };
      }),
  );
