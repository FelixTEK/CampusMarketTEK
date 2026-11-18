import { AppError } from "../errors";

export type ListingStatus = "AVAILABLE" | "RESERVED" | "SOLD" | "CANCELLED" | "RETURNED" | "TAKEN_DOWN";
export type Actor = "owner" | "admin" | "system";

/**
 * ตารางการเปลี่ยนสถานะของประกาศ (ข้อ 2.10 ใน PROJECT_PLAN.md) — ที่เดียวที่กำหนดว่าใครเปลี่ยนอะไรได้
 * "ขายแล้ว" (SOLD) เกิดจากดีลที่สำเร็จเท่านั้น (system) ผู้ขายกดเองไม่ได้ และเป็นสถานะสุดท้าย (BR24)
 */
const TABLE: Array<{ from: ListingStatus; to: ListingStatus; by: Actor[] }> = [
  { from: "AVAILABLE", to: "RESERVED", by: ["system"] },     // ผู้ขายตกลงดีล (เฟส 6)
  { from: "AVAILABLE", to: "CANCELLED", by: ["owner"] },      // BR22
  { from: "AVAILABLE", to: "RETURNED", by: ["admin"] },
  { from: "AVAILABLE", to: "TAKEN_DOWN", by: ["admin"] },
  { from: "RESERVED", to: "AVAILABLE", by: ["system"] },      // ดีลถูกยกเลิก
  { from: "RESERVED", to: "SOLD", by: ["system"] },           // ผู้ซื้อกดได้รับแล้ว / ครบ 48 ชม.
  { from: "RESERVED", to: "RETURNED", by: ["admin"] },
  { from: "RESERVED", to: "TAKEN_DOWN", by: ["admin"] },
  { from: "CANCELLED", to: "AVAILABLE", by: ["owner"] },      // ลงขายอีกครั้ง
  { from: "RETURNED", to: "AVAILABLE", by: ["owner"] },       // แก้แล้วส่งใหม่
  { from: "RETURNED", to: "TAKEN_DOWN", by: ["admin"] },
  { from: "TAKEN_DOWN", to: "AVAILABLE", by: ["admin"] },     // กู้คืน (ผู้ดูแลเท่านั้น)
];

export const LABEL: Record<ListingStatus, string> = {
  AVAILABLE: "กำลังขาย",
  RESERVED: "ถูกจอง",
  SOLD: "ขายแล้ว",
  CANCELLED: "ยกเลิกแล้ว",
  RETURNED: "ถูกตีกลับ",
  TAKEN_DOWN: "ถูกถอด",
};

export function canTransition(from: ListingStatus, to: ListingStatus, actor: Actor): boolean {
  return TABLE.some((r) => r.from === from && r.to === to && r.by.includes(actor));
}

/** ไม่ผ่าน → 409 (สถานะปัจจุบันไม่รองรับการกระทำนี้) */
export function assertTransition(from: ListingStatus, to: ListingStatus, actor: Actor): void {
  if (!canTransition(from, to, actor)) {
    throw new AppError(409, "INVALID_STATUS", `ประกาศที่${LABEL[from]}แล้วเปลี่ยนเป็น${LABEL[to]}ไม่ได้`);
  }
}

/** สถานะที่ผู้ใช้ทั่วไปเปิดดูได้ (BR16): ขายแล้วดูได้จากลิงก์/รายการโปรด */
export const PUBLIC_STATUSES: ListingStatus[] = ["AVAILABLE", "RESERVED", "SOLD"];
