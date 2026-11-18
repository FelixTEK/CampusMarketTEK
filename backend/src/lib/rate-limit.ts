/**
 * จำกัดจำนวนครั้งที่ลองผิด (กันเดารหัสผ่าน) — นับในหน่วยความจำแบบหน้าต่างเวลาตายตัว
 * รีสตาร์ท server แล้วนับใหม่ ซึ่งยอมรับได้สำหรับโครงงานนี้
 */
export class FailureLimiter {
  private hits = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private max: number,
    private windowMs: number,
    private now: () => number = Date.now,
  ) {}

  /** true = ลองผิดเกินกำหนดแล้ว ต้องรอ */
  isBlocked(key: string): boolean {
    const hit = this.hits.get(key);
    if (!hit) return false;
    if (hit.resetAt <= this.now()) {
      this.hits.delete(key);
      return false;
    }
    return hit.count >= this.max;
  }

  recordFailure(key: string): void {
    const t = this.now();
    const hit = this.hits.get(key);
    if (!hit || hit.resetAt <= t) {
      this.hits.set(key, { count: 1, resetAt: t + this.windowMs });
    } else {
      hit.count++;
    }
  }

  reset(key: string): void {
    this.hits.delete(key);
  }
}
