import { Elysia } from "elysia";

/**
 * ข้อผิดพลาดที่ตั้งใจให้ผู้ใช้เห็น — ทุกที่ที่ปฏิเสธคำขอให้ throw ตัวนี้
 * ข้อความ (message) เป็นภาษาไทย แอปแสดงให้ผู้ใช้อ่านได้เลย
 */
export class AppError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export const unauthenticated = () =>
  new AppError(401, "UNAUTHENTICATED", "กรุณาเข้าสู่ระบบ");
export const forbidden = (code = "FORBIDDEN", message = "คุณไม่มีสิทธิ์ทำรายการนี้") =>
  new AppError(403, code, message);
export const notFound = (message = "ไม่พบข้อมูลที่ต้องการ") =>
  new AppError(404, "NOT_FOUND", message);
export const conflict = (message: string) => new AppError(409, "CONFLICT", message);

/**
 * ตัวจัดการ error กลาง: ทุก error ออกเป็นรูปเดียวกัน { code, message }
 * error ที่ไม่รู้จัก (บั๊กของเรา) จะไม่หลุดรายละเอียดภายในไปให้ client — เขียนลง console เท่านั้น
 */
export const errorHandler = new Elysia({ name: "error-handler" }).onError(
  { as: "global" },
  ({ code, error, set }) => {
    if (error instanceof AppError) {
      set.status = error.status;
      return { code: error.code, message: error.message };
    }
    switch (code) {
      case "NOT_FOUND":
        set.status = 404;
        return { code: "NOT_FOUND", message: "ไม่พบสิ่งที่ร้องขอ" };
      case "VALIDATION":
        set.status = 422;
        return { code: "VALIDATION", message: "ข้อมูลไม่ถูกต้อง กรุณาตรวจสอบอีกครั้ง" };
      case "INVALID_FILE_TYPE":
        set.status = 422;
        return { code: "VALIDATION", message: "ไฟล์ต้องเป็นรูป JPEG หรือ PNG" };
      case "PARSE":
        set.status = 400;
        return { code: "BAD_REQUEST", message: "รูปแบบคำขอไม่ถูกต้อง" };
      default:
        // บันทึกเฉพาะชนิด/รหัส/ข้อความ — ไม่พิมพ์ทั้งก้อน เพราะ error จากฐานข้อมูลมีค่าพารามิเตอร์ของคำสั่ง (อาจเป็นอีเมล/โทเค็น) ติดมาด้วย
        const e = error as { name?: string; code?: string; message?: string };
        console.error("[error]", e.name ?? "Error", e.code ?? "", e.message ?? "");
        set.status = 500;
        return { code: "INTERNAL", message: "เซิร์ฟเวอร์ขัดข้อง กรุณาลองใหม่ภายหลัง" };
    }
  },
);
