import { describe, expect, test } from "bun:test";
import { LABEL, assertTransition, canTransition, type ListingStatus } from "../src/listings/status";

const ALL = Object.keys(LABEL) as ListingStatus[];

describe("ตารางการเปลี่ยนสถานะประกาศ", () => {
  test("SOLD เป็นสถานะสุดท้าย: ไม่มีใครเปลี่ยนต่อได้ (BR24)", () => {
    for (const to of ALL) for (const actor of ["owner", "admin", "system"] as const)
      expect(canTransition("SOLD", to, actor)).toBe(false);
  });

  test("ผู้ขายกด 'ขายแล้ว' เองไม่ได้ — เฉพาะระบบ (ดีลสำเร็จ) (BR24)", () => {
    expect(canTransition("RESERVED", "SOLD", "owner")).toBe(false);
    expect(canTransition("RESERVED", "SOLD", "admin")).toBe(false);
    expect(canTransition("RESERVED", "SOLD", "system")).toBe(true);
  });

  test("ผู้ขายยกเลิกได้เฉพาะตอนกำลังขาย (BR22)", () => {
    expect(canTransition("AVAILABLE", "CANCELLED", "owner")).toBe(true);
    for (const from of ALL.filter((s) => s !== "AVAILABLE")) expect(canTransition(from, "CANCELLED", "owner")).toBe(false);
  });

  test("ตีกลับ/ถอดเป็นของผู้ดูแล · กู้คืนจากถูกถอดเป็นของผู้ดูแลเท่านั้น (BR23)", () => {
    expect(canTransition("AVAILABLE", "RETURNED", "admin")).toBe(true);
    expect(canTransition("AVAILABLE", "RETURNED", "owner")).toBe(false);
    expect(canTransition("TAKEN_DOWN", "AVAILABLE", "admin")).toBe(true);
    expect(canTransition("TAKEN_DOWN", "AVAILABLE", "owner")).toBe(false);
  });

  test("assertTransition โยน 409 พร้อมข้อความไทย", () => {
    try {
      assertTransition("SOLD", "AVAILABLE", "owner");
      throw new Error("should not pass");
    } catch (e: any) {
      expect(e.status).toBe(409);
      expect(e.message).toContain("ขายแล้ว");
    }
  });
});
