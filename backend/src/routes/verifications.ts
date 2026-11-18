import { Elysia, t } from "elysia";
import { auth, requireAuth } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict } from "../errors";
import { sniffImageType } from "../identity/crypto";
import { removeKeys, saveEncrypted } from "../identity/storage";

const image = t.File({ type: ["image/jpeg", "image/png"], maxSize: "6m" });

/**
 * ส่งบัตรประชาชน + เซลฟีเพื่อยืนยันตัวตน (คนนอก) — ผู้ดูแลตรวจด้วยตา ไม่ใช้ระบบจดจำใบหน้า
 * รูปเข้ารหัสก่อนเก็บ และจะถูกลบ 7 วันหลังตัดสิน
 */
export const verificationRoutes = new Elysia({ name: "routes-verifications" })
  .use(auth)

  .post(
    "/verifications",
    async ({ user, body }) => {
      const me = user!;
      if (me.isVerified) throw conflict("บัญชีนี้ยืนยันตัวตนแล้ว");

      const card = new Uint8Array(await body.card.arrayBuffer());
      const selfie = new Uint8Array(await body.selfie.arrayBuffer());
      // ไม่เชื่อ Content-Type ที่ client ส่ง — ดูไบต์แรกของไฟล์จริง
      if (!sniffImageType(card) || !sniffImageType(selfie))
        throw new AppError(422, "VALIDATION", "ไฟล์ต้องเป็นรูป JPEG หรือ PNG");

      const id = crypto.randomUUID();
      const cardKey = `verifications/${id}/card.enc`;
      const selfieKey = `verifications/${id}/selfie.enc`;

      // เพิ่มแถวก่อนเขียนไฟล์ — ถ้ามีคำขอที่รอตรวจอยู่แล้ว (unique index) จะปฏิเสธโดยยังไม่มีไฟล์ค้าง
      try {
        await sql`
          INSERT INTO id_card_requests (id, user_id, card_image_key, selfie_image_key)
          VALUES (${id}, ${me.id}, ${cardKey}, ${selfieKey})
        `;
      } catch (e) {
        if ((e as { errno?: string }).errno === "23505") throw conflict("มีคำขอที่กำลังรอตรวจอยู่แล้ว");
        throw e;
      }

      try {
        await saveEncrypted(cardKey, card);
        await saveEncrypted(selfieKey, selfie);
      } catch (e) {
        await removeKeys([cardKey, selfieKey]);
        await sql`DELETE FROM id_card_requests WHERE id = ${id}`;
        throw e;
      }
      return { id, status: "PENDING" };
    },
    { beforeHandle: requireAuth, body: t.Object({ card: image, selfie: image }) },
  )

  // คำขอล่าสุดของตัวเอง (หน้าสถานะรอตรวจ / ถูกปฏิเสธ)
  .get(
    "/verifications/me",
    async ({ user }) => {
      const [r] = await sql`
        SELECT id, status, reject_reason, created_at FROM id_card_requests
        WHERE user_id = ${user!.id} ORDER BY created_at DESC LIMIT 1
      `;
      return r
        ? { id: r.id, status: r.status, rejectReason: r.reject_reason, createdAt: r.created_at }
        : null;
    },
    { beforeHandle: requireAuth },
  );
