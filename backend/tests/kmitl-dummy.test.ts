import { afterAll, describe, expect, test } from "bun:test";
import { Elysia } from "elysia";
import { sql } from "../src/db";
import { errorHandler } from "../src/errors";
import { DUMMY_STUDENTS, createDummyKmitlClient, dummyStudentApi } from "../src/kmitl/dummy";
import { createKmitlService } from "../src/kmitl/service";
import { ALLOWED_STATUSES, ALL_STATUSES, normalizeKmitlEmail, parseStudentInfo, thaiDisplayName } from "../src/kmitl/student";
import { kmitlDummyRoutes } from "../src/routes/kmitl-dummy";
import { authRoutes } from "../src/routes/auth";
import { userRoutes } from "../src/routes/users";

// SSO + Student API จำลอง: ทดสอบทั้ง flow ผ่าน HTTP จริงของแอป (แอปแยกที่ประกอบเอง ไม่ขึ้นกับค่าใน .env)
// sub ของบัญชีจำลองใส่ tag เฉพาะรอบ — จบแล้วลบเฉพาะของรอบนี้ ไม่แตะบัญชีจำลองที่คุณใช้อยู่ในฐานข้อมูลหลัก
const tag = crypto.randomUUID().slice(0, 8);
const BASE = "http://localhost:3000";
const mail = (n: string) => `t${tag}${n}@kmitl.ac.th`;

const base = createDummyKmitlClient(BASE);
/** ห่อ client จำลองให้ sub มี tag เฉพาะรอบ (exchange ใช้รหัสครั้งเดียว จึงเรียกครั้งเดียว) */
const once = (): typeof base => ({
  ...base,
  exchange: async (p) => {
    const profile = await base.exchange(p);
    return { ...profile, sub: `${tag}-${profile.sub}` };
  },
});

const svc = createKmitlService(once());
const app = new Elysia().use(errorHandler).use(authRoutes(svc)).use(userRoutes).use(kmitlDummyRoutes(`${BASE}/auth/kmitl/callback`));

const go = (method: string, url: string, init: { form?: Record<string, string>; json?: unknown; token?: string } = {}) => {
  const headers: Record<string, string> = {};
  let body: string | undefined;
  if (init.form) { headers["content-type"] = "application/x-www-form-urlencoded"; body = new URLSearchParams(init.form).toString(); }
  if (init.json !== undefined) { headers["content-type"] = "application/json"; body = JSON.stringify(init.json); }
  if (init.token) headers.authorization = `Bearer ${init.token}`;
  return app.handle(new Request(url.startsWith("http") ? url : `${BASE}${url}`, { method, headers, body }));
};

/** เดิน flow ครบทุกขั้นแบบแอปจริง: start → หน้าล็อกอินจำลอง → เลือกนักศึกษา → callback → (deep link) → exchange */
async function signIn(studentId: string, email?: string) {
  const start = await go("GET", "/auth/kmitl/start");
  const loginUrl = new URL(start.headers.get("location")!);
  const state = loginUrl.searchParams.get("state")!;
  const post = await go("POST", "/auth/kmitl/dummy/login", { form: { state, studentId, ...(email !== undefined ? { email } : {}) } });
  if (post.status !== 302) return { stage: "form" as const, status: post.status, html: await post.text() };
  const cb = await go("GET", post.headers.get("location")!);
  const deep = new URL(cb.headers.get("location")!);
  if (deep.searchParams.get("error")) return { stage: "callback" as const, error: deep.searchParams.get("error")! };
  const ex = await go("POST", "/auth/exchange", { json: { code: deep.searchParams.get("code") } });
  const session = (await ex.json()) as any;
  const me = (await (await go("GET", "/users/me", { token: session.token })).json()) as any;
  return { stage: "ok" as const, token: session.token as string, me };
}

afterAll(async () => {
  await sql`DELETE FROM users WHERE kmitl_sub LIKE ${`${tag}-%`} OR email LIKE ${`t${tag}%@kmitl.ac.th`} OR email LIKE ${`%${tag}@example.test`}`;
});

describe("Student API (จำลอง) และการดึงฟิลด์", () => {
  test("ดึงครบทุกฟิลด์ตามเอกสาร: ตัวอย่าง John Doe", () => {
    const sample = { student_id: 56070069, prename_th: "นาย", first_name_th: "John", last_name_th: "Doe", prename_en: "Mr.", first_name_en: "John", last_name_en: "Doe",
      status: "จบ", faculty_name_th: "คณะวิศวกรรมศาสตร์", faculty_name_en: "Faculty of Engineering", curriculum_name_th: "วิศวกรรมคอมพิวเตอร์", curriculum_name_en: "Computer Engineering", level: 1 };
    expect(parseStudentInfo(sample)).toEqual({
      studentId: "56070069", prenameTh: "นาย", firstNameTh: "John", lastNameTh: "Doe", prenameEn: "Mr.", firstNameEn: "John", lastNameEn: "Doe",
      status: "จบ", facultyNameTh: "คณะวิศวกรรมศาสตร์", facultyNameEn: "Faculty of Engineering", curriculumNameTh: "วิศวกรรมคอมพิวเตอร์", curriculumNameEn: "Computer Engineering", level: 1,
    });
  });

  test("ไม่มี level (ปริญญาตรี) → null · ไม่มี status/ไม่ใช่ object → null (ใช้ไม่ได้)", () => {
    expect(parseStudentInfo({ status: "เรียน" })?.level).toBeNull();
    expect(parseStudentInfo({ student_id: 1 })).toBeNull();
    expect(parseStudentInfo(null)).toBeNull();
    expect(parseStudentInfo("x")).toBeNull();
  });

  test("ชื่อที่แสดง = ชื่อ + นามสกุลไทย (ไม่รวมคำนำหน้า) · ยาวเกิน 50 ตัดให้", () => {
    expect(thaiDisplayName({ firstNameTh: "สมหญิง", lastNameTh: "ใจดี" })).toBe("สมหญิง ใจดี");
    expect(thaiDisplayName({ firstNameTh: "สมหญิง", lastNameTh: null })).toBe("สมหญิง");
    expect(thaiDisplayName({ firstNameTh: null, lastNameTh: null })).toBeNull();
    expect(thaiDisplayName({ firstNameTh: "ก".repeat(60), lastNameTh: "ข" })!.length).toBe(50);
  });

  test("สถานภาพ: ผ่านเฉพาะ 6 ค่า ที่เหลือ (จบ พ้นสภาพ ลาออก ฯลฯ) ไม่ผ่าน — ตรวจทั้ง 16 ค่าที่ API อาจส่ง", () => {
    expect(ALL_STATUSES).toHaveLength(16);
    const passing: string[] = [...ALL_STATUSES].filter((s) => ALLOWED_STATUSES.has(s)).sort();
    expect(passing).toEqual(["รออนุมัติสำเร็จการศึกษา", "ครบหลักสูตร (ค้างชำระ)", "เรียน", "เรียนเกินหลักสูตร", "เปลี่ยนหลักสูตร", "โอนย้ายข้ามคณะ"].sort());
    for (const s of ["จบ", "พ้นสภาพ", "ลาออก", "ตกให้ออก", "ไม่มารายงานตัว", "ยังไม่รายงานตัว", "พ้นสภาพ-ทุจริต", "ทดสอบ", "รออนุมัติพ้นสภาพ", "เสียชีวิต"])
      expect(ALLOWED_STATUSES.has(s)).toBe(false);
  });

  test("API จำลอง: ไม่มี apikey → 401 · ไม่พบ → 404 · พบ → 200 ฟิลด์ครบตามเอกสาร", () => {
    expect(dummyStudentApi("69010012", null).status).toBe(401);
    expect(dummyStudentApi("00000000", "k").status).toBe(404);
    const ok = dummyStudentApi("69010012", "k");
    expect(ok.status).toBe(200);
    expect(Object.keys(ok.body as object)).toEqual(expect.arrayContaining(["student_id", "prename_th", "first_name_th", "last_name_th", "prename_en", "first_name_en", "last_name_en", "status", "faculty_name_th", "faculty_name_en", "curriculum_name_th", "curriculum_name_en"]));
  });

  test("API จำลองผ่าน HTTP จริง (curl ได้): header apikey · รายชื่อ 3 คน", async () => {
    const noKey = await go("GET", "/dummy/student-catalog/v1/students/69010012");
    expect(noKey.status).toBe(401);
    const res = await app.handle(new Request(`${BASE}/dummy/student-catalog/v1/students/67050514`, { headers: { apikey: "x" } }));
    expect(res.status).toBe(200);
    expect(((await res.json()) as any).status).toBe("เรียนเกินหลักสูตร");
    const list = await app.handle(new Request(`${BASE}/dummy/student-catalog/v1/students`, { headers: { apikey: "x" } }));
    expect(((await list.json()) as any[]).length).toBe(3);
    expect((await app.handle(new Request(`${BASE}/dummy/student-catalog/v1/students/1`, { headers: { apikey: "x" } }))).status).toBe(404);
  });

  test("seed 3 คน: รหัส YYFFNNNN สอดคล้องคณะ (01 วิศวะ · 05 วิทยาศาสตร์) · มีทั้งผ่านและไม่ผ่าน", () => {
    expect(DUMMY_STUDENTS).toHaveLength(3);
    const byFaculty: Record<string, string> = { "01": "คณะวิศวกรรมศาสตร์", "05": "คณะวิทยาศาสตร์" };
    for (const s of DUMMY_STUDENTS.slice(0, 2)) {
      const id = String(s.student_id);
      expect(id).toMatch(/^\d{8}$/);
      expect(byFaculty[id.slice(2, 4)]).toBe(s.faculty_name_th as string);
    }
    expect(DUMMY_STUDENTS.map((s) => ALLOWED_STATUSES.has(String(s.status)))).toEqual([true, true, false]);
  });

  test("อีเมลสถาบัน: ตัวพิมพ์เล็ก ลงท้าย @kmitl.ac.th เท่านั้น", () => {
    expect(normalizeKmitlEmail("69010012@kmitl.ac.th")).toBe("69010012@kmitl.ac.th");
    expect(normalizeKmitlEmail(" 69010012@KMITL.AC.TH ")).toBe("69010012@kmitl.ac.th");
    for (const bad of ["69010012@gmail.com", "x@kmitl.ac.th.evil.com", "@kmitl.ac.th", "a b@kmitl.ac.th", "", null, undefined]) expect(normalizeKmitlEmail(bad as any)).toBeNull();
  });
});

describe("ล็อกอินด้วย SSO จำลองทั้ง flow", () => {
  test("หน้าล็อกอินจำลองแสดงนักศึกษาทั้ง 3 คนพร้อมสถานภาพและช่องอีเมลที่ว่าง", async () => {
    const start = await go("GET", "/auth/kmitl/start");
    expect(start.status).toBe(302);
    const html = await (await go("GET", start.headers.get("location")!)).text();
    for (const s of DUMMY_STUDENTS) expect(html).toContain(String(s.student_id));
    expect(html).toContain("ผ่านเงื่อนไข");
    expect(html).toContain("ไม่ผ่านเงื่อนไข");
    expect(html).toMatch(/name="email"[^>]*value=""/); // ช่องอีเมลว่างให้กรอกเอง
    expect(html).toContain("YYFFNNNN@kmitl.ac.th");
  });

  test("นักศึกษา 'เรียน' + กรอกอีเมล → บัญชีได้ชื่อไทย คณะ อีเมล และยืนยันแล้ว", async () => {
    const r = await signIn("69010012", mail("a"));
    expect(r.stage).toBe("ok");
    if (r.stage !== "ok") return;
    expect(r.me).toMatchObject({ displayName: "สมหญิง ใจดี", email: mail("a"), isVerified: true, isKmitl: true });
    const [f] = await sql`SELECT id FROM faculties WHERE name_th = 'คณะวิศวกรรมศาสตร์'`;
    expect(r.me.facultyId).toBe(f.id);
    expect(JSON.stringify(r.me)).not.toContain("69010012"); // รหัสนักศึกษาไม่หลุดในข้อมูลบัญชี
  });

  test("'เรียนเกินหลักสูตร' ผ่าน · ไม่กรอกอีเมล → อีเมลว่าง (บัญชีใช้ได้ปกติ)", async () => {
    const r = await signIn("67050514");
    expect(r.stage).toBe("ok");
    if (r.stage !== "ok") return;
    expect(r.me).toMatchObject({ displayName: "ธีรภัทร ศรีสุข", email: null, isKmitl: true });
    const [f] = await sql`SELECT id FROM faculties WHERE name_th = 'คณะวิทยาศาสตร์'`;
    expect(r.me.facultyId).toBe(f.id);
  });

  test("สถานภาพ 'จบ' (John Doe) → ถูกปฏิเสธ not_active_student และไม่สร้างบัญชี", async () => {
    const r = await signIn("56070069", mail("c"));
    expect(r).toEqual({ stage: "callback", error: "not_active_student" });
    const [{ n }] = await sql`SELECT count(*)::int AS n FROM users WHERE kmitl_sub = ${`${tag}-dummy-kmitl-56070069`}`;
    expect(n).toBe(0);
  });

  test("ล็อกอินซ้ำ: ไม่ทับชื่อที่ผู้ใช้แก้เอง · ถ้ายังไม่มีอีเมลแล้วกรอกรอบหลังก็เติมให้ · มีแล้วไม่ทับ", async () => {
    const first = await signIn("67050514");
    if (first.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    await go("PATCH", "/users/me", { token: first.token, json: { displayName: "ชื่อที่ฉันตั้งเอง" } });

    const second = await signIn("67050514", mail("d"));
    if (second.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    expect(second.me.id).toBe(first.me.id); // บัญชีเดิม (BR03)
    expect(second.me.displayName).toBe("ชื่อที่ฉันตั้งเอง");
    expect(second.me.email).toBe(mail("d"));

    const third = await signIn("67050514", mail("e"));
    if (third.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    expect(third.me.email).toBe(mail("d")); // อีเมลเดิมอยู่ ไม่ถูกทับ
  });

  test("อีเมลที่บัญชีอื่นใช้อยู่ → ล็อกอินได้แต่ไม่เอาอีเมลนั้น (ไม่แย่งของคนอื่น)", async () => {
    const taken = mail("f");
    await sql`INSERT INTO users (email, password_hash, display_name) VALUES (${taken}, 'x', 'เจ้าของเดิม')`;
    const r = await signIn("69010012", taken);
    if (r.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    expect(r.me.email).not.toBe(taken);
  });

  test("อีเมลผิดรูปแบบในหน้าล็อกอิน → 400 อยู่หน้าเดิมพร้อมข้อความ ไม่ออกรหัส", async () => {
    for (const bad of ["me@gmail.com", "abc", "x@kmitl.ac.th.evil.com"]) {
      const r = await signIn("69010012", bad);
      expect(r.stage).toBe("form");
      if (r.stage === "form") { expect(r.status).toBe(400); expect(r.html).toContain("@kmitl.ac.th"); }
    }
    const none = await go("POST", "/auth/kmitl/dummy/login", { form: { state: "s", studentId: "00000000" } });
    expect(none.status).toBe(400);
  });

  test("state ไม่ตรง/ปลอม → ปฏิเสธ sso_failed · รหัสครั้งเดียวใช้ซ้ำไม่ได้", async () => {
    const forged = await go("POST", "/auth/kmitl/dummy/login", { form: { state: "forged-state", studentId: "69010012" } });
    const cb = await go("GET", forged.headers.get("location")!);
    expect(new URL(cb.headers.get("location")!).searchParams.get("error")).toBe("sso_failed");

    const r = await signIn("69010012");
    if (r.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    const reuse = await go("POST", "/auth/exchange", { json: { code: "already-used" } });
    expect(reuse.status).toBe(401);
  });
});

describe("บัญชีคนนอกกับอีเมล สจล.", () => {
  test("คนนอกสมัครด้วยอีเมล @kmitl.ac.th (รวมโดเมนย่อย) ไม่ได้ · อีเมลอื่นสมัครได้", async () => {
    for (const email of ["someone@kmitl.ac.th", "a@it.kmitl.ac.th", "A@KMITL.AC.TH"]) {
      const r = await go("POST", "/auth/register", { json: { email, password: "Passw0rd-123", displayName: "x" } });
      expect(r.status).toBe(422);
      expect(((await r.json()) as any).message).toContain("เข้าสู่ระบบด้วยปุ่ม");
    }
    const ok = await go("POST", "/auth/register", { json: { email: `out${tag}@example.test`, password: "Passw0rd-123", displayName: "คนนอก" } });
    expect(ok.status).toBe(200);
  });

  test("บัญชี สจล. ที่มีอีเมล ล็อกอินด้วยอีเมล+รหัสผ่านไม่ได้ (รหัสผ่านเป็นค่าสุ่มที่ไม่มีใครรู้) — ต้องผ่าน SSO", async () => {
    const r = await signIn("69010012", mail("g"));
    if (r.stage !== "ok") throw new Error("ต้องล็อกอินได้");
    for (const pw of ["Passw0rd-123", "69010012", "password", ""]) {
      const login = await go("POST", "/auth/login", { json: { email: mail("g"), password: pw } });
      expect(login.status).toBeGreaterThanOrEqual(400);
      expect(login.status).toBeLessThan(500);
    }
  });
});
