import { Elysia, t } from "elysia";
import { auth, requireAuth } from "../auth/plugin";
import { sql } from "../db";
import { AppError, conflict, forbidden } from "../errors";
import { removeKeys } from "../identity/storage";
import { removeListingImages } from "../listings/storage";
import { FailureLimiter } from "../lib/rate-limit";
import { writeLog } from "../logger";

/** ดาวน์โหลดข้อมูลตัวเอง (PDPA ม.30–31): ไม่เกิน 5 ครั้งต่อชั่วโมงต่อคน — ข้อมูลเยอะ ไม่ควรถูกเรียกรัว */
export const exportLimiter = new FailureLimiter(5, 60 * 60 * 1000);

const iso = (v: unknown) => (v ? new Date(v as string).toISOString() : null);

/** ข้อมูลทั้งหมดที่ระบบเก็บเกี่ยวกับ me ในรูป JSON — ไม่มีรหัสผ่าน/แฮช/รูปบัตร และไม่มีข้อมูลระบุตัวตนของคนอื่น (อีกฝ่ายเห็นแค่ชื่อที่แสดง) */
export async function buildExport(me: string) {
  const [u] = await sql`
    SELECT u.id, u.email, u.kmitl_sub, u.display_name, f.name_th AS faculty, u.is_verified, u.is_admin, u.created_at,
           (u.id_hash IS NOT NULL) AS has_id_hash
    FROM users u LEFT JOIN faculties f ON f.id = u.faculty_id WHERE u.id = ${me}`;

  const verifications = await sql`
    SELECT status, reject_reason, created_at, (card_image_key IS NOT NULL OR selfie_image_key IS NOT NULL) AS images_kept
    FROM id_card_requests WHERE user_id = ${me} ORDER BY created_at`;
  const listings = await sql`
    SELECT l.id, l.title, l.description, l.price, l.status, l.status_reason, l.created_at, l.updated_at,
           (SELECT count(*)::int FROM listing_images i WHERE i.listing_id = l.id) AS images
    FROM listings l WHERE l.seller_id = ${me} ORDER BY l.created_at`;
  const favorites = await sql`
    SELECT l.id AS listing_id, l.title, fv.created_at FROM favorites fv JOIN listings l ON l.id = fv.listing_id
    WHERE fv.user_id = ${me} ORDER BY fv.created_at`;
  const deals = await sql`
    SELECT d.id, d.status, d.meetup, d.created_at, d.completed_at, l.title,
           CASE WHEN d.buyer_id = ${me} THEN 'BUYER' ELSE 'SELLER' END AS role,
           CASE WHEN d.buyer_id = ${me} THEN s.display_name ELSE b.display_name END AS other_name
    FROM deals d JOIN listings l ON l.id = d.listing_id JOIN users b ON b.id = d.buyer_id JOIN users s ON s.id = l.seller_id
    WHERE d.buyer_id = ${me} OR l.seller_id = ${me} ORDER BY d.created_at`;
  const rooms = await sql`
    SELECT c.id, c.created_at, l.title AS listing_title,
           CASE WHEN c.buyer_id = ${me} THEN 'BUYER' ELSE 'SELLER' END AS role,
           CASE WHEN c.buyer_id = ${me} THEN s.display_name ELSE b.display_name END AS other_name
    FROM conversations c JOIN listings l ON l.id = c.listing_id JOIN users b ON b.id = c.buyer_id JOIN users s ON s.id = l.seller_id
    WHERE c.buyer_id = ${me} OR l.seller_id = ${me} ORDER BY c.created_at`;
  const messages = rooms.length === 0 ? [] : await sql`
    SELECT m.conversation_id, m.sender_id, m.body, m.created_at FROM messages m
    WHERE m.conversation_id IN ${sql(rooms.map((r: any) => r.id))} ORDER BY m.id`;
  const reviewsGiven = await sql`
    SELECT r.rating, r.comment, r.created_at, l.title FROM reviews r JOIN deals d ON d.id = r.deal_id JOIN listings l ON l.id = d.listing_id
    WHERE d.buyer_id = ${me} ORDER BY r.created_at`;
  const reviewsReceived = await sql`
    SELECT r.rating, r.comment, r.created_at, l.title FROM reviews r JOIN deals d ON d.id = r.deal_id JOIN listings l ON l.id = d.listing_id
    WHERE l.seller_id = ${me} ORDER BY r.created_at`;
  const reports = await sql`
    SELECT target_type, reason, detail, status, admin_note, created_at FROM reports WHERE reporter_id = ${me} ORDER BY created_at`;
  const notifications = await sql`
    SELECT type, body, is_read, created_at FROM notifications WHERE user_id = ${me} ORDER BY id`;

  return {
    exportedAt: new Date().toISOString(),
    note: "ข้อมูลของคุณที่ Campus Market เก็บไว้ · ไม่รวมรหัสผ่าน (เก็บเป็นแฮชถอดกลับไม่ได้) รูปบัตร/เซลฟี (ลบหลังตรวจ 7 วัน) และค่าแฮชเลขบัตร",
    account: {
      id: u.id as string,
      loginMethod: u.kmitl_sub ? "KMITL_SSO" : "EMAIL",
      email: (u.email ?? null) as string | null,
      kmitlSub: (u.kmitl_sub ?? null) as string | null,
      displayName: u.display_name as string,
      faculty: (u.faculty ?? null) as string | null,
      isVerified: u.is_verified as boolean,
      isAdmin: u.is_admin as boolean,
      hasIdCardHash: u.has_id_hash as boolean,
      createdAt: iso(u.created_at),
    },
    verifications: verifications.map((v: any) => ({ status: v.status, rejectReason: v.reject_reason ?? null, imagesStillStored: v.images_kept, createdAt: iso(v.created_at) })),
    listings: listings.map((l: any) => ({ id: l.id, title: l.title, description: l.description, price: l.price, status: l.status, statusReason: l.status_reason ?? null, images: l.images, createdAt: iso(l.created_at), updatedAt: iso(l.updated_at) })),
    favorites: favorites.map((f: any) => ({ listingId: f.listing_id, title: f.title, createdAt: iso(f.created_at) })),
    deals: deals.map((d: any) => ({ id: d.id, role: d.role, status: d.status, listingTitle: d.title, otherParty: d.other_name, meetup: d.meetup ?? null, createdAt: iso(d.created_at), completedAt: iso(d.completed_at) })),
    conversations: rooms.map((r: any) => ({
      id: r.id, role: r.role, listingTitle: r.listing_title, otherParty: r.other_name, createdAt: iso(r.created_at),
      messages: messages
        .filter((m: any) => m.conversation_id === r.id)
        .map((m: any) => ({ from: m.sender_id === null ? "SYSTEM" : m.sender_id === me ? "ME" : "OTHER", body: m.body, createdAt: iso(m.created_at) })),
    })),
    reviewsGiven: reviewsGiven.map((r: any) => ({ rating: r.rating, comment: r.comment ?? null, listingTitle: r.title, createdAt: iso(r.created_at) })),
    reviewsReceived: reviewsReceived.map((r: any) => ({ rating: r.rating, comment: r.comment ?? null, listingTitle: r.title, createdAt: iso(r.created_at) })),
    reportsFiled: reports.map((r: any) => ({ targetType: r.target_type, reason: r.reason, detail: r.detail ?? null, status: r.status, adminNote: r.admin_note ?? null, createdAt: iso(r.created_at) })),
    notifications: notifications.map((n: any) => ({ type: n.type, body: n.body, isRead: n.is_read, createdAt: iso(n.created_at) })),
  };
}

export const DELETED_NAME = "ผู้ใช้ที่ลบบัญชีแล้ว";

/**
 * ลบบัญชี (PDPA ม.33, BR21): ห้ามถ้ามีดีลที่ยังไม่จบ (รอตอบ/ตกลงแล้ว/ส่งมอบแล้ว) · ผู้ดูแลลบตัวเองไม่ได้
 * ล้างข้อมูลระบุตัวตนในแถว users (อีเมล รหัสผ่านแฮช sub แฮชบัตร ชื่อ คณะ) แล้วตั้ง deleted_at — แถวยังอยู่เพื่อให้ดีลเก่า/รีวิว/ข้อความของอีกฝ่ายไม่พัง
 * ประกาศที่ยังไม่ขายถูกยกเลิก ลบรูปและรายละเอียด · ลบรายการโปรด แจ้งเตือน คำขอยืนยันตัวตนพร้อมไฟล์รูป
 * ข้อความแชตที่เคยส่งยังอยู่ให้อีกฝ่าย (ฐานสัญญา เก็บ 1 ปี) แต่ผู้ส่งแสดงเป็น "ผู้ใช้ที่ลบบัญชีแล้ว"
 */
export async function deleteAccount(me: string): Promise<void> {
  const keys: Array<string | null> = [];
  const dropImagesOf: string[] = [];

  await sql.begin(async (tx) => {
    const [u] = await tx`SELECT id, is_admin FROM users WHERE id = ${me} AND deleted_at IS NULL FOR UPDATE`;
    if (!u) throw new AppError(404, "NOT_FOUND", "ไม่พบบัญชี");
    if (u.is_admin) throw forbidden("ADMIN_ACCOUNT", "ผู้ดูแลระบบลบบัญชีตัวเองไม่ได้ (ให้ผู้ดูแลคนอื่นปรับสิทธิ์ก่อน)");

    const [open] = await tx`
      SELECT count(*)::int AS n FROM deals d JOIN listings l ON l.id = d.listing_id
      WHERE d.status IN ('PENDING', 'ACCEPTED', 'HANDED_OVER') AND (d.buyer_id = ${me} OR l.seller_id = ${me})`;
    if (open.n > 0) throw conflict(`ลบบัญชีไม่ได้ เพราะยังมีดีลที่ไม่จบ ${open.n} รายการ (ปิดหรือยกเลิกดีลก่อน)`);

    const gone = await tx`
      UPDATE listings SET status = 'CANCELLED', status_reason = 'ผู้ขายลบบัญชี', description = '', updated_at = now()
      WHERE seller_id = ${me} AND status <> 'SOLD' RETURNING id`;
    for (const l of gone) {
      await tx`DELETE FROM listing_images WHERE listing_id = ${l.id}`;
      dropImagesOf.push(l.id);
    }

    const reqs = await tx`DELETE FROM id_card_requests WHERE user_id = ${me} RETURNING card_image_key, selfie_image_key`;
    for (const r of reqs) keys.push(r.card_image_key, r.selfie_image_key);
    await tx`DELETE FROM favorites WHERE user_id = ${me}`;
    await tx`DELETE FROM notifications WHERE user_id = ${me}`;

    await tx`
      UPDATE users SET email = NULL, password_hash = NULL, kmitl_sub = NULL, id_hash = NULL,
                       display_name = ${DELETED_NAME}, faculty_id = NULL, is_verified = false, deleted_at = now()
      WHERE id = ${me}`;
  });

  // ไฟล์ลบหลัง commit (ลบไม่สำเร็จก็ไม่ย้อนบัญชี — งานล้างรูปบัตรรายวันจะจัดการซ้ำ)
  await removeKeys(keys).catch(() => {});
  for (const id of dropImagesOf) await removeListingImages(id).catch(() => {});
  void writeLog(`${new Date().toISOString()} ACCOUNT_DELETED user=${me}`);
}

export const privacyRoutes = new Elysia({ name: "routes-privacy" })
  .use(auth)
  .guard({ beforeHandle: requireAuth }, (app) =>
    app
      .get("/me/export", async ({ user, set }) => {
        const key = user!.id;
        if (exportLimiter.isBlocked(key)) throw new AppError(429, "RATE_LIMITED", "ดาวน์โหลดข้อมูลได้ไม่เกิน 5 ครั้งต่อชั่วโมง ลองใหม่ภายหลัง");
        exportLimiter.recordFailure(key); // นับทุกครั้งที่เรียก
        set.headers["content-disposition"] = 'attachment; filename="campus-market-my-data.json"';
        set.headers["cache-control"] = "no-store";
        return await buildExport(user!.id);
      })
      // confirm ต้องเป็น true ทุกครั้ง: กันกดพลาดและกันคำขอลอย ๆ
      .delete("/me", async ({ user }) => {
        await deleteAccount(user!.id);
        return { ok: true };
      }, { body: t.Object({ confirm: t.Literal(true) }) }),
  );
