import { Elysia, t } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const reviewDto = (r: any) => ({
  rating: r.rating as number,
  comment: (r.comment ?? null) as string | null,
  createdAt: new Date(r.created_at).toISOString(),
});

/** ผู้เกี่ยวข้องกับดีล (ผู้ซื้อหรือผู้ขายของประกาศ) — คนนอกได้ 404 เหมือนไม่มีดีล */
async function myDeal(dealId: string, me: string) {
  if (!UUID.test(dealId)) throw notFound("ไม่พบดีล");
  const [d] = await sql`
    SELECT d.id, d.status, d.buyer_id, l.seller_id FROM deals d JOIN listings l ON l.id = d.listing_id WHERE d.id = ${dealId}`;
  if (!d || (d.buyer_id !== me && d.seller_id !== me)) throw notFound("ไม่พบดีล");
  return d;
}

/** รีวิวผู้ขาย (BR12): เฉพาะผู้ซื้อของดีลที่สำเร็จ ดีลละครั้ง 1–5 ดาว ความเห็น ≤ 300 ตัว */
export const reviewRoutes = new Elysia({ name: "routes-reviews" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app
      .post(
        "/deals/:id/review",
        async ({ params, body, user }) => {
          const d = await myDeal(params.id, user!.id);
          if (d.buyer_id !== user!.id) throw forbidden("NOT_BUYER", "เฉพาะผู้ซื้อเท่านั้นที่รีวิวได้");
          if (d.status !== "COMPLETED") throw conflict("รีวิวได้หลังดีลสำเร็จเท่านั้น");
          const comment = body.comment?.trim() || null;
          if (comment && comment.length > 300) throw new AppError(422, "VALIDATION", "ความเห็นต้องไม่เกิน 300 ตัวอักษร");
          // deal_id เป็น UNIQUE: กดซ้ำ/พร้อมกันก็ได้แค่ครั้งเดียว
          const [r] = await sql`
            INSERT INTO reviews (deal_id, rating, comment) VALUES (${d.id}, ${body.rating}, ${comment})
            ON CONFLICT (deal_id) DO NOTHING RETURNING rating, comment, created_at`;
          if (!r) throw conflict("ดีลนี้รีวิวไปแล้ว");
          return reviewDto(r);
        },
        { body: t.Object({ rating: t.Integer({ minimum: 1, maximum: 5 }), comment: t.Optional(t.String()) }) },
      )
      // รีวิวของดีลนี้ (null = ยังไม่มี) — แอปใช้ตัดสินว่าจะโชว์ปุ่ม "ให้คะแนน" หรือไม่
      .get("/deals/:id/review", async ({ params, user }) => {
        const d = await myDeal(params.id, user!.id);
        const [r] = await sql`SELECT rating, comment, created_at FROM reviews WHERE deal_id = ${d.id}`;
        return { review: r ? reviewDto(r) : null };
      }),
  );
