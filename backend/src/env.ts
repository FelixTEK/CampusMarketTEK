// อ่านค่าตั้งค่าจาก .env ที่เดียว (Bun โหลด .env ให้อัตโนมัติ)
// ถ้าขาดค่าที่จำเป็นให้หยุดตั้งแต่ตอนเริ่ม ไม่ปล่อยให้ไปพังกลางทาง

function required(name: string): string {
  const value = process.env[name];
  if (!value || value === "CHANGE_ME") {
    throw new Error(
      `ยังไม่ได้ตั้งค่า ${name} — คัดลอก .env.example เป็น .env แล้วแก้ค่า`,
    );
  }
  return value;
}

export const env = {
  databaseUrl: required("DATABASE_URL"),
  jwtSecret: required("JWT_SECRET"),
  port: Number(process.env.PORT ?? 3000),
};
