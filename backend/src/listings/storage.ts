import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { AppError } from "../errors";
import { sniffImageType } from "../identity/crypto";

/** รูปสินค้า: เก็บเป็นไฟล์ใต้ uploads/ ตั้งชื่อ listings/<listingId>/<ลำดับ>.jpg|png · ดึงผ่าน GET /uploads/... ที่ต้องล็อกอินเท่านั้น */
export const UPLOAD_DIR = process.env.UPLOAD_DIR ?? join(import.meta.dir, "..", "..", "uploads");
const full = (key: string) => join(UPLOAD_DIR, key);

export type ImageFile = { bytes: Uint8Array; type: "image/jpeg" | "image/png" };

/** อ่านไฟล์จาก multipart และตรวจชนิดจากไบต์จริง (ไม่เชื่อ Content-Type ที่ client ส่ง) · 1–5 รูป */
export async function readImages(files: File[]): Promise<ImageFile[]> {
  if (files.length < 1 || files.length > 5) throw new AppError(422, "VALIDATION", "ต้องมีรูปสินค้า 1–5 รูป");
  const out: ImageFile[] = [];
  for (const f of files) {
    const bytes = new Uint8Array(await f.arrayBuffer());
    const type = sniffImageType(bytes);
    if (!type) throw new AppError(422, "VALIDATION", "ไฟล์ต้องเป็นรูป JPEG หรือ PNG");
    out.push({ bytes, type });
  }
  return out;
}

/** เขียนรูปลงดิสก์ คืน key ตามลำดับ (0 = ปก) */
export async function saveImages(listingId: string, images: ImageFile[]): Promise<string[]> {
  const keys: string[] = [];
  for (const [i, img] of images.entries()) {
    const key = `listings/${listingId}/${i}.${img.type === "image/png" ? "png" : "jpg"}`;
    await mkdir(dirname(full(key)), { recursive: true });
    await writeFile(full(key), img.bytes);
    keys.push(key);
  }
  return keys;
}

export async function removeListingImages(listingId: string): Promise<void> {
  await rm(full(`listings/${listingId}`), { recursive: true, force: true });
}

export async function readImage(key: string): Promise<Buffer | null> {
  try {
    return await readFile(full(key));
  } catch {
    return null;
  }
}

/** URL สัมพัทธ์ที่แอปใช้โหลดรูป */
export const imageUrl = (key: string) => `/uploads/${key}`;
