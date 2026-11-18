import { describe, expect, test } from "bun:test";
import { TtlStore } from "../src/lib/ttl-store";

describe("TtlStore", () => {
  test("take ใช้ได้ครั้งเดียว", () => {
    const store = new TtlStore<string>();
    store.set("a", "v", 1000);
    expect(store.take("a")).toBe("v");
    expect(store.take("a")).toBeUndefined();
  });

  test("หมดอายุแล้วได้ undefined และถูกกวาดออก", () => {
    let now = 1_000;
    const store = new TtlStore<string>(() => now);
    store.set("a", "v", 500);
    store.set("b", "w", 5_000);
    now = 2_000;
    store.sweep();
    expect(store.size).toBe(1);
    expect(store.take("a")).toBeUndefined();
    expect(store.take("b")).toBe("w");
  });
});
