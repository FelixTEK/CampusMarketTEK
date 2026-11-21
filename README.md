# Campus Market

**ภาษาไทย** · [English](#english)

---

## ภาษาไทย

### นี่คือระบบอะไร

**Campus Market** คือแอปพลิเคชันแอนดรอยด์สำหรับ **ตลาดซื้อขายสินค้ามือสองภายในชุมชนสถาบัน** (หนังสือเรียน อุปกรณ์คอมพิวเตอร์ และเครื่องเขียน) ผู้ขายลงประกาศ ผู้ซื้อค้นหา แชตต่อรอง เสนอซื้อ นัดรับสินค้า แล้วยืนยันการรับของเพื่อปิดดีลในระบบ ระบบไม่เกี่ยวข้องกับการชำระเงิน รายได้ของแพลตฟอร์มมาจากแบนเนอร์โฆษณาเท่านั้น

โครงงานนี้เป็นส่วนหนึ่งของรายวิชา **05506215 การโปรแกรมบนระบบปฏิบัติการแอนดรอยด์ (Android Mobile Application Programming)** สาขาวิทยาการคอมพิวเตอร์ คณะวิทยาศาสตร์ สจล. (หัวข้อโปรเจกต์ที่ 5)

จุดเด่นของระบบ

- **ต้องล็อกอินก่อนใช้งาน** คน สจล. เข้าด้วยบัญชีสถาบัน (KMITL SSO) บุคคลภายนอกสมัครด้วยอีเมลและยืนยันตัวตนด้วยบัตรประชาชน (ผู้ดูแลตรวจ) ไม่มีโหมดผู้เยี่ยมชม
- **ค้นหาภาษาไทยได้จริง** ตัดคำด้วย `Intl.Segmenter` ฝั่ง backend ร่วมกับ full-text search และ `pg_trgm` ของ PostgreSQL
- **หมวดหนังสือจัดตามระบบทศนิยมดิวอี้** พร้อมกรองตามคณะและแท็ก
- **ปิดดีลผ่านการ์ดในแชต** ผู้ขายกดส่งมอบ ผู้ซื้อกดได้รับของ (หรือครบ 48 ชั่วโมงระบบปิดให้) แล้วจึงรีวิวได้ ตัวเลขความน่าเชื่อถือของผู้ขายคำนวณจากดีลที่สำเร็จจริง
- **ป้องกันการจองซ้อน** ตกลงขายได้เพียงรายการเดียวต่อประกาศ ตรวจในธุรกรรมฐานข้อมูล
- **ออกแบบตาม PDPA** เก็บข้อมูลส่วนบุคคลน้อยที่สุด เก็บรหัสผ่านเป็น hash (argon2id) และลบรูปบัตรภายใน 7 วันหลังตรวจ

### สถาปัตยกรรมและส่วนประกอบ

```
┌──────────────────────┐   HTTPS / JSON + WebSocket   ┌────────────────────────┐   SQL    ┌─────────────────┐
│ Frontend             │ ───────────────────────────▶ │ Backend / API          │ ───────▶ │ Database        │
│ Android (Kotlin)     │                              │ Elysia + Bun           │          │ PostgreSQL 17   │
│ Jetpack Compose      │ ◀─────────────────────────── │ REST + JWT + WebSocket │ ◀─────── │ (Bun.sql)       │
│ MVVM + Room (cache)  │                              └──────────┬─────────────┘          └─────────────────┘
└──────────────────────┘                                         │ OIDC / REST
                                                      KMITL SSO (Keycloak) · Student API
```

| ส่วน                                    | เทคโนโลยี                                                                     | หน้าที่                                                                                                                                                             |
| --------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Frontend** (โฟลเดอร์ `app/`)          | Kotlin, Jetpack Compose, MVVM + Repository, Hilt, Retrofit, Room, WorkManager | หน้าจอผู้ใช้ จัดการสถานะหน้าจอด้วย ViewModel เรียก API ผ่าน Repository เก็บแคชออฟไลน์ใน Room และส่งข้อความที่ค้างผ่าน WorkManager                                   |
| **Backend / API** (โฟลเดอร์ `backend/`) | Bun, Elysia, JWT, OpenAPI                                                     | รับคำขอจากแอป ตรวจสิทธิ์ ใช้กติกาธุรกิจ (ดีล การจอง การรายงาน) ตัดคำค้นหาภาษาไทย ส่งข้อความแบบเรียลไทม์ด้วย WebSocket และรันงานตั้งเวลา (ปิดดีลอัตโนมัติ ลบรูปบัตร) |
| **Database**                            | PostgreSQL 17 (`pg_trgm`, partial unique index)                               | เก็บข้อมูล 17 ตาราง (ผู้ใช้ ประกาศ ดีล ข้อความ ฯลฯ) ให้ฐานข้อมูลช่วยบังคับกติกา เช่น หนึ่งประกาศมีดีลที่ตกลงได้รายการเดียว                                          |
| **API เชื่อมฐานข้อมูล**                 | `Bun.sql` ในโฟลเดอร์ `backend/src`                                            | ไดรเวอร์ PostgreSQL ในตัวของ Bun ใช้คำสั่ง SQL ตรง ๆ และทำธุรกรรมด้วย `SELECT … FOR UPDATE` สคีมาจัดการด้วยไฟล์ migration ใน `backend/migrations`                   |
| **ระบบภายนอก**                          | KMITL SSO (Keycloak OIDC), Student API                                        | ยืนยันตัวตนและสถานะของนักศึกษา/บุคลากร                                                                                                                              |

### เทียบกับคำอธิบายรายวิชา

| หัวข้อในรายวิชา                          | สิ่งที่โครงงานนี้ทำ                                                                                                                                              |
| ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| การพัฒนาซอฟต์แวร์บนแอนดรอยด์             | แอป Android เต็มรูปแบบที่คุยกับ backend จริง                                                                                                                     |
| สถาปัตยกรรม Model-View-Controller        | ใช้ **MVVM** (วิวัฒนาการของ MVC ที่แนะนำสำหรับแอนดรอยด์): **Model** = Repository + Room + Retrofit, **View** = Composable, **ViewModel** = ตัวกลางถือสถานะหน้าจอ |
| การเขียนโปรแกรมภาษาจาวา                  | ใช้ **Kotlin** ซึ่งเป็นภาษาหลักที่ Google แนะนำบนแอนดรอยด์ และทำงานร่วมกับ Java/JVM ได้                                                                          |
| ปฏิสัมพันธ์ระหว่างผู้ใช้กับซอฟต์แวร์, UI | Jetpack Compose, การค้นหา กรอง เรียง, แชต, การ์ดดีล                                                                                                              |
| แอนิเมชัน                                | แบนเนอร์โฆษณาแบบหมุนเวียน (carousel) และการเปลี่ยนสถานะของหน้าจอ                                                                                                 |
| มัลติมีเดีย                              | ถ่ายและอัปโหลดรูปสินค้า รูปบัตรและเซลฟีจากกล้อง                                                                                                                  |
| การประมวลผลส่วนหลัง                      | WorkManager (ส่งข้อความที่ค้าง) และงานตั้งเวลาฝั่ง server                                                                                                        |
| การเข้าถึงข้อมูล                         | Room (แคชในเครื่อง), Retrofit (เรียก REST API), PostgreSQL ฝั่ง server                                                                                           |
| AR                                       | ไม่อยู่ในขอบเขตของโครงงานนี้                                                                                                                                     |

### สถานะปัจจุบัน

เป็น **proof of concept** ที่ฟังก์ชันครบตามขอบเขตของแผนและทดสอบอัตโนมัติผ่าน (backend 249 เทสต์) แต่ยังไม่ใช่ระบบ production

- **ทำเสร็จแล้ว:** ล็อกอิน (KMITL SSO และบุคคลภายนอก) การยืนยันตัวตนด้วยบัตร ลงประกาศ ค้นหา รายการโปรด แชตและดีล แจ้งเตือน รีวิว รายงาน แดชบอร์ดผู้ขาย แบนเนอร์โฆษณา ฟังก์ชัน PDPA (ดาวน์โหลดและลบข้อมูล) และฝั่งผู้ดูแล (ตรวจบัตร รายงาน ระงับผู้ใช้ ตั้ง/ถอนผู้ดูแล แบนเนอร์ สถิติ) พร้อมคำเตือนประกาศซ้ำ (ข้อความเหมือนเดิม 100%)
- **ข้อมูลทดสอบ:** มี SSO จำลอง (`KMITL_DUMMY=true`, นักศึกษา 18 คน) และสคริปต์ seed ผู้ขาย 20 คน ผู้ซื้อ 10 คน ประกาศ 64 รายการ และแบนเนอร์ ดู [docs/SEED_ACCOUNTS.md](docs/SEED_ACCOUNTS.md)
- **ยังไม่ได้ทำ/ยังไม่ได้ทดสอบ:** SSO จริงของ สจล. (ยังรอ client และ API key), การถ่ายบัตรด้วยกล้องจริง และงานเตรียมส่ง (รายงานฉบับสุดท้าย Sequence/State/Deployment Diagram สไลด์)

### เอกสาร

| ไฟล์                                         | เนื้อหา                                               |
| -------------------------------------------- | ----------------------------------------------------- |
| [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) | แผนงาน ข้อกำหนด กติกาธุรกิจ API และ checklist รายเฟส  |
| [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md)       | แผนภาพ ER และรายละเอียดตาราง                          |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md)       | Actor, Use Case, workflow, Sequence และ State Diagram |
| [docs/PROJECT_SUMMARY.md](docs/PROJECT_SUMMARY.md) | สรุปโครงงานจากระบบที่ทำจริง |
| [docs/TEST_CASES.md](docs/TEST_CASES.md)     | กรณีทดสอบ |
| [docs/MANUAL_TEST_GUIDE.md](docs/MANUAL_TEST_GUIDE.md) | ขั้นตอนทดสอบด้วยมือบนแอป |
| [docs/SEED_ACCOUNTS.md](docs/SEED_ACCOUNTS.md) | บัญชีทดสอบและข้อมูลสาธิต |
| [docs/INCIDENT_RESPONSE.md](docs/INCIDENT_RESPONSE.md) | แผนรับมือเหตุข้อมูลรั่ว |

### วิธีรันบนเครื่องพัฒนา

ต้องมี Android Studio, [Bun](https://bun.sh), Docker และอีมูเลเตอร์ (หรืออุปกรณ์จริง)

```bash
# 1. ฐานข้อมูล: คัดลอกค่าตั้งต้นแล้วใส่รหัสผ่านและคีย์ของคุณเอง
cd backend
cp .env.example .env
docker compose up -d

# 2. backend
bun install
bun run migrate
bun run dev        # http://localhost:3000/health

# 3. แอป: ให้อีมูเลเตอร์เข้าถึง backend บนเครื่องผ่าน localhost (Windows)
#    powershell scripts/adb-reverse.ps1
#    แล้วรันแอปจาก Android Studio

# ทดสอบ backend
bun test

# (ทางเลือก) SSO จำลอง + ข้อมูลสาธิต
#   ตั้ง KMITL_DUMMY=true ใน backend/.env แล้วรัน
bun scripts/seed-demo.ts      # ผู้ขาย 20 · ผู้ซื้อ 10 · ประกาศ 64 (--remove เพื่อล้าง)
bun scripts/seed-banners.ts   # แบนเนอร์ตัวอย่าง 5 รายการ
bun scripts/dev-admin.ts admin@campusmarket.test "รหัสผ่านอย่างน้อย 8 ตัว"   # สร้าง/รีเซ็ตผู้ดูแล
```

ฐานข้อมูลจะใช้ Docker ตามด้านบน หรือจะใช้ PostgreSQL ที่อื่นก็ได้ โดยแก้ `DATABASE_URL` ใน `backend/.env` (ค่าตั้งต้นคือ `localhost:5433`)

---

## English

### What is this?

**Campus Market** is an Android app for a **second-hand marketplace inside a campus community** (textbooks, computer gear, stationery). Sellers post listings; buyers search, chat, negotiate, make an offer and arrange a hand-over; the deal is closed inside the app when the buyer confirms receipt. The system never handles payments. The platform's only revenue is banner advertising.

This project is part of the course **05506215 Android Mobile Application Programming** at the Computer Science, School of Science, KMITL (project topic 5).

Highlights

- **Login is required.** KMITL members sign in through the institute's SSO. Outsiders register with an email address and verify their identity with a national ID card photo reviewed by an administrator. There is no guest mode.
- **Real Thai-language search.** The backend segments Thai text with `Intl.Segmenter` and combines PostgreSQL full-text search with `pg_trgm`.
- **Books follow the Dewey Decimal Classification**, with filters by faculty and tags.
- **Deals are closed through in-chat cards.** The seller marks the item handed over, the buyer confirms receipt (or the system closes it after 48 hours), and only then can a review be written. Seller credibility figures come from completed deals only.
- **No double booking.** Only one offer per listing can be accepted, enforced in a database transaction.
- **Privacy by design (Thai PDPA).** Minimal personal data, argon2id password hashes, ID photos deleted 7 days after review.

### Architecture and components

```
┌──────────────────────┐   HTTPS / JSON + WebSocket   ┌────────────────────────┐   SQL    ┌─────────────────┐
│ Frontend             │ ───────────────────────────▶ │ Backend / API          │ ───────▶ │ Database        │
│ Android (Kotlin)     │                              │ Elysia + Bun           │          │ PostgreSQL 17   │
│ Jetpack Compose      │ ◀─────────────────────────── │ REST + JWT + WebSocket │ ◀─────── │ (Bun.sql)       │
│ MVVM + Room (cache)  │                              └──────────┬─────────────┘          └─────────────────┘
└──────────────────────┘                                         │ OIDC / REST
                                                      KMITL SSO (Keycloak) · Student API
```

| Part                           | Technology                                                                    | Responsibility                                                                                                                                                                                                      |
| ------------------------------ | ----------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Frontend** (`app/`)          | Kotlin, Jetpack Compose, MVVM + Repository, Hilt, Retrofit, Room, WorkManager | UI screens; screen state in ViewModels; API calls through repositories; offline cache in Room; queued messages sent by WorkManager                                                                                  |
| **Backend / API** (`backend/`) | Bun, Elysia, JWT, OpenAPI                                                     | Receives app requests, authorizes, applies business rules (deals, reservations, reports), segments Thai search terms, pushes real-time chat over WebSocket, runs scheduled jobs (auto-close deals, purge ID photos) |
| **Database**                   | PostgreSQL 17 (`pg_trgm`, partial unique indexes)                             | 17 tables (users, listings, deals, messages, …); the database itself enforces rules such as one accepted deal per listing                                                                                           |
| **API-to-database layer**      | `Bun.sql` in `backend/src`                                                    | Bun's built-in PostgreSQL driver; plain SQL; transactions with `SELECT … FOR UPDATE`; schema managed by migration files in `backend/migrations`                                                                     |
| **External systems**           | KMITL SSO (Keycloak OIDC), Student API                                        | Verify the identity and status of students and staff                                                                                                                                                                |

### Mapping to the course description

| Course topic                       | What this project does                                                                                                                                                       |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android software development       | A full Android app talking to a real backend                                                                                                                                 |
| Model-View-Controller architecture | Uses **MVVM**, the Android-recommended evolution of MVC: **Model** = Repository + Room + Retrofit, **View** = Composables, **ViewModel** = the mediator holding screen state |
| Java programming                   | Uses **Kotlin**, Google's preferred Android language, fully interoperable with Java/JVM                                                                                      |
| User interaction and mobile UI     | Jetpack Compose; search, filter, sort; chat; deal cards                                                                                                                      |
| Animation                          | Rotating ad-banner carousel and screen state transitions                                                                                                                     |
| Multimedia                         | Camera capture and upload of listing photos, ID card photos and selfies                                                                                                      |
| Background processing              | WorkManager (queued messages) and server-side scheduled jobs                                                                                                                 |
| Data access                        | Room (local cache), Retrofit (REST API), PostgreSQL on the server                                                                                                            |
| Augmented reality                  | Out of scope for this project                                                                                                                                                |

### Current status

A **proof of concept**: functionally complete against the plan's scope with automated tests passing (249 backend tests), but not production-ready.

- **Done:** login (KMITL SSO and outsiders), ID-card verification, listings, search, favourites, chat and deals, notifications, reviews, reports, seller dashboard, ad banners, PDPA functions (data download and deletion), and the admin side (ID review, reports, banning users, promoting/demoting admins, banners, statistics), plus a warning for exact duplicate listings (100% identical text).
- **Test data:** a dummy SSO (`KMITL_DUMMY=true`, 18 students) and seed scripts for 20 sellers, 10 buyers, 64 listings and banners. See [docs/SEED_ACCOUNTS.md](docs/SEED_ACCOUNTS.md).
- **Not done / not verified:** the real KMITL SSO (waiting for a client and API key), ID capture with a real camera, and submission work (final report, Sequence/State/Deployment diagrams, slides).

### Documentation

| File                                         | Contents                                                              |
| -------------------------------------------- | --------------------------------------------------------------------- |
| [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) | Plan, requirements, business rules, API contract and phase checklists |
| [docs/DB_SCHEMA.md](docs/DB_SCHEMA.md)       | ER diagram and table details                                          |
| [docs/WORKFLOWS.md](docs/WORKFLOWS.md)       | Actors, use cases, workflows, sequence and state diagrams             |
| [docs/PROJECT_SUMMARY.md](docs/PROJECT_SUMMARY.md) | Project summary based on the system as built                    |
| [docs/TEST_CASES.md](docs/TEST_CASES.md)     | Test cases                                                            |
| [docs/MANUAL_TEST_GUIDE.md](docs/MANUAL_TEST_GUIDE.md) | Step-by-step manual testing on the app                      |
| [docs/SEED_ACCOUNTS.md](docs/SEED_ACCOUNTS.md) | Test accounts and demo data                                         |
| [docs/INCIDENT_RESPONSE.md](docs/INCIDENT_RESPONSE.md) | Data-breach response plan                                   |

### Running locally

Requires Android Studio, [Bun](https://bun.sh), Docker and an emulator (or a real device).

```bash
# 1. Database: copy the template and set your own password and secrets
cd backend
cp .env.example .env
docker compose up -d

# 2. Backend
bun install
bun run migrate
bun run dev        # http://localhost:3000/health

# 3. App: let the emulator reach the host backend via localhost (Windows)
#    powershell scripts/adb-reverse.ps1
#    then run the app from Android Studio

# Backend tests
bun test

# (Optional) dummy SSO + demo data: set KMITL_DUMMY=true in backend/.env, then
bun scripts/seed-demo.ts      # 20 sellers, 10 buyers, 64 listings (--remove to clear)
bun scripts/seed-banners.ts   # 5 sample banners
bun scripts/dev-admin.ts admin@campusmarket.test "at-least-8-char-password"   # create/reset an admin
```

The database can run in Docker as above, or on any PostgreSQL you point `DATABASE_URL` in `backend/.env` at (default `localhost:5433`).
