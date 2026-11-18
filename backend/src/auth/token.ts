import { SignJWT, jwtVerify } from "jose";
import { env } from "../env";

const key = new TextEncoder().encode(env.jwtSecret);

/** อายุ token 7 วัน (ตามแผน) */
const TTL = "7d";

/** ออก JWT ให้ผู้ใช้ — เก็บแค่ id ผู้ใช้ (sub) ข้อมูลอื่นโหลดจากฐานข้อมูลทุกคำขอ */
export function signToken(userId: string): Promise<string> {
  return new SignJWT({})
    .setProtectedHeader({ alg: "HS256" })
    .setSubject(userId)
    .setIssuedAt()
    .setExpirationTime(TTL)
    .sign(key);
}

/** คืน id ผู้ใช้ ถ้า token ถูกต้องและยังไม่หมดอายุ ไม่งั้นคืน null (ไม่โยน error) */
export async function verifyToken(token: string): Promise<string | null> {
  try {
    const { payload } = await jwtVerify(token, key, { algorithms: ["HS256"] });
    return typeof payload.sub === "string" ? payload.sub : null;
  } catch {
    return null;
  }
}
