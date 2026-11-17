-- Campus Market — schema v2 (17 ตาราง)
-- อธิบายแต่ละตารางและคอลัมน์ที่ docs/DB_SCHEMA.md
-- ตัวรัน migration (scripts/migrate.ts) รันไฟล์นี้ใน transaction เดียว พังกลางไฟล์ = ย้อนกลับทั้งไฟล์

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ============================================================
-- ผู้ใช้
-- ============================================================

CREATE TABLE faculties (
  id      smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name_th text NOT NULL UNIQUE
);

CREATE TABLE users (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email         text UNIQUE                 -- ชื่อล็อกอินของคนนอก (คน สจล. ใช้ SSO ไม่มีอีเมลในตารางนี้)
                CHECK (email = lower(email) AND char_length(email) <= 254
                       AND email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
  password_hash text,                       -- argon2id (Bun.password) คนนอกเท่านั้น
  kmitl_sub     text UNIQUE,                -- sub จาก KMITL SSO (คน สจล.)
  id_hash       text UNIQUE,                -- HMAC ของเลขบัตร ตั้งตอนผู้ดูแลอนุมัติ (ไม่เก็บเลขจริง)
  display_name  text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 50),
  faculty_id    smallint REFERENCES faculties (id),
  is_verified   boolean NOT NULL DEFAULT false,
  is_admin      boolean NOT NULL DEFAULT false,
  is_banned     boolean NOT NULL DEFAULT false,
  deleted_at    timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  -- คนนอกมี email คู่กับ password_hash เสมอ
  CHECK ((email IS NULL) = (password_hash IS NULL)),
  -- บัญชีที่ยังไม่ถูกลบต้องล็อกอินได้อย่างน้อยหนึ่งทาง
  CHECK (deleted_at IS NOT NULL OR email IS NOT NULL OR kmitl_sub IS NOT NULL)
);

CREATE TABLE id_card_requests (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id          uuid NOT NULL REFERENCES users (id),
  card_image_key   text,                    -- NULL เมื่อรูปถูกลบ (7 วันหลังตัดสิน)
  selfie_image_key text,
  status           text NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
  reject_reason    text,
  reviewed_by      uuid REFERENCES users (id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL)
);
-- รอตรวจได้ทีละคำขอ
CREATE UNIQUE INDEX id_card_requests_one_pending ON id_card_requests (user_id) WHERE status = 'PENDING';

-- ============================================================
-- สินค้า
-- ============================================================

CREATE TABLE categories (
  id        smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  parent_id smallint REFERENCES categories (id),
  name_th   text NOT NULL,
  sort      smallint NOT NULL DEFAULT 0
);
CREATE INDEX categories_parent_idx ON categories (parent_id, sort);

CREATE TABLE tags (
  id          smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name_th     text NOT NULL UNIQUE,
  category_id smallint REFERENCES categories (id)   -- NULL = ใช้ได้ทุกหมวด
);

CREATE TABLE listings (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  seller_id     uuid NOT NULL REFERENCES users (id),
  category_id   smallint NOT NULL REFERENCES categories (id),
  title         text NOT NULL CHECK (char_length(title) BETWEEN 3 AND 100),
  description   text NOT NULL DEFAULT '' CHECK (char_length(description) <= 2000),
  price         integer NOT NULL CHECK (price > 0),
  status        text NOT NULL DEFAULT 'AVAILABLE'
                CHECK (status IN ('AVAILABLE', 'RESERVED', 'SOLD', 'CANCELLED', 'RETURNED', 'TAKEN_DOWN')),
  status_reason text,
  search_text   text NOT NULL DEFAULT '',   -- คำที่ตัดแล้วด้วย Intl.Segmenter (backend อัปเดต)
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  -- ตีกลับ/ถอดต้องมีเหตุผล (BR23)
  CHECK (status NOT IN ('RETURNED', 'TAKEN_DOWN') OR status_reason IS NOT NULL)
);
CREATE INDEX listings_status_created_idx ON listings (status, created_at DESC);
CREATE INDEX listings_seller_idx         ON listings (seller_id, status);
CREATE INDEX listings_category_idx       ON listings (category_id);
CREATE INDEX listings_price_idx          ON listings (price);
CREATE INDEX listings_title_trgm_idx     ON listings USING gin (title gin_trgm_ops);
CREATE INDEX listings_search_fts_idx     ON listings USING gin (to_tsvector('simple', search_text));

CREATE TABLE listing_images (
  listing_id uuid NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  position   smallint NOT NULL CHECK (position BETWEEN 0 AND 4),   -- 1–5 รูป, 0 = ปก
  image_key  text NOT NULL,
  PRIMARY KEY (listing_id, position)
);

CREATE TABLE listing_faculties (
  listing_id uuid     NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  faculty_id smallint NOT NULL REFERENCES faculties (id),
  PRIMARY KEY (listing_id, faculty_id)
);

CREATE TABLE listing_tags (
  listing_id uuid     NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  tag_id     smallint NOT NULL REFERENCES tags (id),
  PRIMARY KEY (listing_id, tag_id)
);

CREATE TABLE favorites (
  user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  listing_id uuid NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, listing_id)
);
CREATE INDEX favorites_listing_idx ON favorites (listing_id);

-- ============================================================
-- ซื้อขายและแชต
-- ============================================================

CREATE TABLE conversations (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id uuid NOT NULL REFERENCES listings (id),
  buyer_id   uuid NOT NULL REFERENCES users (id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (listing_id, buyer_id)                       -- 1 ห้องต่อ (ประกาศ, ผู้ซื้อ) (BR25)
);
CREATE INDEX conversations_buyer_idx ON conversations (buyer_id);

CREATE TABLE deals (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  listing_id   uuid NOT NULL REFERENCES listings (id),
  buyer_id     uuid NOT NULL REFERENCES users (id),
  status       text NOT NULL DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'ACCEPTED', 'HANDED_OVER', 'COMPLETED', 'REJECTED', 'CANCELLED')),
  meetup       text CHECK (char_length(meetup) <= 200),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),    -- ใช้นับ 48 ชม. ของการยืนยันอัตโนมัติ
  completed_at timestamptz,
  CHECK (status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
-- จองได้คนเดียว (BR09): ประกาศหนึ่งมีดีลที่ตกลงแล้วและยังไม่จบได้ดีลเดียว
CREATE UNIQUE INDEX deals_one_active_per_listing ON deals (listing_id)
  WHERE status IN ('ACCEPTED', 'HANDED_OVER');
-- ขายได้ครั้งเดียว
CREATE UNIQUE INDEX deals_one_completed_per_listing ON deals (listing_id)
  WHERE status = 'COMPLETED';
-- ผู้ซื้อคนเดียวมีข้อเสนอที่รอตอบได้ 1 รายการต่อประกาศ
CREATE UNIQUE INDEX deals_one_pending_per_buyer ON deals (listing_id, buyer_id)
  WHERE status = 'PENDING';
CREATE INDEX deals_buyer_idx   ON deals (buyer_id, status);
CREATE INDEX deals_listing_idx ON deals (listing_id, status);

CREATE TABLE messages (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id uuid NOT NULL REFERENCES conversations (id),
  sender_id       uuid REFERENCES users (id),         -- NULL = ข้อความระบบ
  body            text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 1000),
  deal_id         uuid REFERENCES deals (id),         -- มีค่า = เป็นการ์ดดีลในแชต
  client_id       uuid,                               -- กันส่งซ้ำตอนเน็ตหลุด
  read_at         timestamptz,
  created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX messages_client_uniq ON messages (sender_id, client_id) WHERE client_id IS NOT NULL;
CREATE INDEX messages_conversation_idx   ON messages (conversation_id, id DESC);
CREATE INDEX messages_unread_idx         ON messages (conversation_id) WHERE read_at IS NULL;

CREATE TABLE reviews (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  deal_id    uuid NOT NULL UNIQUE REFERENCES deals (id),   -- 1 ดีลรีวิวได้ครั้งเดียว (BR12)
  rating     smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment    text CHECK (char_length(comment) <= 300),
  created_at timestamptz NOT NULL DEFAULT now()
);

-- ============================================================
-- อื่น ๆ
-- ============================================================

CREATE TABLE reports (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_id uuid NOT NULL REFERENCES users (id),
  target_type text NOT NULL CHECK (target_type IN ('LISTING', 'USER', 'MESSAGE')),
  target_id   text NOT NULL,                          -- uuid หรือเลขข้อความ (ตรวจว่ามีจริงในโค้ด)
  reason      text NOT NULL,
  detail      text CHECK (char_length(detail) <= 300),
  snapshot    jsonb,                                  -- สำเนาเป้าหมาย ณ เวลารายงาน
  status      text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
  admin_note  text,
  resolved_by uuid REFERENCES users (id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (target_type, target_id, reporter_id),       -- รายงานซ้ำไม่ได้ (BR13)
  CHECK (status = 'OPEN' OR (admin_note IS NOT NULL AND resolved_by IS NOT NULL))
);
CREATE INDEX reports_status_idx ON reports (status, created_at);

CREATE TABLE notifications (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  type       text NOT NULL,
  body       text NOT NULL,
  ref_id     text,
  is_read    boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user_idx  ON notifications (user_id, id DESC);
CREATE INDEX notifications_dedupe_idx ON notifications (type, ref_id);   -- ส่งเตือนช่วยปิดดีลครั้งเดียวต่อดีล

CREATE TABLE ad_banners (
  id         smallint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  title      text NOT NULL CHECK (char_length(title) BETWEEN 1 AND 100),
  image_key  text NOT NULL,
  link_url   text CHECK (link_url IS NULL OR link_url ~ '^(https://|campusmarket://)'),
  starts_at  timestamptz NOT NULL,
  ends_at    timestamptz NOT NULL,
  is_active  boolean NOT NULL DEFAULT true,
  sort       smallint NOT NULL DEFAULT 100,
  CHECK (ends_at > starts_at)
);
CREATE INDEX ad_banners_active_idx ON ad_banners (is_active, starts_at, ends_at);
