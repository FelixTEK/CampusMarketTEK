import { Elysia, t } from "elysia";
import { auth, requireVerified } from "../auth/plugin";
import { createDeal, dealAction, setMeetup, type DealAction } from "../chat/deals";
import { sql } from "../db";
import { AppError, notFound } from "../errors";
import { imageUrl } from "../listings/storage";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const id = (v: string) => {
  if (!UUID.test(v)) throw notFound("ไม่พบดีล");
  return v;
};

export const dealRoutes = new Elysia({ name: "routes-deals" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) => {
    const act = (path: string, action: DealAction) =>
      app.post(`/deals/:id/${path}`, ({ params, user }) => dealAction(id(params.id), user!.id, action));

    act("accept", "accept");
    act("reject", "reject");
    act("cancel", "cancel");
    act("handover", "handover");
    act("receive", "receive");

    return app
      // ผู้ซื้อกด "เสนอซื้อ" ในห้องแชต
      .post("/conversations/:id/deals", ({ params, user }) => {
        if (!UUID.test(params.id)) throw notFound("ไม่พบห้องแชต");
        return createDeal(params.id, user!.id);
      })

      .patch(
        "/deals/:id/meetup",
        ({ params, body, user }) => setMeetup(id(params.id), user!.id, body.meetup),
        { body: t.Object({ meetup: t.String() }) },
      )

      // ดีลของฉัน: role=buyer (ที่ฉันเสนอซื้อ) | seller (ที่คนเสนอซื้อของฉัน)
      .get(
        "/deals",
        async ({ query, user }) => {
          const role = query.role ?? "buyer";
          if (role !== "buyer" && role !== "seller") throw new AppError(422, "VALIDATION", "role ต้องเป็น buyer หรือ seller");
          const me = user!.id;
          const rows = Array.from(await sql`
            SELECT d.id, d.status, d.meetup, d.listing_id, d.buyer_id, d.created_at, d.updated_at, d.completed_at,
                   l.title, l.price, l.status AS listing_status, l.seller_id,
                   (SELECT image_key FROM listing_images i WHERE i.listing_id = l.id ORDER BY position LIMIT 1) AS cover,
                   c.id AS conversation_id, b.display_name AS buyer_name, s.display_name AS seller_name
            FROM deals d
            JOIN listings l ON l.id = d.listing_id
            JOIN users b ON b.id = d.buyer_id
            JOIN users s ON s.id = l.seller_id
            LEFT JOIN conversations c ON c.listing_id = d.listing_id AND c.buyer_id = d.buyer_id
            WHERE ${role === "buyer" ? sql`d.buyer_id = ${me}` : sql`l.seller_id = ${me}`}
            ORDER BY d.updated_at DESC
            LIMIT 200
          `);
          return rows.map((r: any) => ({
            id: r.id,
            status: r.status,
            meetup: r.meetup,
            conversationId: r.conversation_id,
            createdAt: r.created_at,
            updatedAt: r.updated_at,
            completedAt: r.completed_at,
            listing: { id: r.listing_id, title: r.title, price: r.price, status: r.listing_status, cover: r.cover ? imageUrl(r.cover) : null },
            other: role === "buyer" ? { id: r.seller_id, displayName: r.seller_name } : { id: r.buyer_id, displayName: r.buyer_name },
          }));
        },
        { query: t.Object({ role: t.Optional(t.String()) }) },
      );
  });
