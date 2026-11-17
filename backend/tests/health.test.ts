import { describe, expect, test } from "bun:test";
import { app } from "../src/app";

// ต้องมี .env และฐานข้อมูลที่เชื่อมต่อได้
describe("GET /health", () => {
  test("ตอบ ok เมื่อเชื่อมฐานข้อมูลได้", async () => {
    const res = await app.handle(new Request("http://localhost/health"));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ status: "ok", database: "up" });
  });
});
