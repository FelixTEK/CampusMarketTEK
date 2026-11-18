/**
 * ที่เก็บค่าชั่วคราวในหน่วยความจำ หมดอายุเอง — ใช้กับ state/nonce ของ SSO และรหัสครั้งเดียว (เฟส 3)
 * ไม่ลงฐานข้อมูลโดยตั้งใจ: ค่าพวกนี้อายุไม่กี่นาที และรีสตาร์ท server แล้วหายก็ไม่เป็นไร
 */
export class TtlStore<T> {
  private items = new Map<string, { value: T; expiresAt: number }>();

  constructor(private now: () => number = Date.now) {
    // กวาดของที่หมดอายุทุกนาที (unref = ไม่ถ่วงการปิดโปรเซส)
    setInterval(() => this.sweep(), 60_000).unref();
  }

  set(key: string, value: T, ttlMs: number): void {
    this.items.set(key, { value, expiresAt: this.now() + ttlMs });
  }

  /** ดึงค่าแล้วลบทิ้งทันที — ใช้ได้ครั้งเดียว (กัน replay) หมดอายุแล้วได้ undefined */
  take(key: string): T | undefined {
    const item = this.items.get(key);
    this.items.delete(key);
    if (!item || item.expiresAt <= this.now()) return undefined;
    return item.value;
  }

  get size(): number {
    return this.items.size;
  }

  sweep(): void {
    const t = this.now();
    for (const [key, item] of this.items) {
      if (item.expiresAt <= t) this.items.delete(key);
    }
  }
}
