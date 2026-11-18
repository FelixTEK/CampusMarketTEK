import { describe, expect, test } from "bun:test";
import { buildSearchText, tokenize } from "../src/search/tokenize";

describe("tokenize", () => {
  test("ตัดคำไทยออกเป็นหลายคำ", () => {
    const tokens = tokenize("หนังสือฟิสิกส์ปี 1");
    expect(tokens.length).toBeGreaterThan(1);
    expect(tokens).toContain("หนังสือ");
    expect(tokens).toContain("1");
  });

  test("คำอังกฤษและตัวเลขเก็บเป็นตัวพิมพ์เล็ก", () => {
    expect(tokenize("Lenovo Legion g102")).toEqual(["lenovo", "legion", "g102"]);
  });

  test("ข้ามเครื่องหมายวรรคตอนและช่องว่าง", () => {
    expect(tokenize("  ,, !! ")).toEqual([]);
  });

  test("buildSearchText รวมหลายส่วนและตัดคำซ้ำ", () => {
    const text = buildSearchText("เมาส์ Logitech", null, "logitech g102");
    expect(text.split(" ").filter((t) => t === "logitech")).toHaveLength(1);
    expect(text).toContain("g102");
  });
});
