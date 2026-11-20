import { randomBytes } from "node:crypto";
import { TtlStore } from "../lib/ttl-store";
import type { KmitlClient } from "./client";
import { ALLOWED_STATUSES, parseStudentInfo } from "./student";

/**
 * SSO + Student API จำลอง (เฉพาะโหมดพัฒนา — KMITL_DUMMY=true) ไว้ใช้ตอนยังต่อ SSO/Student API จริงไม่ได้
 * ใช้ flow เดียวกับของจริงทุกขั้น (start → หน้าล็อกอิน → callback → ตรวจสถานะ → สร้างบัญชี → exchange)
 * สลับกลับไปใช้ของจริงได้โดยเอา KMITL_DUMMY ออกจาก .env
 */

/**
 * นักศึกษาสาธิต 15 คน — เป็นคนเดียวกับผู้ขาย/ผู้ซื้อ สจล. ใน `scripts/seed-demo.ts` (ใช้ข้อมูลชุดนี้ชุดเดียว) จึงล็อกอินผ่าน SSO จำลองแล้วเจอประกาศและบัญชีเดิม
 * เรียงตามลำดับที่ seed ใช้: 10 คนแรกเป็นผู้ขาย อีก 5 คนเป็นผู้ซื้อ · สถานภาพ "เรียน" ทั้งหมด
 */
const CURRICULA = {
  "01": [["วิศวกรรมคอมพิวเตอร์", "Computer Engineering"], ["วิศวกรรมไฟฟ้า", "Electrical Engineering"], ["วิศวกรรมโยธา", "Civil Engineering"]],
  "05": [["วิทยาการคอมพิวเตอร์", "Computer Science"], ["เคมี", "Chemistry"], ["ฟิสิกส์", "Physics"]],
} as const;
const FACULTY = { "01": ["คณะวิศวกรรมศาสตร์", "Faculty of Engineering"], "05": ["คณะวิทยาศาสตร์", "Faculty of Science"] } as const;

// [รหัสนักศึกษา, M/F, ชื่อ, นามสกุล, ชื่ออังกฤษ, นามสกุลอังกฤษ]
const DEMO_ROWS: Array<[number, "M" | "F", string, string, string, string]> = [
  [65017680, "M", "ธนกร", "วงศ์สวัสดิ์", "Thanakorn", "Wongsawat"],
  [68014165, "M", "ภัทรพล", "ศรีสมบูรณ์", "Phattharaphon", "Srisomboon"],
  [67057109, "M", "กฤษณะ", "จันทร์เพ็ญ", "Krissana", "Chanpen"],
  [67057704, "M", "ณัฐวุฒิ", "รัตนพันธ์", "Nattawut", "Rattanaphan"],
  [65051892, "F", "ปวีณา", "แก้วมณี", "Paweena", "Kaewmanee"],
  [66055988, "F", "ชลธิชา", "พงษ์เจริญ", "Chonthicha", "Phongcharoen"],
  [66011883, "F", "อารียา", "สิริวัฒน์", "Areeya", "Siriwat"],
  [66054183, "F", "วริศรา", "ทองดี", "Warisara", "Thongdee"],
  [68019964, "F", "กัญญาณัฐ", "ใจมั่น", "Kanyanat", "Jaimun"],
  [66019287, "F", "นภัสสร", "เกียรติกุล", "Naphatsorn", "Kiattikul"],
  [66014667, "M", "ศุภณัฐ", "มณีรัตน์", "Suphanat", "Maneerat"],
  [65051298, "M", "อภิชาติ", "บุญเรือง", "Apichat", "Boonruang"],
  [68055263, "F", "ธิดารัตน์", "สายทอง", "Thidarat", "Saithong"],
  [68013588, "M", "รพีภัทร", "อินทร์แก้ว", "Rapheephat", "Inkaew"],
  [66054111, "M", "พีรวิชญ์", "สุขเกษม", "Peerawit", "Sukkasem"],
];
export const DEMO_STUDENTS: Array<Record<string, unknown>> = DEMO_ROWS.map(([id, sex, first, last, firstEn, lastEn], i) => {
  const ff = String(id).slice(2, 4) as "01" | "05";
  const [cth, cen] = CURRICULA[ff][i % 3]!;
  return {
    student_id: id, prename_th: sex === "M" ? "นาย" : "นางสาว", first_name_th: first, last_name_th: last,
    prename_en: sex === "M" ? "Mr." : "Ms.", first_name_en: firstEn, last_name_en: lastEn,
    status: "เรียน",
    faculty_name_th: FACULTY[ff][0], faculty_name_en: FACULTY[ff][1],
    curriculum_name_th: cth, curriculum_name_en: cen,
  };
});

/** นักศึกษาตัวอย่าง 3 คน — รูปแบบ JSON ตรงกับ GET /students/{id} ของ Student API (student_id = YYFFNNNN: ปีที่เข้า, รหัสคณะ, ลำดับ) */
export const DUMMY_STUDENTS: Array<Record<string, unknown>> = [
  {
    student_id: 69010012, prename_th: "นางสาว", first_name_th: "สมหญิง", last_name_th: "ใจดี",
    prename_en: "Ms.", first_name_en: "Somying", last_name_en: "Jaidee",
    status: "เรียน",
    faculty_name_th: "คณะวิศวกรรมศาสตร์", faculty_name_en: "Faculty of Engineering",
    curriculum_name_th: "วิศวกรรมคอมพิวเตอร์", curriculum_name_en: "Computer Engineering",
  },
  {
    student_id: 67050514, prename_th: "นาย", first_name_th: "ธีรภัทร", last_name_th: "ศรีสุข",
    prename_en: "Mr.", first_name_en: "Theeraphat", last_name_en: "Srisuk",
    status: "เรียนเกินหลักสูตร",
    faculty_name_th: "คณะวิทยาศาสตร์", faculty_name_en: "Faculty of Science",
    curriculum_name_th: "วิทยาการคอมพิวเตอร์", curriculum_name_en: "Computer Science",
  },
  {
    // ตัวอย่างจากเอกสาร Student API (สถานภาพ "จบ" → ไม่ผ่านเงื่อนไข ไว้ทดสอบกรณีถูกปฏิเสธ)
    student_id: 56070069, prename_th: "นาย", first_name_th: "John", last_name_th: "Doe",
    prename_en: "Mr.", first_name_en: "John", last_name_en: "Doe",
    status: "จบ",
    faculty_name_th: "คณะวิศวกรรมศาสตร์", faculty_name_en: "Faculty of Engineering",
    curriculum_name_th: "วิศวกรรมคอมพิวเตอร์", curriculum_name_en: "Computer Engineering",
    level: 1,
  },
  ...DEMO_STUDENTS,
];

/** จำลอง GET /students/{id}: ต้องมี header apikey (ไม่มี = 401 เหมือนของจริง) · ไม่พบ = 404 */
export function dummyStudentApi(id: string, apiKey: string | null | undefined): { status: number; body: unknown } {
  if (!apiKey) return { status: 401, body: { message: "Unauthorized" } };
  const s = DUMMY_STUDENTS.find((x) => String(x.student_id) === id);
  return s ? { status: 200, body: s } : { status: 404, body: { message: "Student not found" } };
}

type Issued = { studentId: string; email: string | null };
const codes = new TtlStore<Issued>();

/** ออกรหัสครั้งเดียว (5 นาที) หลังเลือกนักศึกษาในหน้าล็อกอินจำลอง */
export function issueDummyCode(studentId: string, email: string | null): string {
  const code = randomBytes(18).toString("base64url");
  codes.set(code, { studentId, email }, 5 * 60_000);
  return code;
}

export function createDummyKmitlClient(publicBaseUrl: string): KmitlClient {
  return {
    authorizeUrl: ({ state }) => `${publicBaseUrl}/auth/kmitl/dummy/login?state=${encodeURIComponent(state)}`,

    async exchange({ code }) {
      const issued = codes.take(code);
      if (!issued) throw new Error("dummy code ไม่ถูกต้องหรือหมดอายุ");
      const student = parseStudentInfo(dummyStudentApi(issued.studentId, "dummy").body);
      return {
        sub: `dummy-kmitl-${issued.studentId}`, // เหมือน sub จริง: รหัสอ้างอิงคงที่ต่อคน (ไม่ใช่รหัสนักศึกษาล้วน)
        username: issued.studentId,
        givenName: student?.firstNameTh ?? null,
        email: issued.email,
      };
    },

    async studentInfo(studentId) {
      const r = dummyStudentApi(studentId, "dummy");
      return r.status === 200 ? parseStudentInfo(r.body) : null;
    },
  };
}

// ── หน้าล็อกอินจำลอง (HTML ล้วน ไม่มี JavaScript) ───────────────────────────

const esc = (v: unknown) =>
  String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

export function renderDummyLogin(o: { state: string; error?: string; selected?: string; email?: string }): string {
  const cards = DUMMY_STUDENTS.map((s) => {
    const id = String(s.student_id);
    const ok = ALLOWED_STATUSES.has(String(s.status));
    return `<label class="card"><input type="radio" name="studentId" value="${esc(id)}"${o.selected === id ? " checked" : ""} required>
      <span><b>${esc(s.prename_th)}${esc(s.first_name_th)} ${esc(s.last_name_th)}</b><br>
      รหัส ${esc(id)} · ${esc(s.faculty_name_th)}<br>${esc(s.curriculum_name_th)}<br>
      สถานภาพ: <b class="${ok ? "ok" : "bad"}">${esc(s.status)}</b> ${ok ? "(ผ่านเงื่อนไข)" : "(ไม่ผ่านเงื่อนไข)"}</span></label>`;
  }).join("\n");

  return `<!doctype html><html lang="th"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>KMITL SSO (จำลอง)</title>
<style>
 body{font-family:system-ui,"Noto Sans Thai",sans-serif;margin:0;background:#f4f1ec;color:#1b1b1b}
 main{max-width:460px;margin:0 auto;padding:20px}
 .banner{background:#7a1f1f;color:#fff;padding:10px 14px;border-radius:10px;font-size:14px}
 h1{font-size:20px;margin:18px 0 4px} p{margin:4px 0 12px;color:#555;font-size:14px}
 .card{display:flex;gap:10px;align-items:flex-start;background:#fff;border:1px solid #d8d2c8;border-radius:12px;padding:12px;margin:10px 0}
 .card input{margin-top:6px} .ok{color:#0e6b5a} .bad{color:#a12626}
 label.f{display:block;margin-top:14px;font-weight:600;font-size:14px}
 input[type=text]{width:100%;box-sizing:border-box;padding:12px;border:1px solid #b9b2a6;border-radius:10px;font-size:16px;margin-top:6px}
 small{color:#666} button{width:100%;margin-top:18px;padding:14px;border:0;border-radius:12px;background:#0e6b5a;color:#fff;font-size:16px}
 .err{background:#fde8e8;color:#8a1c1c;padding:10px;border-radius:10px;margin-top:12px}
</style></head><body><main>
<div class="banner">หน้าล็อกอินจำลอง — ไม่ใช่ระบบจริงของ สจล. ใช้ทดสอบเท่านั้น (ไม่มีการกรอกรหัสผ่าน)</div>
<h1>เข้าสู่ระบบด้วยบัญชี KMITL</h1><p>เลือกนักศึกษาตัวอย่าง (ข้อมูลตาม Student API)</p>
${o.error ? `<div class="err">${esc(o.error)}</div>` : ""}
<form method="post" action="/auth/kmitl/dummy/login">
<input type="hidden" name="state" value="${esc(o.state)}">
${cards}
<label class="f" for="email">อีเมลสถาบัน (ไม่บังคับ)</label>
<input type="text" id="email" name="email" inputmode="email" autocomplete="off" placeholder="เช่น 69010012@kmitl.ac.th" value="${esc(o.email)}">
<small>รูปแบบ YYFFNNNN@kmitl.ac.th — YY = ปีที่เข้าศึกษา, FF = รหัสคณะ (01 วิศวกรรมศาสตร์, 05 วิทยาศาสตร์) · เว้นว่างได้ ถ้ากรอกต้องลงท้าย @kmitl.ac.th</small>
<button type="submit">เข้าสู่ระบบ</button>
</form></main></body></html>`;
}
