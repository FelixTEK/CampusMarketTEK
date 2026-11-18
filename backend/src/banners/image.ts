import { AppError } from "../errors";
import { sniffImageType } from "../identity/crypto";

/** อ่านความกว้าง×สูงจากหัวไฟล์ (ไม่ถอดรหัสรูปทั้งใบ) · PNG: ช่อง IHDR · JPEG: เดินหาเครื่องหมาย SOFn */
export function imageSize(bytes: Uint8Array): { width: number; height: number } | null {
  const type = sniffImageType(bytes);
  const at = (i: number) => bytes[i] ?? 0;
  const u32 = (i: number) => ((at(i) << 24) | (at(i + 1) << 16) | (at(i + 2) << 8) | at(i + 3)) >>> 0;
  const u16 = (i: number) => (at(i) << 8) | at(i + 1);

  if (type === "image/png") {
    if (bytes.length < 24) return null;
    return { width: u32(16), height: u32(20) };
  }
  if (type === "image/jpeg") {
    let i = 2;
    while (i + 9 < bytes.length) {
      if (at(i) !== 0xff) { i++; continue; } // ข้ามไบต์ที่ไม่ใช่เครื่องหมาย
      const marker = at(i + 1);
      if (marker === 0xff) { i++; continue; }     // ไบต์เติม
      if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) { i += 2; continue; } // ไม่มีความยาว
      const isSof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
      if (isSof) return { height: u16(i + 5), width: u16(i + 7) };
      i += 2 + u16(i + 2);
    }
  }
  return null;
}

export const BANNER_MIN_WIDTH = 600;

/** รูปแบนเนอร์ต้องเป็น 2:1 (คลาดได้ ±5%) และกว้างอย่างน้อย 600 px — ไม่งั้นแสดงแล้วถูกครอปเพี้ยน */
export function assertBannerImage(bytes: Uint8Array): "image/jpeg" | "image/png" {
  const type = sniffImageType(bytes);
  if (!type) throw new AppError(422, "VALIDATION", "ไฟล์ต้องเป็นรูป JPEG หรือ PNG");
  const size = imageSize(bytes);
  if (!size || size.height === 0) throw new AppError(422, "VALIDATION", "อ่านขนาดรูปไม่ได้");
  const ratio = size.width / size.height;
  if (ratio < 1.9 || ratio > 2.1) throw new AppError(422, "VALIDATION", `รูปต้องมีสัดส่วน 2:1 (รูปนี้ ${size.width}×${size.height})`);
  if (size.width < BANNER_MIN_WIDTH) throw new AppError(422, "VALIDATION", `รูปต้องกว้างอย่างน้อย ${BANNER_MIN_WIDTH} px`);
  return type;
}
