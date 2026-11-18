import { Elysia, t } from "elysia";
import { auth, requireAdmin } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden, notFound } from "../errors";
import { hashNationalId, isValidThaiNationalId, sniffImageType } from "../identity/crypto";
import { readDecrypted, stampDecided } from "../identity/storage";
import { writeLog } from "../logger";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** ผู้ดูแล: ตรวจคำขอยืนยันตัวตนของคนนอก */
export const adminVerificationRoutes = new Elysia({ name: "routes-admin-verifications", prefix: "/admin/verifications" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app
      // คิวคำขอ (เก่าสุดก่อน) — ค่าเริ่มต้นคือที่รอตรวจ
      .get(
        "/",
        async ({ query }) => {
          const status = query.status ?? "PENDING";
          if (!["PENDING", "APPROVED", "REJECTED"].includes(status))
            throw new AppError(422, "VALIDATION", "สถานะไม่ถูกต้อง");
          const rows = await sql`
            SELECT r.id, r.status, r.created_at, u.display_name
            FROM id_card_requests r JOIN users u ON u.id = r.user_id
            WHERE r.status = ${status}
            ORDER BY r.created_at
            LIMIT 100
          `;
          return rows.map((r: any) => ({
            id: r.id,
            displayName: r.display_name,
            status: r.status,
            createdAt: r.created_at,
          }));
        },
        { query: t.Object({ status: t.Optional(t.String()) }) },
      )

      // รูปบัตร/เซลฟี — ส่งไบต์ที่ถอดรหัสแล้วเฉพาะผู้ดูแลที่ล็อกอิน และเขียน log ทุกครั้ง
      .get("/:id/images/:which", async ({ params, user }) => {
        if (!UUID.test(params.id) || !["card", "selfie"].includes(params.which)) throw notFound();
        const [r] = await sql`
          SELECT card_image_key, selfie_image_key FROM id_card_requests WHERE id = ${params.id}
        `;
        if (!r) throw notFound();
        const key = params.which === "card" ? r.card_image_key : r.selfie_image_key;
        if (!key) throw notFound("รูปถูกลบแล้วตามนโยบายความเป็นส่วนตัว");
        const bytes = await readDecrypted(key);
        if (!bytes) throw notFound("ไม่พบไฟล์รูป");

        void writeLog(
          `${new Date().toISOString()} ID_IMAGE_VIEW admin=${user!.id} request=${params.id} which=${params.which}`,
        );
        return new Response(new Uint8Array(bytes), {
          headers: {
            "content-type": sniffImageType(bytes) ?? "application/octet-stream",
            "cache-control": "no-store",
          },
        });
      })

      // อนุมัติ: ผู้ดูแลกรอกเลขบัตรที่อ่านจากรูป → เก็บเฉพาะค่าแฮช กันเลขเดียวหลายบัญชี
      .post(
        "/:id/approve",
        async ({ params, body, user }) => {
          if (!UUID.test(params.id)) throw notFound();
          const nationalId = body.nationalId.replace(/\D/g, "");
          if (!isValidThaiNationalId(nationalId))
            throw new AppError(422, "VALIDATION", "เลขบัตรประชาชนไม่ถูกต้อง");

          const keys = await sql.begin(async (tx) => {
            const [r] = await tx`
              SELECT id, user_id, status, card_image_key, selfie_image_key
              FROM id_card_requests WHERE id = ${params.id} FOR UPDATE
            `;
            if (!r) throw notFound();
            if (r.status !== "PENDING") throw conflict("คำขอนี้ตัดสินไปแล้ว");
            if (r.user_id === user!.id) throw forbidden("OWN_REQUEST", "ตรวจคำขอของตัวเองไม่ได้");

            try {
              await tx`
                UPDATE users SET is_verified = true, id_hash = ${hashNationalId(nationalId)}
                WHERE id = ${r.user_id}
              `;
            } catch (e) {
              if ((e as { errno?: string }).errno === "23505")
                throw conflict("เลขบัตรนี้ถูกใช้ยืนยันกับบัญชีอื่นแล้ว");
              throw e;
            }
            await tx`
              UPDATE id_card_requests SET status = 'APPROVED', reviewed_by = ${user!.id} WHERE id = ${r.id}
            `;
            await tx`
              INSERT INTO notifications (user_id, type, body, ref_id)
              VALUES (${r.user_id}, 'VERIFICATION_APPROVED', 'ยืนยันตัวตนสำเร็จ เริ่มใช้งานได้เลย', ${r.id})
            `;
            return [r.card_image_key, r.selfie_image_key] as Array<string | null>;
          });
          await stampDecided(keys);
          return { ok: true };
        },
        { body: t.Object({ nationalId: t.String() }) },
      )

      .post(
        "/:id/reject",
        async ({ params, body, user }) => {
          if (!UUID.test(params.id)) throw notFound();
          const reason = body.reason.trim();
          if (reason.length < 1 || reason.length > 300)
            throw new AppError(422, "VALIDATION", "ต้องระบุเหตุผล (ไม่เกิน 300 ตัวอักษร)");

          const keys = await sql.begin(async (tx) => {
            const [r] = await tx`
              SELECT id, user_id, status, card_image_key, selfie_image_key
              FROM id_card_requests WHERE id = ${params.id} FOR UPDATE
            `;
            if (!r) throw notFound();
            if (r.status !== "PENDING") throw conflict("คำขอนี้ตัดสินไปแล้ว");
            if (r.user_id === user!.id) throw forbidden("OWN_REQUEST", "ตรวจคำขอของตัวเองไม่ได้");

            await tx`
              UPDATE id_card_requests
              SET status = 'REJECTED', reject_reason = ${reason}, reviewed_by = ${user!.id}
              WHERE id = ${r.id}
            `;
            await tx`
              INSERT INTO notifications (user_id, type, body, ref_id)
              VALUES (${r.user_id}, 'VERIFICATION_REJECTED', ${`ยืนยันตัวตนไม่ผ่าน: ${reason}`}, ${r.id})
            `;
            return [r.card_image_key, r.selfie_image_key] as Array<string | null>;
          });
          await stampDecided(keys);
          return { ok: true };
        },
        { body: t.Object({ reason: t.String() }) },
      ),
  );
