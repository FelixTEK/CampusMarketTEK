import { Elysia, t } from "elysia";
import { auth, requireAdmin } from "../auth/plugin";
import { sql } from "../db";
import { AppError, forbidden, notFound } from "../errors";
import { LABEL, assertTransition, type ListingStatus } from "../listings/status";
import { notify } from "../notifications";
import { cancelOpenDeals, lockListing } from "./listings";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * ผู้ดูแลเปลี่ยนสถานะประกาศ (BR14, BR23)
 *   return   → ถูกตีกลับ  (ผู้ขายแก้แล้วส่งใหม่ได้)
 *   takedown → ถูกถอด     (กรณีร้ายแรง กู้คืนโดยผู้ดูแลเท่านั้น)
 *   restore  → กลับเป็นกำลังขาย
 * ต้องมีเหตุผล · ตัดสินประกาศของตัวเองไม่ได้ · ดีลที่ค้างถูกยกเลิกและแจ้งผู้เกี่ยวข้อง
 */
async function adminChange(id: string, adminId: string, to: ListingStatus, reason: string | null) {
  if (!UUID.test(id)) throw notFound("ไม่พบประกาศ");
  await sql.begin(async (tx) => {
    const l = await lockListing(tx, id);
    if (l.seller_id === adminId) throw forbidden("OWN_LISTING", "ตัดสินประกาศของตัวเองไม่ได้");
    assertTransition(l.status, to, "admin");

    await tx`
      UPDATE listings SET status = ${to}, status_reason = ${reason}, updated_at = now() WHERE id = ${id}
    `;

    if (to === "RETURNED" || to === "TAKEN_DOWN") {
      // ดีลที่ยังค้าง (รอตอบ / ตกลงแล้ว / ส่งมอบแล้ว) ถูกยกเลิก และผู้ซื้อได้แจ้งเตือน
      await cancelOpenDeals(
        tx, id, ["PENDING", "ACCEPTED", "HANDED_OVER"],
        `ประกาศ "${l.title}" ถูกนำออกโดยผู้ดูแล ดีลของคุณถูกยกเลิก`, "LISTING_UNAVAILABLE",
      );
      await notify(
        tx, l.seller_id,
        to === "RETURNED" ? "LISTING_RETURNED" : "LISTING_TAKEN_DOWN",
        `ประกาศ "${l.title}" ${LABEL[to]}: ${reason}`, id,
      );
    } else {
      await notify(tx, l.seller_id, "LISTING_RESTORED", `ประกาศ "${l.title}" ถูกกู้คืนแล้ว`, id);
    }
  });
  return { ok: true };
}

const reasonBody = t.Object({ reason: t.String() });
const requireReason = (raw: string) => {
  const reason = raw.trim();
  if (reason.length < 1 || reason.length > 300)
    throw new AppError(422, "VALIDATION", "ต้องระบุเหตุผล (ไม่เกิน 300 ตัวอักษร)");
  return reason;
};

export const adminListingRoutes = new Elysia({ name: "routes-admin-listings", prefix: "/admin/listings" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app
      .post("/:id/return", ({ params, body, user }) => adminChange(params.id, user!.id, "RETURNED", requireReason(body.reason)), { body: reasonBody })
      .post("/:id/takedown", ({ params, body, user }) => adminChange(params.id, user!.id, "TAKEN_DOWN", requireReason(body.reason)), { body: reasonBody })
      .post("/:id/restore", ({ params, user }) => adminChange(params.id, user!.id, "AVAILABLE", null)),
  );
