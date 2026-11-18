import { Elysia } from "elysia";
import { sql } from "../db";
import { forbidden, unauthenticated } from "../errors";
import { verifyToken } from "./token";

export type AuthUser = {
  id: string;
  displayName: string;
  isVerified: boolean;
  isAdmin: boolean;
  isBanned: boolean;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * ถอด Bearer token → โหลดผู้ใช้จากฐานข้อมูล "ทุกคำขอ" (ไม่เชื่อสิทธิ์ที่ฝังใน token)
 * เพื่อให้การระงับบัญชีหรืออนุมัติการยืนยันมีผลทันที ไม่ต้องรอ token หมดอายุ
 * ใส่ `user` ให้ทุก route: ไม่มี token / token เสีย / บัญชีถูกลบ → null
 */
export const auth = new Elysia({ name: "auth" }).resolve(
  { as: "global" },
  async ({ headers }): Promise<{ user: AuthUser | null }> => {
    const header = headers.authorization;
    if (!header?.startsWith("Bearer ")) return { user: null };

    const id = await verifyToken(header.slice(7));
    if (!id || !UUID.test(id)) return { user: null };

    const [row] = await sql`
      SELECT id, display_name, is_verified, is_admin, is_banned
      FROM users
      WHERE id = ${id} AND deleted_at IS NULL
    `;
    if (!row) return { user: null };

    return {
      user: {
        id: row.id,
        displayName: row.display_name,
        isVerified: row.is_verified,
        isAdmin: row.is_admin,
        isBanned: row.is_banned,
      },
    };
  },
);

type WithUser = { user: AuthUser | null };

/** ต้องมี token ที่ใช้ได้ และบัญชีไม่ถูกระงับ (BR15, BR17) */
export function requireAuth({ user }: WithUser): void {
  if (!user) throw unauthenticated();
  if (user.isBanned) throw forbidden("BANNED", "บัญชีของคุณถูกระงับ");
}

/** ต้องผ่านการยืนยันตัวตนแล้วด้วย (BR01) — ใช้กับ endpoint ส่วนใหญ่ */
export function requireVerified(ctx: WithUser): void {
  requireAuth(ctx);
  if (!ctx.user!.isVerified) {
    throw forbidden("NOT_VERIFIED", "บัญชียังไม่ผ่านการยืนยันตัวตน");
  }
}

/** เฉพาะผู้ดูแล */
export function requireAdmin(ctx: WithUser): void {
  requireVerified(ctx);
  if (!ctx.user!.isAdmin) throw forbidden();
}
