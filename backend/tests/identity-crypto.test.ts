import { describe, expect, test } from "bun:test";
import { randomBytes } from "node:crypto";
import { withChecksum } from "./helpers";
import { decryptBytes, encryptBytes, hashNationalId, isValidThaiNationalId, sniffImageType } from "../src/identity/crypto";

describe("เข้ารหัสไฟล์ AES-256-GCM", () => {
  const key = randomBytes(32);

  test("ถอดรหัสได้ข้อมูลเดิม และไฟล์ที่เข้ารหัสไม่มีข้อมูลเดิมให้เห็น", () => {
    const plain = Buffer.from("ข้อมูลลับ secret image bytes");
    const packed = encryptBytes(plain, key);
    expect(packed.includes(plain)).toBe(false);
    expect(decryptBytes(packed, key).equals(plain)).toBe(true);
  });

  test("เข้ารหัสซ้ำได้ผลต่างกัน (iv สุ่ม)", () => {
    const plain = Buffer.from("same");
    expect(encryptBytes(plain, key).equals(encryptBytes(plain, key))).toBe(false);
  });

  test("ไฟล์ถูกแก้ไขหรือกุญแจผิด → ถอดรหัสไม่ผ่าน", () => {
    const packed = encryptBytes(Buffer.from("hello"), key);
    const tampered = Buffer.from(packed);
    tampered[tampered.length - 1] = tampered[tampered.length - 1]! ^ 0xff;
    expect(() => decryptBytes(tampered, key)).toThrow();
    expect(() => decryptBytes(packed, randomBytes(32))).toThrow();
  });
});

describe("เลขบัตรประชาชน", () => {
  test("checksum ถูก/ผิด", () => {
    const good = withChecksum("110170023070");
    expect(isValidThaiNationalId(good)).toBe(true);
    const bad = good.slice(0, 12) + String((Number(good[12]) + 1) % 10);
    expect(isValidThaiNationalId(bad)).toBe(false);
    expect(isValidThaiNationalId("123")).toBe(false);
    expect(isValidThaiNationalId("abcdefghijklm")).toBe(false);
  });

  test("แฮชคงที่ต่อเลขเดิม ต่างกันต่อเลขอื่น และไม่มีเลขจริงอยู่ในค่าแฮช", () => {
    const secret = randomBytes(32);
    const id = withChecksum("110170023070");
    expect(hashNationalId(id, secret)).toBe(hashNationalId(id, secret));
    expect(hashNationalId(id, secret)).not.toBe(hashNationalId(withChecksum("110170023071"), secret));
    expect(hashNationalId(id, secret)).not.toContain(id);
  });
});

describe("ตรวจชนิดไฟล์จากไบต์จริง", () => {
  test("jpeg / png / อย่างอื่น", () => {
    expect(sniffImageType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe("image/jpeg");
    expect(sniffImageType(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))).toBe("image/png");
    expect(sniffImageType(new TextEncoder().encode("<html>"))).toBeNull();
  });
});
