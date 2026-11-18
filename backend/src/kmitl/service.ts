import { createHash, randomBytes } from "node:crypto";
import { sql } from "../db";
import { TtlStore } from "../lib/ttl-store";
import type { KmitlClient } from "./client";
import { ALLOWED_STATUSES, normalizeKmitlEmail, thaiDisplayName } from "./student";

/** เหตุผลที่ล็อกอินด้วยบัญชีสถาบันไม่ผ่าน (ส่งกลับแอปเป็น ?error=...) */
export type KmitlFailure = "not_active_student" | "banned" | "sso_failed";

export class KmitlLoginError extends Error {
  constructor(public readonly reason: KmitlFailure) {
    super(reason);
  }
}

const STUDENT_ID = /^\d{8}$/;
const b64url = (b: Buffer) => b.toString("base64url");

/**
 * flow ล็อกอินด้วย KMITL SSO ฝั่ง backend
 *   start()    → URL ไปหน้าล็อกอินของสถาบัน (เก็บ state/nonce/PKCE verifier ไว้ในหน่วยความจำ 10 นาที)
 *   callback() → แลก code ตรวจ id_token ตรวจสถานะนักศึกษา หา/สร้างบัญชี แล้วออกรหัสครั้งเดียว (60 วินาที)
 *   exchange() → แลกรหัสครั้งเดียวเป็น userId (แอปนำไปขอ JWT)
 */
export type KmitlServiceOptions = {
  /** true = ไม่เรียก Student API และไม่ตรวจสถานะ "เรียน" (โหมดพัฒนาเท่านั้น — ข้ามกฎ BR02) · ได้คณะเป็น null */
  skipStudentCheck?: boolean;
};

export function createKmitlService(client: KmitlClient, now: () => number = Date.now, options: KmitlServiceOptions = {}) {
  const pending = new TtlStore<{ nonce: string; verifier: string }>(now);
  const codes = new TtlStore<string>(now);

  return {
    start(): string {
      const state = b64url(randomBytes(16));
      const nonce = b64url(randomBytes(16));
      const verifier = b64url(randomBytes(32));
      const challenge = b64url(createHash("sha256").update(verifier).digest());
      pending.set(state, { nonce, verifier }, 10 * 60_000);
      return client.authorizeUrl({ state, nonce, challenge });
    },

    async callback(code: string, state: string): Promise<string> {
      const saved = pending.take(state); // ใช้ได้ครั้งเดียว กัน replay และ CSRF
      if (!saved) throw new KmitlLoginError("sso_failed");

      let profile;
      try {
        profile = await client.exchange({ code, verifier: saved.verifier, nonce: saved.nonce });
      } catch {
        throw new KmitlLoginError("sso_failed");
      }

      // นักศึกษา (รหัส 8 หลัก) ต้องมีสถานะ "เรียน" · บุคลากรไม่มีรหัสนักศึกษา ผ่านเมื่อล็อกอินสถาบันสำเร็จ
      let facultyName: string | null = null;
      let studentName: string | null = null;
      if (profile.username && STUDENT_ID.test(profile.username) && !options.skipStudentCheck) {
        let info;
        try {
          info = await client.studentInfo(profile.username);
        } catch {
          throw new KmitlLoginError("sso_failed");
        }
        // ผ่านเฉพาะสถานภาพที่ยังเป็นนักศึกษา (เรียน/เปลี่ยนหลักสูตร/รออนุมัติสำเร็จการศึกษา/โอนย้ายข้ามคณะ/ครบหลักสูตร (ค้างชำระ)/เรียนเกินหลักสูตร)
        if (!info || !ALLOWED_STATUSES.has(info.status)) throw new KmitlLoginError("not_active_student");
        facultyName = info.facultyNameTh;
        studentName = thaiDisplayName(info); // ชื่อที่แสดง = ชื่อ + นามสกุลภาษาไทย (ตัดตั้งแต่ครั้งแรก ผู้ใช้แก้เองได้ภายหลัง)
      }

      const displayName = studentName ?? ((profile.givenName ?? "").trim().slice(0, 50) || "สมาชิก สจล.");
      const [faculty] = facultyName
        ? await sql`
            SELECT id FROM faculties
            WHERE position(name_th IN ${facultyName}) > 0 OR position(${facultyName} IN name_th) > 0
            LIMIT 1`
        : [];

      // หาบัญชีจาก kmitl_sub (บัญชี สจล. เดียว = บัญชีเดียว แม้ล็อกอินหลายเครื่อง — BR03)
      // อีเมลสถาบัน (จาก claim email หรือที่กรอกในหน้าล็อกอินจำลอง) ต้องลงท้าย @kmitl.ac.th และยังไม่ถูกบัญชีอื่นใช้
      // ตาราง users บังคับให้ email คู่กับ password_hash เสมอ (CHECK) บัญชี สจล. จึงได้ hash ของรหัสสุ่มที่ไม่มีใครรู้ — ล็อกอินด้วยรหัสผ่านไม่ได้
      let email = normalizeKmitlEmail(profile.email);
      if (email) {
        const [taken] = await sql`SELECT 1 AS x FROM users WHERE email = ${email} AND kmitl_sub IS DISTINCT FROM ${profile.sub}`;
        if (taken) email = null;
      }
      const lockedHash = email ? await Bun.password.hash(randomBytes(32).toString("hex"), "argon2id") : null;

      const [user] = await sql`
        INSERT INTO users (kmitl_sub, display_name, faculty_id, is_verified, email, password_hash)
        VALUES (${profile.sub}, ${displayName}, ${faculty?.id ?? null}, true, ${email}, ${lockedHash})
        ON CONFLICT (kmitl_sub) DO UPDATE
          SET is_verified = true,
              faculty_id = COALESCE(${faculty?.id ?? null}, users.faculty_id),
              email = COALESCE(users.email, EXCLUDED.email),
              password_hash = COALESCE(users.password_hash, EXCLUDED.password_hash)
        RETURNING id, is_banned
      `;
      if (user.is_banned) throw new KmitlLoginError("banned");

      const oneTime = b64url(randomBytes(24));
      codes.set(oneTime, user.id, 60_000);
      return oneTime;
    },

    /** คืน userId หรือ undefined ถ้ารหัสผิด/หมดอายุ/ถูกใช้ไปแล้ว */
    exchange(oneTimeCode: string): string | undefined {
      return codes.take(oneTimeCode);
    },
  };
}

export type KmitlService = ReturnType<typeof createKmitlService>;
