import { appendFile, mkdir, readdir, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { Elysia } from "elysia";

/**
 * ไฟล์ log การเข้าใช้งาน (พ.ร.บ.คอมพิวเตอร์ ม.26 → เก็บ 90 วัน, BR20)
 * หมุนไฟล์รายวัน logs/YYYY-MM-DD.log และลบไฟล์ที่เก่ากว่า 90 วัน
 *
 * บันทึกเฉพาะ: เวลา, method, path (ไม่เอา query), สถานะ, เวลาที่ใช้, id ผู้ใช้
 * ห้ามบันทึก: header Authorization, body, query (อาจมี code/token), รหัสผ่าน, เลขบัตร
 */
export const LOG_DIR = process.env.LOG_DIR ?? join(import.meta.dir, "..", "logs");
const KEEP_DAYS = 90;

const day = (d: Date) => d.toISOString().slice(0, 10);

export async function writeLog(line: string, now = new Date()): Promise<void> {
  try {
    await mkdir(LOG_DIR, { recursive: true });
    await appendFile(join(LOG_DIR, `${day(now)}.log`), `${line}\n`);
  } catch (e) {
    // ห้ามให้การเขียน log ทำให้คำขอพัง
    console.error("[logger]", e);
  }
}

export async function pruneOldLogs(now = new Date()): Promise<number> {
  const cutoff = now.getTime() - KEEP_DAYS * 24 * 60 * 60 * 1000;
  let removed = 0;
  try {
    for (const name of await readdir(LOG_DIR)) {
      if (!/^\d{4}-\d{2}-\d{2}\.log$/.test(name)) continue;
      const path = join(LOG_DIR, name);
      if ((await stat(path)).mtimeMs < cutoff) {
        await rm(path);
        removed++;
      }
    }
  } catch {
    // ยังไม่มีโฟลเดอร์ log = ไม่มีอะไรต้องลบ
  }
  return removed;
}

// ลบไฟล์เก่าตอนเริ่ม และทุก 6 ชั่วโมง (unref = ไม่ถ่วงการปิดโปรเซส)
void pruneOldLogs();
setInterval(() => void pruneOldLogs(), 6 * 60 * 60 * 1000).unref();

export const requestLogger = new Elysia({ name: "request-logger" })
  .derive({ as: "global" }, () => ({ startedAt: performance.now() }))
  .onAfterResponse({ as: "global" }, (ctx) => {
    const { request, set, startedAt } = ctx;
    const user = (ctx as { user?: { id: string } | null }).user;
    const status = typeof set.status === "number" ? set.status : 200;
    // เส้นทางที่ไม่มีอยู่ (404) ไม่ผ่าน derive จึงไม่มีเวลาเริ่ม
    const ms = startedAt === undefined ? 0 : Math.round(performance.now() - startedAt);
    const path = new URL(request.url).pathname;
    void writeLog(
      `${new Date().toISOString()} ${request.method} ${path} ${status} ${ms}ms user=${user?.id ?? "-"}`,
    );
  });
