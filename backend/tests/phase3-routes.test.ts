import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { rm } from "node:fs/promises";
import { Elysia } from "elysia";
import { app } from "../src/app";
import { errorHandler } from "../src/errors";
import { signToken } from "../src/auth/token";
import { sql } from "../src/db";
import { pathOf } from "../src/identity/storage";
import { purgeDecidedImages } from "../src/jobs/purge-id-images";
import { authRoutes, loginLimiter } from "../src/routes/auth";
import { withChecksum } from "./helpers";

// ต้องมี .env และฐานข้อมูลที่ migrate แล้ว — ผู้ใช้/คำขอ/ไฟล์ที่สร้างในเทสต์ถูกลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);
const email = (n: string) => `t-${tag}-${n}@example.test`;
const PASSWORD = "correct-horse-battery";

type Json = Record<string, any>;

async function call(method: string, path: string, opts: { token?: string; json?: unknown; form?: FormData } = {}) {
  const headers: Record<string, string> = {};
  if (opts.token) headers.authorization = `Bearer ${opts.token}`;
  let body: RequestInit["body"];
  if (opts.json !== undefined) {
    headers["content-type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.form) {
    body = opts.form;
  }
  const res = await app.handle(new Request(`http://localhost${path}`, { method, headers, body }));
  const type = res.headers.get("content-type") ?? "";
  const data: any = type.includes("json") ? await res.json() : null;
  return { res, status: res.status, data: data as Json };
}

// JPEG/PNG ปลอมแต่ไบต์แรกถูกต้อง
const jpeg = (marker: number) => new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xe0, marker, 1, 2, 3])], { type: "image/jpeg" });
const photoForm = (marker = 1) => {
  const f = new FormData();
  f.set("card", jpeg(marker), "card.jpg");
  f.set("selfie", jpeg(marker + 1), "selfie.jpg");
  return f;
};

let outsider: { token: string; id: string };
let admin: { token: string; id: string };
let other: { token: string; id: string };

async function register(name: string) {
  const { status, data } = await call("POST", "/auth/register", {
    json: { email: email(name), password: PASSWORD, displayName: `ทดสอบ ${name}` },
  });
  expect(status).toBe(200);
  return { token: data.token as string, id: data.user.id as string };
}

beforeAll(async () => {
  outsider = await register("outsider");
  other = await register("other");
  admin = await register("admin");
  await sql`UPDATE users SET is_admin = true, is_verified = true WHERE id = ${admin.id}`;
});

afterAll(async () => {
  const rows = await sql`SELECT id, card_image_key FROM id_card_requests WHERE user_id IN (SELECT id FROM users WHERE email LIKE ${`t-${tag}-%`})`;
  for (const r of rows) await rm(pathOf(`verifications/${r.id}`), { recursive: true, force: true });
  await sql`DELETE FROM id_card_requests WHERE user_id IN (SELECT id FROM users WHERE email LIKE ${`t-${tag}-%`})`;
  await sql`DELETE FROM users WHERE email LIKE ${`t-${tag}-%`}`;
});

describe("POST /auth/register", () => {
  test("สร้างบัญชีที่ยังไม่ยืนยัน ได้ token + โปรไฟล์ และเก็บรหัสผ่านเป็น argon2id", async () => {
    const { data } = await call("POST", "/auth/login", { json: { email: email("outsider"), password: PASSWORD } });
    expect(data.user).toMatchObject({ displayName: "ทดสอบ outsider", isVerified: false, isAdmin: false, verification: null });
    const [row] = await sql`SELECT password_hash FROM users WHERE id = ${outsider.id}`;
    expect(row.password_hash).toStartWith("$argon2id$");
    expect(row.password_hash).not.toContain(PASSWORD);
  });

  test("อีเมลซ้ำ (ไม่สนตัวพิมพ์) → 409", async () => {
    const { status } = await call("POST", "/auth/register", {
      json: { email: email("OUTSIDER").toUpperCase(), password: PASSWORD, displayName: "x" },
    });
    expect(status).toBe(409);
  });

  test.each([
    ["อีเมลผิดรูปแบบ", { email: "nope", password: PASSWORD, displayName: "x" }],
    ["รหัสผ่านสั้นไป", { email: email("v1"), password: "short", displayName: "x" }],
    ["ชื่อว่าง", { email: email("v2"), password: PASSWORD, displayName: "   " }],
    ["ชื่อยาวเกิน 50", { email: email("v3"), password: PASSWORD, displayName: "x".repeat(51) }],
  ])("%s → 422", async (_name, json) => {
    const { status, data } = await call("POST", "/auth/register", { json });
    expect(status).toBe(422);
    expect(data.code).toBe("VALIDATION");
  });
});

describe("POST /auth/login", () => {
  test("รหัสผิดกับอีเมลที่ไม่มี ตอบเหมือนกัน (ไม่บอกว่าอีเมลไหนมีในระบบ)", async () => {
    const wrongPw = await call("POST", "/auth/login", { json: { email: email("outsider"), password: "wrong-password" } });
    const noUser = await call("POST", "/auth/login", { json: { email: email("ghost"), password: "wrong-password" } });
    expect(wrongPw.status).toBe(401);
    expect(noUser.status).toBe(401);
    expect(wrongPw.data).toEqual(noUser.data);
    expect(wrongPw.data.code).toBe("INVALID_CREDENTIALS");
  });

  test("ลองผิดครบ 10 ครั้ง → 429 แม้ครั้งถัดไปจะใส่รหัสถูก", async () => {
    const e = email("victim");
    await register("victim");
    loginLimiter.reset(`login:${e}`);
    for (let i = 0; i < 10; i++) await call("POST", "/auth/login", { json: { email: e, password: "wrong-password" } });
    const { status, data } = await call("POST", "/auth/login", { json: { email: e, password: PASSWORD } });
    expect(status).toBe(429);
    expect(data.code).toBe("TOO_MANY_ATTEMPTS");
    loginLimiter.reset(`login:${e}`);
  });

  test("บัญชีถูกระงับ → 403 BANNED", async () => {
    const b = await register("banned");
    await sql`UPDATE users SET is_banned = true WHERE id = ${b.id}`;
    const { status, data } = await call("POST", "/auth/login", { json: { email: email("banned"), password: PASSWORD } });
    expect(status).toBe(403);
    expect(data.code).toBe("BANNED");
  });
});

describe("โปรไฟล์ /users/me และ /faculties", () => {
  test("ยังไม่ยืนยันก็ดูและแก้โปรไฟล์ตัวเองได้ แต่ไม่คืนข้อมูลลับ", async () => {
    const { status, data } = await call("GET", "/users/me", { token: outsider.token });
    expect(status).toBe(200);
    expect(Object.keys(data).sort()).toEqual(["displayName", "email", "facultyId", "id", "isAdmin", "isKmitl", "isVerified", "verification"]);
  });

  test("PATCH แก้ชื่อและคณะ · คณะที่ไม่มี → 422", async () => {
    const faculties = (await call("GET", "/faculties", { token: outsider.token })).data as unknown as Array<{ id: number }>;
    expect(faculties).toHaveLength(16);
    const ok = await call("PATCH", "/users/me", { token: outsider.token, json: { displayName: " ชื่อใหม่ ", facultyId: faculties[0]!.id } });
    expect(ok.data).toMatchObject({ displayName: "ชื่อใหม่", facultyId: faculties[0]!.id });
    expect((await call("PATCH", "/users/me", { token: outsider.token, json: { facultyId: 9999 } })).status).toBe(422);
    expect((await call("PATCH", "/users/me", { token: outsider.token, json: { displayName: "" } })).status).toBe(422);
  });

  test("ไม่มี token → 401 · GET /users/:id ต้องยืนยันตัวตนก่อน", async () => {
    expect((await call("GET", "/users/me")).status).toBe(401);
    expect((await call("GET", `/users/${admin.id}`, { token: outsider.token })).status).toBe(403);
    const pub = await call("GET", `/users/${admin.id}`, { token: await signToken(admin.id) });
    expect(pub.status).toBe(200);
    // โปรไฟล์สาธารณะ: ชื่อ คณะ และตัวเลขผู้ขาย (เฟส 8) — ห้ามมีอีเมล/รหัส/sub/id_hash
    expect(Object.keys(pub.data).sort()).toEqual(["activeListings", "closeRate", "displayName", "faculty", "id", "isKmitl", "rating", "reviews", "sold"]);
  });
});

describe("ส่งบัตรและผู้ดูแลตรวจ", () => {
  let requestId: string;

  test("ส่งรูป → PENDING · รูปที่เก็บไม่ใช่ไฟล์ต้นฉบับ (เข้ารหัสแล้ว)", async () => {
    const { status, data } = await call("POST", "/verifications", { token: outsider.token, form: photoForm(10) });
    expect(status).toBe(200);
    expect(data.status).toBe("PENDING");
    requestId = data.id;

    const raw = new Uint8Array(await Bun.file(pathOf(`verifications/${requestId}/card.enc`)).arrayBuffer());
    expect(raw.length).toBe(12 + 16 + 8); // iv + tag + ข้อมูล 8 ไบต์
    expect(raw.slice(28, 32)).not.toEqual(new Uint8Array([0xff, 0xd8, 0xff, 0xe0])); // ไม่ใช่ข้อความเปิด

    const me = await call("GET", "/verifications/me", { token: outsider.token });
    expect(me.data).toMatchObject({ id: requestId, status: "PENDING" });
  });

  test("ส่งซ้ำตอนมีคำขอรอตรวจ → 409 · ไฟล์ที่ไม่ใช่รูป → 422", async () => {
    expect((await call("POST", "/verifications", { token: outsider.token, form: photoForm(20) })).status).toBe(409);
    const bad = new FormData();
    bad.set("card", new Blob(["<html>not an image</html>"], { type: "image/jpeg" }), "card.jpg");
    bad.set("selfie", jpeg(1), "selfie.jpg");
    const r = await call("POST", "/verifications", { token: other.token, form: bad });
    expect(r.status).toBe(422);
  });

  test("ผู้ใช้ทั่วไปเรียก /admin/* → 403 · ไม่มี token → 401", async () => {
    expect((await call("GET", "/admin/verifications", { token: outsider.token })).status).toBe(403);
    expect((await call("GET", "/admin/verifications")).status).toBe(401);
  });

  test("ผู้ดูแลเห็นคิวและเปิดรูปได้ (ถอดรหัสได้ไบต์เดิม) · ผู้ใช้อื่นเปิดไม่ได้", async () => {
    const adminToken = await signToken(admin.id);
    const queue = await call("GET", "/admin/verifications", { token: adminToken });
    expect((queue.data as unknown as Array<{ id: string }>).some((r) => r.id === requestId)).toBe(true);

    const img = await call("GET", `/admin/verifications/${requestId}/images/card`, { token: adminToken });
    expect(img.status).toBe(200);
    expect(img.res.headers.get("content-type")).toBe("image/jpeg");
    expect(img.res.headers.get("cache-control")).toBe("no-store");
    expect(new Uint8Array(await img.res.arrayBuffer())).toEqual(new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 10, 1, 2, 3]));

    expect((await call("GET", `/admin/verifications/${requestId}/images/card`, { token: other.token })).status).toBe(403);
  });

  test("เลขบัตรไม่ถูกต้อง → 422 · ผู้ดูแลตรวจคำขอของตัวเองไม่ได้", async () => {
    const adminToken = await signToken(admin.id);
    expect((await call("POST", `/admin/verifications/${requestId}/approve`, { token: adminToken, json: { nationalId: "1234567890123" } })).status).toBe(422);

    // ผู้ดูแลยังไม่ยืนยันก็ส่งบัตรไม่ได้อยู่แล้ว จึงจำลองด้วยการสลับให้ผู้ดูแลเป็นเจ้าของคำขอ
    const [own] = await sql`
      INSERT INTO id_card_requests (user_id, card_image_key, selfie_image_key) VALUES (${admin.id}, NULL, NULL) RETURNING id`;
    const r = await call("POST", `/admin/verifications/${own.id}/reject`, { token: adminToken, json: { reason: "x" } });
    expect(r.status).toBe(403);
    expect(r.data.code).toBe("OWN_REQUEST");
  });

  test("อนุมัติ → is_verified + เก็บแค่ค่าแฮช (ไม่มีเลขบัตรจริงในฐานข้อมูล) + แจ้งเตือน", async () => {
    const adminToken = await signToken(admin.id);
    const nationalId = withChecksum("110170023070");
    const ok = await call("POST", `/admin/verifications/${requestId}/approve`, { token: adminToken, json: { nationalId: `${nationalId.slice(0, 1)}-${nationalId.slice(1)}` } });
    expect(ok.status).toBe(200);

    const [u] = await sql`SELECT is_verified, id_hash FROM users WHERE id = ${outsider.id}`;
    expect(u.is_verified).toBe(true);
    expect(u.id_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(u.id_hash).not.toContain(nationalId);
    const [n] = await sql`SELECT type FROM notifications WHERE user_id = ${outsider.id}`;
    expect(n.type).toBe("VERIFICATION_APPROVED");

    // ตัดสินซ้ำไม่ได้
    expect((await call("POST", `/admin/verifications/${requestId}/reject`, { token: adminToken, json: { reason: "x" } })).status).toBe(409);
    // บัญชีที่ยืนยันแล้วส่งบัตรอีกไม่ได้
    expect((await call("POST", "/verifications", { token: outsider.token, form: photoForm(30) })).status).toBe(409);
    // ตอนนี้ใช้ endpoint ที่ต้องยืนยันได้แล้ว
    expect((await call("GET", "/categories", { token: outsider.token })).status).toBe(200);
  });

  test("เลขบัตรเดียวกันยืนยันบัญชีที่สองไม่ได้ (กัน 1 คนหลายบัญชี)", async () => {
    const adminToken = await signToken(admin.id);
    const sub = await call("POST", "/verifications", { token: other.token, form: photoForm(40) });
    expect(sub.status).toBe(200);
    const dup = await call("POST", `/admin/verifications/${sub.data.id}/approve`, {
      token: adminToken,
      json: { nationalId: withChecksum("110170023070") },
    });
    expect(dup.status).toBe(409);
    const [u] = await sql`SELECT is_verified FROM users WHERE id = ${other.id}`;
    expect(u.is_verified).toBe(false); // transaction ย้อนกลับ

    // ปฏิเสธพร้อมเหตุผล → ผู้ใช้เห็นเหตุผล และส่งใหม่ได้
    const rej = await call("POST", `/admin/verifications/${sub.data.id}/reject`, { token: adminToken, json: { reason: "รูปไม่ชัด" } });
    expect(rej.status).toBe(200);
    const mine = await call("GET", "/users/me", { token: other.token });
    expect(mine.data.verification).toMatchObject({ status: "REJECTED", rejectReason: "รูปไม่ชัด" });
    expect((await call("POST", "/verifications", { token: other.token, form: photoForm(50) })).status).toBe(200);
  });

  test("งานลบรูป: ยังไม่ครบ 7 วันไม่ลบ · ครบกำหนดแล้วลบไฟล์และเคลียร์คอลัมน์ · คำขอที่ยังรอตรวจไม่ถูกแตะ", async () => {
    const [decided] = await sql`SELECT id, card_image_key FROM id_card_requests WHERE id = ${requestId}`;
    expect(decided.card_image_key).not.toBeNull();
    expect(await purgeDecidedImages()).toBe(0); // เพิ่งตัดสิน (ไฟล์ถูกประทับเวลาเมื่อกี้)

    const future = Date.now() + 8 * 24 * 60 * 60 * 1000; // จำลองว่าผ่านไป 8 วัน
    expect(await purgeDecidedImages(7 * 24 * 60 * 60 * 1000, future)).toBeGreaterThanOrEqual(1);

    const [after] = await sql`SELECT card_image_key, selfie_image_key FROM id_card_requests WHERE id = ${requestId}`;
    expect(after.card_image_key).toBeNull();
    expect(after.selfie_image_key).toBeNull();
    expect(await Bun.file(pathOf(`verifications/${requestId}/card.enc`)).exists()).toBe(false);

    const [pending] = await sql`SELECT card_image_key FROM id_card_requests WHERE user_id = ${other.id} AND status = 'PENDING'`;
    expect(pending.card_image_key).not.toBeNull();

    const gone = await call("GET", `/admin/verifications/${requestId}/images/card`, { token: await signToken(admin.id) });
    expect(gone.status).toBe(404);
  });
});

describe("POST /auth/thaid/mock (เฉพาะโหมดพัฒนา)", () => {
  test("ยืนยันบัญชีทันที · ทำซ้ำ → 409 · ไม่มี token → 401", async () => {
    const u = await register("thaid");
    expect((await call("POST", "/auth/thaid/mock")).status).toBe(401);
    const ok = await call("POST", "/auth/thaid/mock", { token: u.token });
    expect(ok.status).toBe(200);
    expect(ok.data.isVerified).toBe(true);
    expect((await call("POST", "/auth/thaid/mock", { token: u.token })).status).toBe(409);
  });
});

describe("SSO ยังไม่ตั้งค่า", () => {
  test("/auth/kmitl/start → 503 SSO_NOT_CONFIGURED · /auth/exchange ด้วยรหัสมั่ว → 401", async () => {
    // แอปแยกที่ไม่ส่ง SSO เข้า authRoutes (null) — ไม่ขึ้นกับว่า .env ตั้ง SSO จริง/จำลองไว้หรือไม่
    const bare = new Elysia().use(errorHandler).use(authRoutes(null));
    const go = async (method: string, path: string, json?: unknown) => {
      const res = await bare.handle(new Request(`http://localhost${path}`, { method, headers: json ? { "content-type": "application/json" } : {}, body: json ? JSON.stringify(json) : undefined }));
      return { status: res.status, data: await res.json().catch(() => null) as any };
    };
    const start = await go("GET", "/auth/kmitl/start");
    expect(start.status).toBe(503);
    expect(start.data.code).toBe("SSO_NOT_CONFIGURED");
    expect((await go("POST", "/auth/exchange", { code: "nope" })).status).toBe(401);
  });
});
