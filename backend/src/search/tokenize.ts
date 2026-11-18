/**
 * ตัดคำสำหรับค้นหา — PostgreSQL ไม่รู้จักคำไทย (ไม่มีช่องว่างคั่น) จึงตัดที่ backend ก่อนเก็บ
 * ใช้ Intl.Segmenter ที่มากับ Bun/ICU: ผลลัพธ์เก็บใน listings.search_text เป็นคำคั่นด้วยช่องว่าง
 * แล้ว full-text แบบ 'simple' จะค้นได้ (และมี pg_trgm เป็นตัวสำรองเมื่อตัดคำพลาด)
 */
const segmenter = new Intl.Segmenter("th", { granularity: "word" });

export function tokenize(text: string): string[] {
  const out: string[] = [];
  for (const part of segmenter.segment(text.normalize("NFC").toLowerCase())) {
    if (part.isWordLike) out.push(part.segment);
  }
  return out;
}

/** รวมหลายข้อความ (ชื่อ รายละเอียด ชื่อหมวด ชื่อแท็ก) เป็นสตริงค้นหาเดียว ตัดคำซ้ำออก */
export function buildSearchText(...parts: Array<string | null | undefined>): string {
  const seen = new Set<string>();
  for (const part of parts) {
    if (!part) continue;
    for (const token of tokenize(part)) seen.add(token);
  }
  return [...seen].join(" ");
}
