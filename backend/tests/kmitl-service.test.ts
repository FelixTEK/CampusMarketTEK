import { afterAll, describe, expect, test } from "bun:test";
import { sql } from "../src/db";
import type { KmitlClient, KmitlProfile, StudentInfo } from "../src/kmitl/client";
import { KmitlLoginError, createKmitlService } from "../src/kmitl/service";

// ทดสอบ flow ด้วยตัวปลอม (ยังไม่มี SSO client/API key จริง) — สร้างผู้ใช้ทดสอบแล้วลบทิ้งตอนจบ
const tag = crypto.randomUUID().slice(0, 8);
const sub = (n: string) => `test-kmitl-${tag}-${n}`;

function fakeClient(profile: KmitlProfile, student: StudentInfo | null): KmitlClient & { calls: string[] } {
  const calls: string[] = [];
  return {
    calls,
    authorizeUrl: ({ state, challenge }) => `https://sso.example/auth?state=${state}&code_challenge=${challenge}`,
    exchange: async ({ code }) => {
      calls.push(`exchange:${code}`);
      if (code === "bad") throw new Error("token endpoint 400");
      return profile;
    },
    studentInfo: async (id) => {
      calls.push(`student:${id}`);
      return student;
    },
  };
}

/** ข้อมูลนักศึกษาตามรูปแบบ Student API (ฟิลด์ที่ไม่เกี่ยวกับเทสต์ว่าง) */
const stu = (status: string, facultyNameTh: string | null = null, first = "ทดสอบ", last = "ระบบ"): StudentInfo => ({
  studentId: "67050514", prenameTh: null, firstNameTh: first, lastNameTh: last, prenameEn: null, firstNameEn: null, lastNameEn: null,
  status, facultyNameTh, facultyNameEn: null, curriculumNameTh: null, curriculumNameEn: null, level: null,
});

const stateOf = (url: string) => new URL(url).searchParams.get("state")!;

afterAll(async () => {
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`test-kmitl-${tag}-%`}`;
});

describe("KMITL SSO service", () => {
  test("นักศึกษาสถานะเรียน → ได้บัญชีที่ยืนยันแล้ว ใช้รหัสครั้งเดียวแลก userId", async () => {
    const client = fakeClient(
      { sub: sub("a"), username: "67050514", givenName: "มิ้นท์" },
      stu("เรียน", "คณะวิทยาศาสตร์", "มิ้นท์", "ทดสอบ"),
    );
    const svc = createKmitlService(client);

    const oneTime = await svc.callback("good", stateOf(svc.start()));
    const userId = svc.exchange(oneTime);
    expect(userId).toBeDefined();
    expect(svc.exchange(oneTime)).toBeUndefined(); // ใช้ซ้ำไม่ได้

    const [u] = await sql`SELECT display_name, is_verified, faculty_id, email FROM users WHERE id = ${userId!}`;
    expect(u.display_name).toBe("มิ้นท์ ทดสอบ"); // ชื่อ + นามสกุลภาษาไทยจาก Student API
    expect(u.is_verified).toBe(true);
    expect(u.email).toBeNull();
    expect(u.faculty_id).not.toBeNull(); // จับคู่ "คณะวิทยาศาสตร์" กับตาราง faculties ได้
    // ไม่เก็บรหัสนักศึกษาที่ไหนเลย: ไม่มีคอลัมน์ให้เก็บ และชื่อที่แสดงไม่ใช่รหัส
    expect(u.display_name).not.toContain("67050514"); // รหัสนักศึกษาไม่ปนในชื่อ
  });

  test("ล็อกอินซ้ำด้วยบัญชีเดิม (คนละเครื่อง) → ได้บัญชีเดิม ไม่สร้างใหม่ (BR03)", async () => {
    const client = fakeClient({ sub: sub("b"), username: "67050515", givenName: "เจ" }, stu("เรียน"));
    const svc = createKmitlService(client);
    const id1 = svc.exchange(await svc.callback("good", stateOf(svc.start())));
    const id2 = svc.exchange(await svc.callback("good", stateOf(svc.start())));
    expect(id1).toBe(id2);
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM users WHERE kmitl_sub = ${sub("b")}`;
    expect(n).toBe(1);
  });

  test("สถานภาพนอกรายการที่ผ่าน (เช่น พ้นสภาพ) → ปฏิเสธและไม่สร้างบัญชี (BR02)", async () => {
    const client = fakeClient({ sub: sub("c"), username: "60010001", givenName: "x" }, stu("พ้นสภาพ"));
    const svc = createKmitlService(client);
    await expect(svc.callback("good", stateOf(svc.start()))).rejects.toMatchObject({ reason: "not_active_student" });
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM users WHERE kmitl_sub = ${sub("c")}`;
    expect(n).toBe(0);
  });

  test("ไม่พบนักศึกษาใน Student API → ปฏิเสธ", async () => {
    const svc = createKmitlService(fakeClient({ sub: sub("d"), username: "60010002", givenName: "x" }, null));
    await expect(svc.callback("good", stateOf(svc.start()))).rejects.toBeInstanceOf(KmitlLoginError);
  });

  test("โหมดข้ามการตรวจ (ยังไม่มี API key): นักศึกษาล็อกอินได้โดยไม่เรียก Student API · ไม่มีคณะ · ปิดสวิตช์แล้วกลับมาตรวจตามเดิม", async () => {
    const profile = { sub: sub("skip"), username: "67050514", givenName: "สอง" };
    const skipping = fakeClient(profile, null); // Student API ไม่ตอบ/ไม่มีข้อมูล
    const svc = createKmitlService(skipping, Date.now, { skipStudentCheck: true });
    const oneTime = await svc.callback("good", stateOf(svc.start()));
    const userId = svc.exchange(oneTime);
    expect(userId).toBeDefined();
    expect(skipping.calls.some((c) => c.startsWith("student:"))).toBe(false);
    const [u] = await sql`SELECT is_verified, faculty_id FROM users WHERE id = ${userId!}`;
    expect(u).toMatchObject({ is_verified: true, faculty_id: null });

    // สวิตช์ปิด (ค่าเริ่มต้น) → ยังตรวจและปฏิเสธเมื่อไม่พบนักศึกษา
    const strict = createKmitlService(fakeClient({ sub: sub("strict"), username: "67050514", givenName: "x" }, null));
    await expect(strict.callback("good", stateOf(strict.start()))).rejects.toMatchObject({ reason: "not_active_student" });
  });

  test("บุคลากร (ไม่มีรหัสนักศึกษา) ผ่านโดยไม่เรียก Student API", async () => {
    const client = fakeClient({ sub: sub("e"), username: "somchai.s", givenName: "สมชาย" }, null);
    const svc = createKmitlService(client);
    const oneTime = await svc.callback("good", stateOf(svc.start()));
    expect(svc.exchange(oneTime)).toBeDefined();
    expect(client.calls.some((c) => c.startsWith("student:"))).toBe(false);
  });

  test("state ผิด / ใช้ซ้ำ → ปฏิเสธ (กัน CSRF และ replay)", async () => {
    const client = fakeClient({ sub: sub("f"), username: null, givenName: "x" }, null);
    const svc = createKmitlService(client);
    await expect(svc.callback("good", "forged-state")).rejects.toMatchObject({ reason: "sso_failed" });

    const state = stateOf(svc.start());
    await svc.callback("good", state);
    await expect(svc.callback("good", state)).rejects.toMatchObject({ reason: "sso_failed" });
  });

  test("แลก code กับสถาบันไม่สำเร็จ → sso_failed", async () => {
    const svc = createKmitlService(fakeClient({ sub: sub("g"), username: null, givenName: "x" }, null));
    await expect(svc.callback("bad", stateOf(svc.start()))).rejects.toMatchObject({ reason: "sso_failed" });
  });

  test("บัญชีถูกระงับ → banned", async () => {
    const client = fakeClient({ sub: sub("h"), username: null, givenName: "x" }, null);
    const svc = createKmitlService(client);
    const id = svc.exchange(await svc.callback("good", stateOf(svc.start())))!;
    await sql`UPDATE users SET is_banned = true WHERE id = ${id}`;
    await expect(svc.callback("good", stateOf(svc.start()))).rejects.toMatchObject({ reason: "banned" });
  });

  test("รหัสครั้งเดียวหมดอายุใน 60 วินาที", async () => {
    let now = 1_000_000;
    const svc = createKmitlService(fakeClient({ sub: sub("i"), username: null, givenName: "x" }, null), () => now);
    const oneTime = await svc.callback("good", stateOf(svc.start()));
    now += 61_000;
    expect(svc.exchange(oneTime)).toBeUndefined();
  });
});
