import { app } from "./app";
import { env } from "./env";
import { startAutoCompleteJob } from "./chat/deals";
import { startDealReminderJob } from "./jobs/deal-reminders";
import { startRetentionJob } from "./jobs/retention";
import { startPurgeJob } from "./jobs/purge-id-images";

startPurgeJob(); // ลบรูปบัตรที่ครบ 7 วันหลังตัดสิน
startAutoCompleteJob(); // ปิดดีลที่ส่งมอบแล้วเกิน 48 ชม. ทุก 15 นาที
startDealReminderJob(); // เตือนช่วยปิดดีล (24 ชม.) และชวนรีวิว ครั้งเดียวต่อดีล
startRetentionJob(); // ลบแชต (1 ปีหลังข้อความล่าสุด) และรายงานที่ปิดแล้ว (1 ปี) ตามประกาศความเป็นส่วนตัว

// hostname 0.0.0.0 = รับการเชื่อมต่อจากเครื่องอื่นด้วย (emulator ผ่าน 10.0.2.2, มือถือจริงผ่าน IP ในวง LAN)
app.listen({ port: env.port, hostname: "0.0.0.0" });

console.log(`Campus Market API  http://localhost:${env.port}`);
console.log(`เอกสาร API         http://localhost:${env.port}/openapi`);
