import { Elysia, t } from "elysia";
import { auth, requireAdmin } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { writeLog } from "../logger";
import { notify } from "../notifications";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * ระงับผู้ใช้ (BR14, BR15): ใช้งานไม่ได้ทันที (auth plugin โหลดผู้ใช้ทุก request) · ประกาศถูกซ่อนจากการค้นหา (ไม่เปลี่ยนสถานะ)
 * ดีลที่ค้างทุกฝั่งถูกยกเลิก (ประกาศที่ถูกจองกลับเป็น กำลังขาย) และอีกฝ่ายได้แจ้งเตือน
 * ระงับตัวเอง/ผู้ดูแลด้วยกันไม่ได้ · ปลดระงับแล้วทุกอย่างกลับมาเอง (ยกเว้นดีลที่ยกเลิกไปแล้ว)
 */
async function ban(targetId: string, adminId: string, reason: string) {
  if (!UUID.test(targetId)) throw notFound("ไม่พบผู้ใช้");
  await sql.begin(async (tx) => {
    const [u] = await tx`SELECT id, is_admin, is_banned FROM users WHERE id = ${targetId} AND deleted_at IS NULL FOR UPDATE`;
    if (!u) throw notFound("ไม่พบผู้ใช้");
    if (u.id === adminId || u.is_admin) throw forbidden("OWN_TARGET", "ระงับตัวเองหรือผู้ดูแลคนอื่นไม่ได้");
    if (u.is_banned) throw conflict("ผู้ใช้นี้ถูกระงับอยู่แล้ว");

    await tx`UPDATE users SET is_banned = true WHERE id = ${targetId}`;

    // ดีลที่ค้าง ทั้งที่เป็นผู้ขายและผู้ซื้อ
    const open = await tx`
      UPDATE deals d SET status = 'CANCELLED', updated_at = now()
      FROM listings l
      WHERE l.id = d.listing_id AND d.status IN ('PENDING', 'ACCEPTED', 'HANDED_OVER')
        AND (l.seller_id = ${targetId} OR d.buyer_id = ${targetId})
      RETURNING d.id, d.buyer_id, l.seller_id, l.id AS listing_id, l.title`;
    for (const d of open) {
      const other = d.seller_id === targetId ? d.buyer_id : d.seller_id;
      await notify(tx, other, "DEAL_CANCELLED", `ดีล "${d.title}" ถูกยกเลิก เพราะอีกฝ่ายถูกระงับบัญชี`, d.id);
    }
    await tx`UPDATE listings SET status = 'AVAILABLE', updated_at = now()
             WHERE status = 'RESERVED' AND id IN ${tx(open.map((d: any) => d.listing_id).concat([adminId]))}
               AND NOT EXISTS (SELECT 1 FROM deals x WHERE x.listing_id = listings.id AND x.status IN ('ACCEPTED', 'HANDED_OVER'))`;
    void writeLog(`${new Date().toISOString()} USER_BAN admin=${adminId} user=${targetId} reason=${JSON.stringify(reason)}`);
  });
  return { ok: true };
}

async function unban(targetId: string, adminId: string) {
  if (!UUID.test(targetId)) throw notFound("ไม่พบผู้ใช้");
  const r = await sql`UPDATE users SET is_banned = false WHERE id = ${targetId} AND is_banned AND deleted_at IS NULL RETURNING id`;
  if (r.length === 0) throw conflict("ผู้ใช้นี้ไม่ได้ถูกระงับ");
  void writeLog(`${new Date().toISOString()} USER_UNBAN admin=${adminId} user=${targetId}`);
  return { ok: true };
}

export const adminUserRoutes = new Elysia({ name: "routes-admin-users", prefix: "/admin/users" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app
      .post(
        "/:id/ban",
        ({ params, body, user }) => {
          const reason = body.reason.trim();
          if (reason.length < 1 || reason.length > 300) throw new AppError(422, "VALIDATION", "ต้องระบุเหตุผล (ไม่เกิน 300 ตัวอักษร)");
          return ban(params.id, user!.id, reason);
        },
        { body: t.Object({ reason: t.String() }) },
      )
      .post("/:id/unban", ({ params, user }) => unban(params.id, user!.id))
      // สถานะระงับปัจจุบัน ไว้ให้หน้ารายงานเลือกปุ่มระงับ/ปลดระงับ
      .get("/:id", async ({ params }) => {
        if (!UUID.test(params.id)) throw notFound("ไม่พบผู้ใช้");
        const [u] = await sql`SELECT id, display_name, is_banned, is_admin FROM users WHERE id = ${params.id} AND deleted_at IS NULL`;
        if (!u) throw notFound("ไม่พบผู้ใช้");
        return { id: u.id as string, displayName: u.display_name as string, isBanned: u.is_banned as boolean, isAdmin: u.is_admin as boolean };
      }),
  );
