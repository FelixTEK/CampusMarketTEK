-- ============================================================
-- Campus Market — คำสั่ง SQL ไว้ดูข้อมูล (อ่านอย่างเดียว ไม่แก้ข้อมูล)
-- เปิดด้วย: psql -h localhost -p 5433 -U campus -d campus_market
-- ============================================================

-- ── 0. ภาพรวม: แต่ละตารางมีกี่แถว ─────────────────────────────
SELECT 'users' AS tbl, count(*) FROM users
UNION ALL SELECT 'listings', count(*) FROM listings
UNION ALL SELECT 'deals', count(*) FROM deals
UNION ALL SELECT 'conversations', count(*) FROM conversations
UNION ALL SELECT 'messages', count(*) FROM messages
UNION ALL SELECT 'reviews', count(*) FROM reviews
UNION ALL SELECT 'reports', count(*) FROM reports
UNION ALL SELECT 'notifications', count(*) FROM notifications
UNION ALL SELECT 'ad_banners', count(*) FROM ad_banners
UNION ALL SELECT 'id_card_requests', count(*) FROM id_card_requests;

-- ── 1. ผู้ใช้ ─────────────────────────────────────────────────
-- ใครเป็นใคร: ประเภท, ยืนยันแล้วไหม, ผู้ดูแล, ถูกระงับ
SELECT display_name,
       CASE WHEN kmitl_sub IS NOT NULL THEN 'สจล.' ELSE 'บุคคลภายนอก' END AS type,
       email, is_verified, is_admin, is_banned, created_at
FROM users
WHERE deleted_at IS NULL
ORDER BY created_at DESC
LIMIT 20;

-- ผู้ดูแลทั้งหมด
SELECT display_name, email FROM users WHERE is_admin AND deleted_at IS NULL;

-- ผู้ใช้ที่ถูกระงับ
SELECT display_name, email FROM users WHERE is_banned;

-- คำขอยืนยันตัวตนที่รอผู้ดูแลตรวจ
SELECT u.display_name, r.status, r.created_at
FROM id_card_requests r JOIN users u ON u.id = r.user_id
WHERE r.status = 'PENDING';

-- ── 2. ประกาศ ─────────────────────────────────────────────────
-- ประกาศล่าสุด + ผู้ขาย + หมวด
SELECT l.title, l.price, l.status, u.display_name AS seller, c.name_th AS category, l.created_at
FROM listings l
JOIN users u      ON u.id = l.seller_id
JOIN categories c ON c.id = l.category_id
ORDER BY l.created_at DESC
LIMIT 20;

-- นับประกาศตามสถานะ
SELECT status, count(*) FROM listings GROUP BY status ORDER BY 2 DESC;

-- นับประกาศตามหมวดหลัก (หนังสือ / คอม / เครื่องเขียน)
SELECT coalesce(p.name_th, c.name_th) AS main_category, count(*)
FROM listings l
JOIN categories c      ON c.id = l.category_id
LEFT JOIN categories p ON p.id = c.parent_id
GROUP BY 1 ORDER BY 2 DESC;

-- ค้นชื่อประกาศแบบง่าย (ILIKE = LIKE ไม่สนตัวพิมพ์เล็ก/ใหญ่)
SELECT title, price, status FROM listings WHERE title ILIKE '%calculus%';

-- คำที่ระบบตัดไว้สำหรับค้นหาภาษาไทย (Intl.Segmenter)
SELECT title, search_text FROM listings LIMIT 5;

-- ── 3. แชตและดีล ──────────────────────────────────────────────
-- ดีลทั้งหมด: ประกาศอะไร ใครซื้อ สถานะไหน
SELECT l.title, b.display_name AS buyer, s.display_name AS seller, d.status, d.meetup, d.updated_at
FROM deals d
JOIN listings l ON l.id = d.listing_id
JOIN users b    ON b.id = d.buyer_id
JOIN users s    ON s.id = l.seller_id
ORDER BY d.updated_at DESC;

-- ข้อความล่าสุด (sender ว่าง = ข้อความระบบ, deal_id มีค่า = การ์ดดีล)
SELECT m.id, coalesce(u.display_name, '[ระบบ]') AS sender, m.body, m.deal_id IS NOT NULL AS is_deal_card, m.created_at
FROM messages m LEFT JOIN users u ON u.id = m.sender_id
ORDER BY m.id DESC
LIMIT 20;

-- พิสูจน์ "กันจองซ้อน": ต้องไม่มีประกาศไหนมีดีลที่ตกลงแล้ว > 1 (ผลต้องว่าง)
SELECT listing_id, count(*)
FROM deals
WHERE status IN ('ACCEPTED', 'HANDED_OVER')
GROUP BY listing_id
HAVING count(*) > 1;

-- ── 4. รีวิวและตัวเลขผู้ขาย ───────────────────────────────────
SELECT s.display_name AS seller,
       count(*) FILTER (WHERE d.status = 'COMPLETED') AS sold,
       round(avg(r.rating), 1) AS avg_rating,
       count(r.id) AS reviews
FROM listings l
JOIN users s       ON s.id = l.seller_id
JOIN deals d       ON d.listing_id = l.id
LEFT JOIN reviews r ON r.deal_id = d.id
GROUP BY s.display_name
ORDER BY sold DESC;

-- ── 5. รายงาน แจ้งเตือน แบนเนอร์ ──────────────────────────────
-- รายงานที่ยังเปิดอยู่ (คิวผู้ดูแล)
SELECT r.target_type, r.reason, u.display_name AS reporter, r.created_at
FROM reports r JOIN users u ON u.id = r.reporter_id
WHERE r.status = 'OPEN'
ORDER BY r.created_at;

-- แจ้งเตือนล่าสุด (รวม SPAM_WARN ของประกาศซ้ำ)
SELECT u.display_name, n.type, n.body, n.is_read, n.created_at
FROM notifications n JOIN users u ON u.id = n.user_id
ORDER BY n.id DESC
LIMIT 20;

-- แบนเนอร์ที่กำลังแสดงอยู่ตอนนี้
SELECT title, sort, starts_at, ends_at
FROM ad_banners
WHERE is_active AND now() BETWEEN starts_at AND ends_at
ORDER BY sort;
