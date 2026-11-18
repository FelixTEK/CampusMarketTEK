import { Elysia, t } from "elysia";
import { auth, requireAdmin, requireVerified } from "../auth/plugin";
import { sql } from "../db";
import { sellerStats } from "../users/stats";

/**
 * Dashboard (CM10, ข้อ 2.13) — นับ/รวมด้วย COUNT/SUM … GROUP BY ในฐานข้อมูล ไม่มีคอลัมน์เพิ่ม
 *  - ยอดขาย = ผลรวม "ราคาประกาศ" ของประกาศที่ขายสำเร็จ (ค่าประมาณ ราคาต่อรองจริงอยู่นอกระบบ)
 *  - เดือนนับตามเวลาไทย (Asia/Bangkok) จากวันที่ดีลสำเร็จ (completed_at) · เติมเดือนที่ไม่มีข้อมูลเป็น 0 ให้กราฟต่อเนื่อง
 */
const TZ = "Asia/Bangkok";
const STATUSES = ["AVAILABLE", "RESERVED", "SOLD", "CANCELLED", "RETURNED", "TAKEN_DOWN"] as const;

/** n เดือนล่าสุด (รวมเดือนนี้) เป็นตาราง months(m) */
const monthsCte = (n: number) => sql`
  months AS (
    SELECT generate_series(
      date_trunc('month', now() AT TIME ZONE ${TZ}) - make_interval(months => ${n - 1}::int),
      date_trunc('month', now() AT TIME ZONE ${TZ}),
      interval '1 month') AS m
  )`;

const rowsToMonthly = (rows: any[]) => rows.map((r) => ({ month: r.month as string, count: r.n as number, value: r.value as number }));

async function byStatus(sellerId: string) {
  const rows = await sql`SELECT status, count(*)::int AS n FROM listings WHERE seller_id = ${sellerId} GROUP BY status`;
  const out = Object.fromEntries(STATUSES.map((s) => [s, 0])) as Record<(typeof STATUSES)[number], number>;
  for (const r of rows) out[r.status as (typeof STATUSES)[number]] = r.n;
  return out;
}

export const statsRoutes = new Elysia({ name: "routes-stats" })
  .use(auth)
  .guard({ beforeHandle: requireVerified }, (app) =>
    app.get(
      "/stats/me",
      async ({ query, user }) => {
        const me = user!.id;
        const n = query.months ?? 6;

        const seller = await sql`
          WITH ${monthsCte(n)}
          SELECT to_char(m.m, 'YYYY-MM') AS month, count(d.id)::int AS n, coalesce(sum(l.price), 0)::int AS value
          FROM months m
          LEFT JOIN (deals d JOIN listings l ON l.id = d.listing_id AND l.seller_id = ${me})
            ON d.status = 'COMPLETED' AND date_trunc('month', d.completed_at AT TIME ZONE ${TZ}) = m.m
          GROUP BY m.m ORDER BY m.m`;

        const buyer = await sql`
          WITH ${monthsCte(n)}
          SELECT to_char(m.m, 'YYYY-MM') AS month, count(d.id)::int AS n, coalesce(sum(l.price), 0)::int AS value
          FROM months m
          LEFT JOIN (deals d JOIN listings l ON l.id = d.listing_id AND d.buyer_id = ${me})
            ON d.status = 'COMPLETED' AND date_trunc('month', d.completed_at AT TIME ZONE ${TZ}) = m.m
          GROUP BY m.m ORDER BY m.m`;

        const [life] = await sql`
          SELECT
            (SELECT coalesce(sum(l.price), 0)::int FROM listings l WHERE l.seller_id = ${me} AND l.status = 'SOLD') AS sold_value,
            (SELECT count(*)::int FROM deals d WHERE d.buyer_id = ${me} AND d.status = 'COMPLETED') AS bought,
            (SELECT coalesce(sum(l.price), 0)::int FROM deals d JOIN listings l ON l.id = d.listing_id WHERE d.buyer_id = ${me} AND d.status = 'COMPLETED') AS spent`;

        const { sold, closeRate, rating } = await sellerStats(me);
        return {
          months: n,
          seller: { sold, soldValue: life.sold_value as number, closeRate, rating, byStatus: await byStatus(me), monthly: rowsToMonthly(seller) },
          buyer: { bought: life.bought as number, spent: life.spent as number, monthly: rowsToMonthly(buyer) },
        };
      },
      { query: t.Object({ months: t.Optional(t.Numeric({ minimum: 1, maximum: 12 })) }) },
    ),
  );

export const adminStatsRoutes = new Elysia({ name: "routes-admin-stats" })
  .use(auth)
  .guard({ beforeHandle: requireAdmin }, (app) =>
    app.get(
      "/admin/stats",
      async ({ query }) => {
        const n = query.months ?? 6;
        const [u] = await sql`
          SELECT count(*)::int AS total,
                 count(*) FILTER (WHERE is_verified)::int AS verified,
                 count(*) FILTER (WHERE is_banned)::int AS banned,
                 count(*) FILTER (WHERE kmitl_sub IS NOT NULL)::int AS kmitl
          FROM users WHERE deleted_at IS NULL`;
        const listings = await sql`SELECT status, count(*)::int AS n FROM listings GROUP BY status`;
        const deals = await sql`SELECT status, count(*)::int AS n FROM deals GROUP BY status`;
        const [q] = await sql`
          SELECT (SELECT count(*)::int FROM reports WHERE status = 'OPEN') AS open_reports,
                 (SELECT count(*)::int FROM id_card_requests WHERE status = 'PENDING') AS pending_verifications,
                 (SELECT coalesce(sum(l.price), 0)::int FROM deals d JOIN listings l ON l.id = d.listing_id WHERE d.status = 'COMPLETED') AS total_value`;
        const monthly = await sql`
          WITH ${monthsCte(n)}
          SELECT to_char(m.m, 'YYYY-MM') AS month, count(d.id)::int AS n, coalesce(sum(l.price), 0)::int AS value
          FROM months m
          LEFT JOIN (deals d JOIN listings l ON l.id = d.listing_id)
            ON d.status = 'COMPLETED' AND date_trunc('month', d.completed_at AT TIME ZONE ${TZ}) = m.m
          GROUP BY m.m ORDER BY m.m`;
        const newUsers = await sql`
          WITH ${monthsCte(n)}
          SELECT to_char(m.m, 'YYYY-MM') AS month, count(x.id)::int AS n
          FROM months m LEFT JOIN users x ON date_trunc('month', x.created_at AT TIME ZONE ${TZ}) = m.m AND x.deleted_at IS NULL
          GROUP BY m.m ORDER BY m.m`;

        const toMap = (rows: any[]) => Object.fromEntries(rows.map((r) => [r.status, r.n as number]));
        return {
          months: n,
          users: { total: u.total as number, verified: u.verified as number, banned: u.banned as number, kmitl: u.kmitl as number, outsiders: (u.total - u.kmitl) as number },
          listings: { ...Object.fromEntries(STATUSES.map((s) => [s, 0])), ...toMap(listings) },
          deals: toMap(deals),
          openReports: q.open_reports as number,
          pendingVerifications: q.pending_verifications as number,
          totalValue: q.total_value as number,
          monthly: rowsToMonthly(monthly),
          newUsers: newUsers.map((r: any) => ({ month: r.month as string, count: r.n as number })),
        };
      },
      { query: t.Object({ months: t.Optional(t.Numeric({ minimum: 1, maximum: 12 })) }) },
    ),
  );
