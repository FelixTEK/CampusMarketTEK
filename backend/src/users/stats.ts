import { sql } from "../db";

/**
 * ตัวเลขความน่าเชื่อถือของผู้ขาย (ข้อ 2.13) — คำนวณตอน query ไม่มีคอลัมน์เพิ่ม
 *  - ปิดการขายสำเร็จ = ประกาศ SOLD · อัตราปิด = SOLD ÷ (SOLD + CANCELLED) แสดงเมื่อปิดแล้ว ≥ 3 ประกาศ
 *  - คะแนน = เฉลี่ยรายผู้ซื้อก่อน แล้วค่อยเฉลี่ยรวม (ผู้ซื้อคนเดียวรีวิวหลายดีลก็นับน้ำหนักเท่าคนเดียว)
 */
export const MIN_CLOSED_FOR_RATE = 3;

export async function sellerStats(sellerId: string) {
  const [c] = await sql`
    SELECT count(*) FILTER (WHERE status = 'SOLD')::int AS sold,
           count(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled,
           count(*) FILTER (WHERE status IN ('AVAILABLE', 'RESERVED'))::int AS active
    FROM listings WHERE seller_id = ${sellerId}`;
  const closed = c.sold + c.cancelled;

  const [r] = await sql`
    SELECT avg(b.avg_rating)::float AS avg, count(*)::int AS buyers, sum(b.n)::int AS reviews
    FROM (
      SELECT d.buyer_id, avg(rv.rating) AS avg_rating, count(*) AS n
      FROM reviews rv JOIN deals d ON d.id = rv.deal_id JOIN listings l ON l.id = d.listing_id
      WHERE l.seller_id = ${sellerId}
      GROUP BY d.buyer_id
    ) b`;

  const recent = await sql`
    SELECT rv.rating, rv.comment, rv.created_at, u.display_name AS buyer_name, l.title
    FROM reviews rv JOIN deals d ON d.id = rv.deal_id JOIN listings l ON l.id = d.listing_id JOIN users u ON u.id = d.buyer_id
    WHERE l.seller_id = ${sellerId}
    ORDER BY rv.created_at DESC LIMIT 10`;

  return {
    sold: c.sold as number,
    activeListings: c.active as number,
    // null = ผู้ขายใหม่ (ปิดแล้วไม่ถึง 3 ประกาศ) แอปแสดง "ผู้ขายใหม่"
    closeRate: closed >= MIN_CLOSED_FOR_RATE ? Math.round((c.sold / closed) * 100) : null,
    rating: r.reviews ? { average: Math.round(r.avg * 10) / 10, count: r.reviews as number } : null,
    reviews: recent.map((x: any) => ({
      rating: x.rating as number,
      comment: (x.comment ?? null) as string | null,
      createdAt: new Date(x.created_at).toISOString(),
      buyerName: x.buyer_name as string,
      listingTitle: x.title as string,
    })),
  };
}
