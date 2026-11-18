import { sql } from "../db";

/** ข้อมูลผู้ใช้ของตัวเอง (GET /users/me, ผลล็อกอิน) — ไม่มี password_hash, id_hash, kmitl_sub */
export type MeDto = {
  id: string;
  displayName: string;
  email: string | null;
  facultyId: number | null;
  isVerified: boolean;
  isAdmin: boolean;
  /** เข้าด้วย KMITL SSO (บัญชี สจล. อาจมีอีเมลสถาบันด้วย จึงดูจาก email อย่างเดียวไม่ได้) */
  isKmitl: boolean;
  /** คำขอยืนยันล่าสุด (บัตรประชาชน) — null = ยังไม่เคยส่ง */
  verification: { id: string; status: string; rejectReason: string | null } | null;
};

export async function loadMe(userId: string): Promise<MeDto | null> {
  const [u] = await sql`
    SELECT id, display_name, email, faculty_id, is_verified, is_admin, (kmitl_sub IS NOT NULL) AS is_kmitl
    FROM users WHERE id = ${userId} AND deleted_at IS NULL
  `;
  if (!u) return null;
  const [v] = await sql`
    SELECT id, status, reject_reason FROM id_card_requests
    WHERE user_id = ${userId} ORDER BY created_at DESC LIMIT 1
  `;
  return {
    id: u.id,
    displayName: u.display_name,
    email: u.email,
    facultyId: u.faculty_id,
    isVerified: u.is_verified,
    isAdmin: u.is_admin,
    isKmitl: u.is_kmitl,
    verification: v ? { id: v.id, status: v.status, rejectReason: v.reject_reason } : null,
  };
}
