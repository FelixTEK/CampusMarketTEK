import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { app } from "../src/app";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — สร้างผู้ใช้ทดสอบเองแล้วลบทิ้งตอนจบ
const tag = crypto.randomUUID();
const ids = {} as Record<"verified" | "unverified" | "banned" | "deleted", string>;

async function makeUser(name: keyof typeof ids, flags: { verified?: boolean; banned?: boolean; deleted?: boolean }) {
  const [row] = await sql`
    INSERT INTO users (kmitl_sub, display_name, is_verified, is_banned, deleted_at)
    VALUES (${`test-${tag}-${name}`}, ${name}, ${flags.verified ?? false}, ${flags.banned ?? false},
            ${flags.deleted ? new Date() : null})
    RETURNING id
  `;
  ids[name] = row.id;
}

const get = (path: string, token?: string) =>
  app.handle(
    new Request(`http://localhost${path}`, {
      headers: token ? { authorization: `Bearer ${token}` } : {},
    }),
  );

beforeAll(async () => {
  await makeUser("verified", { verified: true });
  await makeUser("unverified", {});
  await makeUser("banned", { verified: true, banned: true });
  await makeUser("deleted", { verified: true, deleted: true });
});

afterAll(async () => {
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`test-${tag}-%`}`;
});

describe("GET /categories (guard requireVerified)", () => {
  test("ไม่มี token → 401 พร้อม {code, message}", async () => {
    const res = await get("/categories");
    expect(res.status).toBe(401);
    expect(await res.json() as unknown).toEqual({ code: "UNAUTHENTICATED", message: "กรุณาเข้าสู่ระบบ" });
  });

  test("token มั่ว → 401", async () => {
    expect((await get("/categories", "not-a-jwt")).status).toBe(401);
  });

  test("token ลงชื่อด้วยกุญแจอื่น → 401", async () => {
    const { SignJWT } = await import("jose");
    const forged = await new SignJWT({})
      .setProtectedHeader({ alg: "HS256" })
      .setSubject(ids.verified)
      .setExpirationTime("1h")
      .sign(new TextEncoder().encode("another-secret"));
    expect((await get("/categories", forged)).status).toBe(401);
  });

  test("ยังไม่ยืนยันตัวตน → 403 NOT_VERIFIED (BR01)", async () => {
    const res = await get("/categories", await signToken(ids.unverified));
    expect(res.status).toBe(403);
    expect(((await res.json()) as { code: string }).code).toBe("NOT_VERIFIED");
  });

  test("ถูกระงับ → 403 BANNED (BR15)", async () => {
    const res = await get("/categories", await signToken(ids.banned));
    expect(res.status).toBe(403);
    expect(((await res.json()) as { code: string }).code).toBe("BANNED");
  });

  test("บัญชีถูกลบ → 401", async () => {
    expect((await get("/categories", await signToken(ids.deleted))).status).toBe(401);
  });

  test("ยืนยันแล้ว → 200 และหมวดตรงกับตาราง categories", async () => {
    const res = await get("/categories", await signToken(ids.verified));
    expect(res.status).toBe(200);
    const body = (await res.json()) as Array<{ id: number; parentId: number | null; nameTh: string }>;
    const [{ count }] = await sql`SELECT count(*)::int AS count FROM categories`;
    expect(body).toHaveLength(count);
    expect(body[0]?.parentId).toBeNull(); // หมวดหลักมาก่อน
    expect(body.filter((c) => c.parentId === null).length).toBe(3);
  });
});

describe("error handler กลาง", () => {
  test("path ที่ไม่มี → 404 {code, message}", async () => {
    const res = await get("/no-such-route");
    expect(res.status).toBe(404);
    expect(await res.json() as unknown).toEqual({ code: "NOT_FOUND", message: "ไม่พบสิ่งที่ร้องขอ" });
  });
});
