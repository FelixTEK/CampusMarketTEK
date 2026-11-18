// อ่านค่าตั้งค่าจาก .env ที่เดียว (Bun โหลด .env ให้อัตโนมัติ)
// ถ้าขาดค่าที่จำเป็นให้หยุดตั้งแต่ตอนเริ่ม ไม่ปล่อยให้ไปพังกลางทาง

function required(name: string): string {
  const value = process.env[name];
  if (!value || value === "CHANGE_ME") {
    throw new Error(
      `ยังไม่ได้ตั้งค่า ${name} — คัดลอก .env.example เป็น .env แล้วแก้ค่า`,
    );
  }
  return value;
}

function requiredHex32(name: string): Buffer {
  const value = required(name);
  if (!/^[0-9a-fA-F]{64}$/.test(value)) {
    throw new Error(`${name} ต้องเป็น hex 64 ตัว (openssl rand -hex 32)`);
  }
  return Buffer.from(value, "hex");
}

// SSO + Student API จำลอง (kmitl/dummy.ts) — เฉพาะโหมดพัฒนา: ใครก็เลือกนักศึกษาตัวอย่างเข้าระบบได้ จึงห้ามเปิดใน production
const kmitlDummy = process.env.KMITL_DUMMY === "true";
if (kmitlDummy && process.env.NODE_ENV === "production") {
  throw new Error("KMITL_DUMMY=true ใช้ใน production ไม่ได้ (หน้าล็อกอินจำลองไม่ตรวจตัวตน)");
}

const kmitlClientId = process.env.KMITL_CLIENT_ID;
const kmitlClientSecret = process.env.KMITL_CLIENT_SECRET;

// ข้ามการตรวจสถานะ "เรียน" กับ Student API (BR02) — ไว้ลอง SSO จริงตอนยังไม่มี API key · เฉพาะโหมดพัฒนา
const skipStudentCheck = process.env.KMITL_SKIP_STUDENT_CHECK === "true";
if (skipStudentCheck && process.env.NODE_ENV === "production") {
  throw new Error("KMITL_SKIP_STUDENT_CHECK=true ใช้ใน production ไม่ได้ (ข้ามกฎ BR02: นักศึกษาต้องมีสถานะ \"เรียน\")");
}

export const env = {
  databaseUrl: required("DATABASE_URL"),
  jwtSecret: required("JWT_SECRET"),
  port: Number(process.env.PORT ?? 3000),
  isProduction: process.env.NODE_ENV === "production",
  /** กุญแจเข้ารหัสรูปบัตร (32 ไบต์) */
  encryptionKey: requiredHex32("ENCRYPTION_KEY"),
  /** กุญแจ HMAC ของเลขบัตร (32 ไบต์) */
  idHashSecret: requiredHex32("ID_HASH_SECRET"),
  /** true = ใช้ SSO/Student API จำลองแทนของ สจล. (ไม่ต้องมี client/API key) */
  kmitlDummy,
  publicBaseUrl: (process.env.PUBLIC_BASE_URL ?? "http://localhost:3000").replace(/\/$/, ""),
  /** ไม่ตั้งค่า = ยังไม่เปิด SSO */
  kmitl:
    kmitlClientId && kmitlClientSecret
      ? {
          clientId: kmitlClientId,
          clientSecret: kmitlClientSecret,
          apiKey: process.env.KMITL_API_KEY ?? null,
          skipStudentCheck,
          issuer: process.env.KMITL_ISSUER ?? "https://sso.kmitl.ac.th/realms/master",
          studentApiBase:
            process.env.KMITL_STUDENT_API ?? "https://api.kmitl.ac.th/student-catalog/v1",
        }
      : null,
};
