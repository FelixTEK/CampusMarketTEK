/**
 * ศูนย์กลางส่งเหตุการณ์สดผ่าน WebSocket (/ws): ข้อความใหม่ และสถานะดีลที่เปลี่ยน
 * เก็บการเชื่อมต่อของผู้ใช้แต่ละคนไว้ในหน่วยความจำ (ผู้ใช้หนึ่งคนมีได้หลายเครื่อง)
 * ถ้าผู้รับไม่ได้ออนไลน์ก็แค่ไม่มีใครรับ — ข้อมูลจริงอยู่ในฐานข้อมูลแล้ว แอปดึงเองตอนเปิดห้อง/กลับมาออนไลน์
 */
export type RealtimeEvent =
  | { type: "message"; message: unknown }
  | { type: "deal"; conversationId: string; deal: unknown }
  | { type: "hello" };

export interface Sender {
  send(data: string): unknown;
}

const sockets = new Map<string, Set<Sender>>();

export const hub = {
  add(userId: string, ws: Sender): void {
    let set = sockets.get(userId);
    if (!set) sockets.set(userId, (set = new Set()));
    set.add(ws);
  },

  remove(userId: string, ws: Sender): void {
    const set = sockets.get(userId);
    if (!set) return;
    set.delete(ws);
    if (set.size === 0) sockets.delete(userId);
  },

  /** ส่งให้ผู้ใช้ทุกเครื่องที่เชื่อมอยู่ — คืนจำนวนเครื่องที่ส่งถึง */
  publish(userId: string, event: RealtimeEvent): number {
    const set = sockets.get(userId);
    if (!set) return 0;
    const data = JSON.stringify(event);
    let n = 0;
    for (const ws of set) {
      try {
        ws.send(data);
        n++;
      } catch {
        set.delete(ws); // เชื่อมต่อเสียแล้ว
      }
    }
    return n;
  },

  /** ส่งให้ผู้ใช้หลายคน (ซ้ำคนเดิมส่งครั้งเดียว) */
  publishMany(userIds: Array<string | null | undefined>, event: RealtimeEvent): void {
    for (const id of new Set(userIds)) if (id) hub.publish(id, event);
  },

  /** สำหรับทดสอบ */
  connectionCount(userId: string): number {
    return sockets.get(userId)?.size ?? 0;
  },
};
