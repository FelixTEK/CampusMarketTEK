/**
 * ข้อมูลนักศึกษาจาก Student API ของ สจล. (GET /students/{id}) — ฟิลด์ตามเอกสาร OpenAPI ของ developer.kmitl.ac.th
 * ตัวจริงและตัวจำลอง (kmitl/dummy.ts) ผ่านฟังก์ชัน parseStudentInfo ตัวเดียวกัน
 */
export type StudentInfo = {
  studentId: string;
  prenameTh: string | null;
  firstNameTh: string | null;
  lastNameTh: string | null;
  prenameEn: string | null;
  firstNameEn: string | null;
  lastNameEn: string | null;
  /** สถานภาพ: ดู ALLOWED_STATUSES สำหรับค่าที่ผ่าน */
  status: string;
  facultyNameTh: string | null;
  facultyNameEn: string | null;
  curriculumNameTh: string | null;
  curriculumNameEn: string | null;
  /** ระดับการศึกษา — มีเฉพาะนักศึกษาบัณฑิตศึกษา */
  level: number | null;
};

/** สถานภาพทั้งหมดที่ Student API อาจส่งมา (ตามเอกสาร) */
export const ALL_STATUSES = [
  "จบ", "พ้นสภาพ", "ลาออก", "เรียนเกินหลักสูตร", "ตกให้ออก", "ไม่มารายงานตัว", "ครบหลักสูตร (ค้างชำระ)",
  "ยังไม่รายงานตัว", "พ้นสภาพ-ทุจริต", "เรียน", "เปลี่ยนหลักสูตร", "ทดสอบ", "รออนุมัติพ้นสภาพ",
  "รออนุมัติสำเร็จการศึกษา", "เสียชีวิต", "โอนย้ายข้ามคณะ",
] as const;

/**
 * สถานภาพที่เข้าใช้งานได้ (BR02): ยังเป็นนักศึกษาของสถาบันอยู่ — เรียน · เปลี่ยนหลักสูตร · รออนุมัติสำเร็จการศึกษา ·
 * โอนย้ายข้ามคณะ · ครบหลักสูตร (ค้างชำระ) · เรียนเกินหลักสูตร · ที่เหลือ (จบ พ้นสภาพ ลาออก ตกให้ออก ฯลฯ) ไม่ผ่าน
 */
export const ALLOWED_STATUSES: ReadonlySet<string> = new Set([
  "เรียน", "เปลี่ยนหลักสูตร", "รออนุมัติสำเร็จการศึกษา", "โอนย้ายข้ามคณะ", "ครบหลักสูตร (ค้างชำระ)", "เรียนเกินหลักสูตร",
]);

const str = (v: unknown): string | null => (typeof v === "string" && v.trim() ? v.trim() : null);

/** แปลง JSON ของ Student API เป็น StudentInfo · ไม่มี status ถือว่าข้อมูลใช้ไม่ได้ (null) */
export function parseStudentInfo(json: unknown): StudentInfo | null {
  if (!json || typeof json !== "object") return null;
  const o = json as Record<string, unknown>;
  const status = str(o.status);
  if (!status) return null;
  const id = typeof o.student_id === "number" ? String(o.student_id) : str(o.student_id);
  return {
    studentId: id ?? "",
    prenameTh: str(o.prename_th),
    firstNameTh: str(o.first_name_th),
    lastNameTh: str(o.last_name_th),
    prenameEn: str(o.prename_en),
    firstNameEn: str(o.first_name_en),
    lastNameEn: str(o.last_name_en),
    status,
    facultyNameTh: str(o.faculty_name_th),
    facultyNameEn: str(o.faculty_name_en),
    curriculumNameTh: str(o.curriculum_name_th),
    curriculumNameEn: str(o.curriculum_name_en),
    level: typeof o.level === "number" && Number.isInteger(o.level) ? o.level : null,
  };
}

/** ชื่อที่แสดงในระบบ = ชื่อ + นามสกุลภาษาไทย (ไม่รวมคำนำหน้า) · ไม่มีข้อมูลคืน null */
export function thaiDisplayName(s: Pick<StudentInfo, "firstNameTh" | "lastNameTh">): string | null {
  const name = [s.firstNameTh, s.lastNameTh].filter(Boolean).join(" ").trim();
  return name ? name.slice(0, 50) : null;
}

/** อีเมลสถาบันที่รับได้: ตัวพิมพ์เล็ก ลงท้าย @kmitl.ac.th (นักศึกษาปกติคือ YYFFNNNN@kmitl.ac.th) */
export function normalizeKmitlEmail(raw: string | null | undefined): string | null {
  const e = raw?.trim().toLowerCase();
  return e && e.length <= 254 && /^[a-z0-9._-]+@kmitl\.ac\.th$/.test(e) ? e : null;
}
