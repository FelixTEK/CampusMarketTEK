import { Elysia, t } from "elysia";
import { auth, requireAuth } from "../auth/plugin";
import { signToken } from "../auth/token";
import { sql } from "../db";
import { env } from "../env";
import { AppError, conflict, notFound } from "../errors";
import { hashNationalId } from "../identity/crypto";
import { KmitlLoginError, type KmitlService } from "../kmitl/service";
import { FailureLimiter } from "../lib/rate-limit";
import { loadMe } from "../users/dto";

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const invalid = (message: string) => new AppError(422, "VALIDATION", message);
const ssoOff = () =>
  new AppError(503, "SSO_NOT_CONFIGURED", "ยังไม่ได้เปิดใช้การเข้าสู่ระบบด้วยบัญชี สจล.");

// ลองรหัสผิด 10 ครั้งต่อ 15 นาทีต่ออีเมล แล้วโดนบล็อกชั่วคราว
export const loginLimiter = new FailureLimiter(10, 15 * 60_000);

// แฮชหลอกไว้เทียบเวลาตอนไม่พบอีเมล — กันเดาว่าอีเมลไหนมีในระบบจากเวลาตอบ
const dummyHash = await Bun.password.hash("dummy-password-for-timing", "argon2id");

async function sessionFor(userId: string) {
  return { token: await signToken(userId), user: await loadMe(userId) };
}

/**
 * /auth/* — ทางเข้าระบบ (ไม่ต้องมี token ยกเว้น thaid/mock)
 *   คนนอก: register / login ด้วยอีเมล + รหัสผ่าน
 *   คน สจล.: kmitl/start → callback → exchange (kmitl = null ถ้ายังไม่ตั้งค่า SSO)
 */
export const authRoutes = (kmitl: KmitlService | null) =>
  new Elysia({ name: "routes-auth", prefix: "/auth" })
    .use(auth)

    // แอปถามก่อนว่าช่องทางไหนเปิดอยู่ (SSO ต้องตั้งค่า client · ThaID จำลองมีเฉพาะโหมดพัฒนา)
    .get("/config", () => ({ kmitlSso: kmitl !== null, thaidMock: !env.isProduction }))

    .post(
      "/register",
      async ({ body }) => {
        const email = body.email.trim().toLowerCase();
        const displayName = body.displayName.trim();
        if (email.length > 254 || !EMAIL.test(email)) throw invalid("รูปแบบอีเมลไม่ถูกต้อง");
        // อีเมลของ สจล. ผูกกับบัญชีที่เข้าด้วย SSO เท่านั้น (กันคนนอกจองอีเมลของนักศึกษาไว้ก่อน)
        if (/@([a-z0-9-]+\.)*kmitl\.ac\.th$/.test(email)) throw invalid("อีเมล สจล. ใช้สมัครไม่ได้ กรุณาเข้าสู่ระบบด้วยปุ่ม \"เข้าสู่ระบบด้วยบัญชี KMITL\"");
        if (body.password.length < 8 || body.password.length > 128)
          throw invalid("รหัสผ่านต้องยาว 8–128 ตัวอักษร");
        if (displayName.length < 1 || displayName.length > 50)
          throw invalid("ชื่อที่แสดงต้องยาว 1–50 ตัวอักษร");

        const hash = await Bun.password.hash(body.password, "argon2id");
        try {
          const [row] = await sql`
            INSERT INTO users (email, password_hash, display_name)
            VALUES (${email}, ${hash}, ${displayName})
            RETURNING id
          `;
          return await sessionFor(row.id);
        } catch (e) {
          // 23505 = unique_violation (อีเมลซ้ำ)
          if ((e as { errno?: string }).errno === "23505") throw conflict("อีเมลนี้ถูกใช้สมัครแล้ว");
          throw e;
        }
      },
      { body: t.Object({ email: t.String(), password: t.String(), displayName: t.String() }) },
    )

    .post(
      "/login",
      async ({ body }) => {
        const email = body.email.trim().toLowerCase();
        const key = `login:${email}`;
        if (loginLimiter.isBlocked(key))
          throw new AppError(429, "TOO_MANY_ATTEMPTS", "ลองผิดหลายครั้งเกินไป กรุณารอ 15 นาทีแล้วลองใหม่");

        const [user] = await sql`
          SELECT id, password_hash, is_banned FROM users
          WHERE email = ${email} AND deleted_at IS NULL
        `;
        const ok = await Bun.password.verify(body.password, user?.password_hash ?? dummyHash);
        if (!user || !ok) {
          loginLimiter.recordFailure(key);
          throw new AppError(401, "INVALID_CREDENTIALS", "อีเมลหรือรหัสผ่านไม่ถูกต้อง");
        }
        if (user.is_banned) throw new AppError(403, "BANNED", "บัญชีของคุณถูกระงับ");

        loginLimiter.reset(key);
        return await sessionFor(user.id);
      },
      { body: t.Object({ email: t.String(), password: t.String() }) },
    )

    // ── KMITL SSO ────────────────────────────────────────────
    .get("/kmitl/start", ({ redirect }) => {
      if (!kmitl) throw ssoOff();
      return redirect(kmitl.start());
    })

    .get(
      "/kmitl/callback",
      async ({ query, redirect }) => {
        if (!kmitl) throw ssoOff();
        // ส่งผลกลับแอปผ่าน deep link — ไม่ส่ง token ตรง ๆ (ส่งรหัสครั้งเดียวให้ไปแลกเอง)
        if (query.error || !query.code || !query.state)
          return redirect("campusmarket://auth?error=sso_failed");
        try {
          const code = await kmitl.callback(query.code, query.state);
          return redirect(`campusmarket://auth?code=${encodeURIComponent(code)}`);
        } catch (e) {
          const reason = e instanceof KmitlLoginError ? e.reason : "sso_failed";
          return redirect(`campusmarket://auth?error=${reason}`);
        }
      },
      {
        query: t.Object({
          code: t.Optional(t.String()),
          state: t.Optional(t.String()),
          error: t.Optional(t.String()),
        }),
      },
    )

    .post(
      "/exchange",
      async ({ body }) => {
        const userId = kmitl?.exchange(body.code);
        if (!userId)
          throw new AppError(401, "INVALID_CODE", "รหัสเข้าสู่ระบบไม่ถูกต้องหรือหมดอายุ กรุณาลองใหม่");
        return await sessionFor(userId);
      },
      { body: t.Object({ code: t.String() }) },
    )

    // ── ThaID จำลอง (mockup เปิดเฉพาะตอนพัฒนา) ─────────────────
    .post(
      "/thaid/mock",
      async ({ user }) => {
        if (env.isProduction) throw notFound();
        const me = user!;
        if (me.isVerified) throw conflict("บัญชีนี้ยืนยันตัวตนแล้ว");
        // ค่าแฮชจำลอง ไม่ใช่เลขบัตรจริง (ผูกกับบัญชีนี้บัญชีเดียว)
        await sql`
          UPDATE users SET is_verified = true, id_hash = ${hashNationalId(`thaid-mock:${me.id}`)}
          WHERE id = ${me.id}
        `;
        return await loadMe(me.id);
      },
      { beforeHandle: requireAuth },
    );
