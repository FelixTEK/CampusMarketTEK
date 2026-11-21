# สคริปต์เดโม Campus Market (30 นาที)

> อีมูเลเตอร์มีเครื่องเดียว จึงเล่นสองบทบาทโดย **สลับบัญชี** (ออกจากระบบ → ล็อกอินใหม่ ใช้เวลาประมาณ 30 วินาที)
> TC-xx = รหัสใน [TEST_CASES.md](TEST_CASES.md) · บัญชีทั้งหมดอยู่ใน [SEED_ACCOUNTS.md](SEED_ACCOUNTS.md)

## เตรียมก่อนขึ้นเดโม (ทำล่วงหน้า 15 นาที)

```powershell
& "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe" startvm DebianAndroid --type headless   # ฐานข้อมูล
cd D:\Documents\GitHub\CampusMarketTEK\backend
bun scripts/seed-demo.ts                       # ผู้ขาย 20 · ผู้ซื้อ 10 · ประกาศ 64
bun scripts/seed-banners.ts                    # แบนเนอร์ 5 รายการ
bun scripts/dev-admin.ts admin@campusmarket.test "<รหัสที่จำได้>"
bun run test:clean                             # รันไว้ก่อน เปิดหน้าต่างผลค้างไว้โชว์ (249 pass)
bun run dev                                    # backend (ต้องมี KMITL_DUMMY=true ใน .env)
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" reverse tcp:3000 tcp:3000
```

- [ ] เปิดแอปค้างไว้ที่หน้าล็อกอิน · จอตั้ง (`adb shell wm user-rotation lock 0`)
- [ ] เตรียมรูปสินค้า 1–2 รูปไว้ในแกลเลอรี่ของอีมูเลเตอร์
- [ ] มีบัญชีบุคคลภายนอกที่ "รอยืนยันตัวตน" อยู่ 1 บัญชี (ไว้โชว์ผู้ดูแลอนุมัติบัตร) — สมัครและส่งรูปบัตรไว้ก่อน
- [ ] มีรายงานค้างในคิวอย่างน้อย 1 เรื่อง (รายงานประกาศใดก็ได้ไว้ก่อน)

| บทบาท | ล็อกอิน |
|---|---|
| ผู้ซื้อ (สจล.) | ปุ่ม KMITL → การ์ด **ศุภณัฐ มณีรัตน์ 66014667** |
| ผู้ขาย (บุคคลภายนอก) | `somchai.c@example.test` / `TestPass-12345` |
| ผู้ดูแล | `admin@campusmarket.test` / รหัสที่ตั้งไว้ |
| ถูกปฏิเสธ | การ์ด **John Doe 56070069** (สถานภาพ "จบ") |

---

## ลำดับเดโม

| เวลา | ฉาก | ทำอะไร | พูดอะไร (ประเด็นเดียวพอ) | TC |
|---|---|---|---|---|
| 0:00–2:00 | **เปิดเรื่อง** | สไลด์ปัญหา + รูปสถาปัตยกรรม | ซื้อขายของมือสองในกลุ่มแชตหาของยาก ไม่รู้ว่าคู่ค้าเป็นใคร จองซ้อน → แอปนี้ยืนยันตัวตนทุกคน สถานะสินค้าระบบคุม ปิดดีลในแอป ไม่ยุ่งเรื่องเงิน | — |
| 2:00–5:00 | **ล็อกอิน 2 แบบ** | ① ปุ่ม KMITL → เลือก John Doe → แอปขึ้นข้อความปฏิเสธ ② ปุ่ม KMITL → เลือกศุภณัฐ → เข้าหน้าแรกได้ | คน สจล. เข้าด้วย SSO แล้วระบบเช็กสถานภาพจาก Student API คนที่จบแล้วเข้าไม่ได้ (หน้านี้เป็นสถาบันจำลอง เพราะยังไม่ได้ client จริง) | TC-05 |
| 5:00–8:00 | **หน้าแรก + ค้นหา** | ดู carousel แบนเนอร์ (หมุนเอง กดค้างหยุด) → ค้นคำไทย เช่น `แคลคูลัส` หรือ `หนังสือ` → กรองหมวด/ราคา → เรียง → กดหัวใจ | ค้นภาษาไทยได้จริงเพราะ backend ตัดคำด้วย `Intl.Segmenter` + full-text search · ปุ่มกรองบอกจำนวนผลก่อนกด | TC-15 TC-17 TC-64 TC-73 |
| 8:00–10:00 | **ดูประกาศ + เริ่มดีล** | เปิดประกาศของสมชาย → ดูโปรไฟล์ผู้ขาย (ขายสำเร็จ/อัตราปิด) → **ทักแชต** → พิมพ์ข้อความ → **เสนอซื้อ** | ตัวเลขความน่าเชื่อถือคิดจากดีลที่สำเร็จจริงเท่านั้น | TC-23 TC-33 |
| 10:00–14:00 | **ฝั่งผู้ขาย** | ออกจากระบบ → ล็อกอินสมชาย → ดู badge แจ้งเตือน → เข้าแชต → **ตกลง** → **นัดรับ** (เวลา/สถานที่) → **ส่งมอบแล้ว** | ตกลงขายได้คนเดียวต่อประกาศ ข้อเสนออื่นถูกปฏิเสธอัตโนมัติ ประกาศเปลี่ยนเป็น "ถูกจอง" · กันจองซ้อนในฐานข้อมูล (`SELECT … FOR UPDATE`) ทดสอบยิงพร้อมกันแล้ว | TC-24 TC-25 TC-30 TC-31 TC-68 |
| 14:00–17:00 | **ลงประกาศ** (ยังเป็นสมชาย) | แท็บลงขาย → หมวดหนังสือ (ต้องเลือกคณะ/แท็ก) → ใส่รูป ราคา สภาพ → โพสต์ → ลองโพสต์ไม่มีรูปให้เห็น error | ฟอร์มตรวจทั้งในแอปและที่ server · ถ้าเน็ตหลุดตอนโพสต์ แอปเก็บเป็นแบบร่างแล้วส่งเองทีหลัง (WorkManager) | TC-09 TC-10 TC-63 |
| 17:00–19:00 | **ปิดดีล + รีวิว** | ออก → ล็อกอินศุภณัฐ → เข้าแชต → **ได้รับแล้ว** → ให้ 4 ดาว → เปิดโปรไฟล์ผู้ขายดู 4.0 | ถ้าผู้ซื้อไม่กดภายใน 48 ชม. ระบบปิดดีลให้ · รีวิวได้เฉพาะดีลที่สำเร็จ | TC-24 TC-27 TC-69 |
| 19:00–20:00 | **รายงาน** | กดค้างข้อความในแชต → รายงาน → ลองรายงานซ้ำ | รายงานซ้ำไม่ได้ ไปเข้าคิวผู้ดูแล | TC-34 TC-70 |
| 20:00–25:00 | **ผู้ดูแล** | ออก → ล็อกอิน admin → ① ตรวจบัตร → อนุมัติ (บอกว่าหน้านี้กันแคปจอ) ② คิวรายงาน → ดูสำเนาแชต → ระงับผู้ใช้ ③ "ผู้ใช้และสิทธิ์" → กรอง "ถูกระงับ" → ปลดระงับ → ตั้งคนหนึ่งเป็นผู้ดูแล ④ สถิติแพลตฟอร์ม | รูปบัตรเข้ารหัส AES-256-GCM และลบเองภายใน 7 วันหลังตัดสิน · ทุกการกระทำของผู้ดูแลถูกบันทึก · ผู้ดูแลถอนสิทธิ์ตัวเองไม่ได้ | TC-61 TC-71 TC-77 TC-78 TC-72 |
| 25:00–27:00 | **PDPA + ความทนทาน** | แท็บฉัน → ดาวน์โหลดข้อมูลของฉัน (JSON) · (ถ้ามีเวลา) หยุด backend → เปิดแชต เห็นข้อมูลเดิม + แถบ "เชื่อมต่อไม่ได้" → เปิดกลับ | เก็บข้อมูลน้อยที่สุด ผู้ใช้ขอข้อมูลและลบบัญชีเองได้ · มีแคชใน Room | TC-38 TC-74 TC-76 |
| 27:00–28:00 | **ผลทดสอบ** | สลับไปหน้าต่าง `bun run test:clean` ที่รันไว้ | backend 249 เทสต์ผ่าน ยิงกับฐานข้อมูลจริง รวมเทสต์ที่วนทุก API โดยไม่มี token ต้องได้ 401 | TC-50–55 |
| 28:00–30:00 | **ปิด + ข้อจำกัด** | สไลด์สรุป | ที่ยังไม่ได้ทำ: SSO จริง (รอ client) · กล้องจริง (กล้องอีมูเลเตอร์ล้ม) · push จริง (FCM) | §5 |

**ถ้าเวลาไม่พอ ตัดตามลำดับนี้:** ความทนทาน (TC-76) → รายงาน → ลองโพสต์ผิด → สถิติ
**ห้ามลบบัญชีในเดโม** (TC-74 ลบจริง) ถ้าจะโชว์ให้ใช้บัญชีทิ้งที่สร้างไว้

---

## เตรียมตอบคำถาม

### 1. Bun / Elysia คืออะไร ใช้แทน FastAPI ทำไม

**คืออะไร (เทียบให้เห็นภาพ)**

| ฝั่ง Python | ฝั่งเรา | หน้าที่ |
|---|---|---|
| Python + uvicorn | **Bun** | ตัวรันโปรแกรม (runtime) ของ JavaScript/TypeScript แบบเดียวกับ Node.js |
| FastAPI | **Elysia** | เว็บเฟรมเวิร์กสำหรับเขียน API |
| Pydantic | `t.Object(...)` ของ Elysia | ตรวจรูปแบบข้อมูลที่ส่งเข้ามา |
| `/docs` (Swagger) | `/openapi` | เอกสาร API สร้างอัตโนมัติ |
| psycopg / SQLAlchemy | `Bun.sql` (มากับ Bun) | คุยกับ PostgreSQL |
| pytest | `bun test` (มากับ Bun) | เทสต์ |

**ทำงานยังไง (คำขอหนึ่งครั้ง):** แอปส่ง HTTP มา → Elysia ผ่านปลั๊กอินตามลำดับใน `app.ts` (จัดการ error → บันทึก log → CORS) → เข้า route เช่น `routes/deals.ts` → ด่านตรวจสิทธิ์ `beforeHandle` (ล็อกอินแล้วไหม ยืนยันตัวตนแล้วไหม เป็นผู้ดูแลไหม) → ตรวจรูปแบบ body → handler เขียน SQL ตรง ๆ ใน transaction → ตอบ JSON และส่ง WebSocket ไปหาอีกฝ่าย

**เลือกเพราะ (ตอบตรง ๆ ไม่อวดเกิน)**
- **ภาษาเดียวกันทั้งระบบ เป็น TypeScript มี type:** ฟิลด์ผิดชื่อจะรู้ตั้งแต่ตอนเขียน
- **ตัดคำไทยได้โดยไม่ต้องลงไลบรารีเพิ่ม:** `Intl.Segmenter` มากับ Bun อยู่แล้ว ถ้าเป็น FastAPI ต้องลง PyThaiNLP
- **ของจำเป็นมาในตัวเดียว:** ไดรเวอร์ฐานข้อมูล ตัวรันเทสต์ ตัวจัดการแพ็กเกจ และ WebSocket ติดตั้งน้อย เริ่มเร็ว
- **ข้อเสียที่ยอมรับ:** ecosystem ของ FastAPI ใหญ่และนิ่งกว่า Bun ยังใหม่ ส่วนความเร็วไม่ใช่เหตุผลหลัก เพราะงานระดับ proof of concept คอขวดไม่ได้อยู่ที่เฟรมเวิร์ก

### 2. ทำไมใช้ SSO จริงไม่ได้

- **ระบบจริงต้องมีกุญแจสองอย่าง** จาก KMITL Developer Platform (ดูแลโดย KDMC)
  1. **SSO client** (Client ID/Secret) — ลองแล้ว Keycloak ของสถาบันตอบ **"Client not found"** แปลว่า client ยังใช้ไม่ได้
  2. **API key ของ Student API** ไว้เช็กสถานภาพนักศึกษา — ต้องรออนุมัติ 1–3 วันทำการและเซ็นข้อตกลงการใช้ข้อมูล ตอนนี้ตอบ **unauthorized**
- **ข้อจำกัดเพิ่มเติม:** SSO ให้ข้อมูลพื้นฐานแค่ชื่อและอีเมล รหัสนักศึกษาและคณะต้องขอ KDMC เพิ่ม และ redirect URI ต้องเป็น http(s) ใช้ `campusmarket://` ตรง ๆ ไม่ได้ เราจึงให้ backend รับผลแทนแล้วค่อยส่งต่อเข้าแอป
- **โค้ดจริงพร้อมแล้ว:** `kmitl/client.ts` คุยกับ Keycloak แบบ OIDC + PKCE ตัวจำลอง `kmitl/dummy.ts` ทำตาม interface เดียวกัน `app.ts` เลือกตัวใดตัวหนึ่งตาม env พอได้ client จริงก็แค่ปิด `KMITL_DUMMY` แล้วใส่ Client ID/Secret ใน `.env` แอปไม่ต้องแก้
- **ตัวจำลองใช้ใน production ไม่ได้:** ถ้าตั้ง `NODE_ENV=production` คู่กับ `KMITL_DUMMY=true` backend ไม่ยอมเริ่ม (`env.ts`)

### 3. หน้า mockup SSO เปิดจากไหน ยังไง

**backend เป็นคนเสิร์ฟหน้านี้เอง** (HTML ธรรมดา ไม่ได้อยู่ในแอป) และมีเฉพาะตอน `KMITL_DUMMY=true`

```
แอป: ปุ่ม "เข้าสู่ระบบด้วยบัญชี KMITL"  (ui/auth/SsoLinkScreen.kt)
  → เปิดเบราว์เซอร์ไปที่  GET /auth/kmitl/start            (routes/auth.ts)
  → backend redirect ไป  GET /auth/kmitl/dummy/login?state=…  (routes/kmitl-dummy.ts → renderDummyLogin ใน kmitl/dummy.ts)
       ← ตรงนี้คือ "หน้าสถาบันจำลอง" ระบบจริงจะเป็น sso.kmitl.ac.th
  → เลือกการ์ด → POST กลับ → redirect ไป /auth/kmitl/callback?code&state
  → backend เช็ก state, เรียก Student API (จำลอง) เช็กสถานภาพ, สร้าง/หาบัญชี
  → redirect ไป  campusmarket://auth?code=<รหัสใช้ครั้งเดียว>
  → Android จับ deep link (AndroidManifest + MainActivity.kt)
  → แอปส่ง POST /auth/exchange แลกเป็น JWT → เข้าหน้าแรก
```

- **ทำไมไม่ส่ง token ใน URL ตรง ๆ:** ส่งเป็นรหัสครั้งเดียวที่หมดอายุเร็ว แล้วให้แอปไปแลกเอง URL จะได้ไม่มีความลับติดไป
- **โชว์ข้อมูลจำลองบนคอม:** เปิด `http://localhost:3000/dummy/student-catalog/v1/students` โดยใส่ header `apikey` เป็นค่าอะไรก็ได้ จะได้ JSON รูปแบบเดียวกับ Student API จริง
- **แก้รายชื่อนักศึกษา:** แก้ที่ `DUMMY_STUDENTS` ใน `backend/src/kmitl/dummy.ts`

### 4. ขอดูโค้ด — จะไล่ยังไง

**ไล่ "หนึ่งคำขอ" จากปุ่มถึงฐานข้อมูล** อย่าเปิดทีละโฟลเดอร์ ตัวอย่างที่ดีที่สุดคือ **ผู้ขายกด "ตกลง"** เพราะผ่านทุกชั้น

| ลำดับ | ไฟล์ | ชั้น | ทำอะไร |
|---|---|---|---|
| 1 | `app/.../ui/chat/DealCard.kt` | View (Compose) | ปุ่ม "ตกลงขาย" บนการ์ดดีลในแชต |
| 2 | `app/.../ui/chat/ChatRoomViewModel.kt` | ViewModel | `accept()` ถือสถานะหน้าจอ กันกดซ้ำ |
| 3 | `app/.../data/repository/ChatRepository.kt` | Repository (Model) | ตัวกลางระหว่าง API กับ Room |
| 4 | `app/.../data/remote/ChatApi.kt` | Retrofit | นิยามเส้นทาง HTTP |
| 5 | `backend/src/app.ts` | Elysia | ประกอบทุก route และปลั๊กอิน |
| 6 | `backend/src/routes/deals.ts` | Route | รับคำขอ ตรวจสิทธิ์ ตรวจ body |
| 7 | `backend/src/chat/deals.ts` | กติกาธุรกิจ | transaction + `SELECT … FOR UPDATE` กันจองซ้อน ปฏิเสธข้อเสนออื่น |
| 8 | `backend/src/listings/status.ts` | กติกาธุรกิจ | ตารางว่าใครเปลี่ยนสถานะประกาศจากอะไรไปอะไรได้ |
| 9 | `backend/src/realtime/hub.ts` + `notifications.ts` | Realtime | ส่ง WebSocket และบันทึกแจ้งเตือนให้อีกฝ่าย |
| 10 | `backend/migrations/001_init.sql` | Database | 17 ตาราง + partial unique index (หนึ่งประกาศตกลงได้ดีลเดียว) |

**แผนที่โฟลเดอร์ (ตอบว่า "โค้ดไหนทำอะไร")**

| ฝั่งแอป `app/src/main/java/com/example/campusmarkettek/` | |
|---|---|
| `ui/<หน้าจอ>/` | หน้าจอ Compose + ViewModel แยกตามฟีเจอร์ (auth, home, listing, chat, admin, profile, …) |
| `ui/navigation/MainShell.kt` | แท็บล่างและเส้นทางระหว่างหน้า |
| `data/remote/` | Retrofit API + `AuthInterceptor` แนบ JWT + `RealtimeClient` (WebSocket) |
| `data/repository/` | Repository ตัวกลางที่ ViewModel เรียก |
| `data/local/` | Room (แคช แบบร่าง ข้อความที่รอส่ง) |
| `work/` | WorkManager: `SyncWorker` ส่งของที่ค้าง · `NotificationWorker` |
| `di/` | Hilt ประกอบ dependency |

| ฝั่ง backend `backend/src/` | |
|---|---|
| `index.ts` / `app.ts` | จุดเริ่ม / ประกอบ app (แยกไว้ให้เทสต์เรียก `app.handle()` ได้โดยไม่เปิดพอร์ต) |
| `routes/` | เส้นทาง API ไฟล์ละเรื่อง (`admin-*` = ของผู้ดูแล) |
| `auth/` | JWT และด่านตรวจสิทธิ์ (`requireAuth` / `requireVerified` / `requireAdmin`) |
| `kmitl/` | SSO จริง (`client.ts`) · ตัวจำลอง (`dummy.ts`) · เช็กสถานภาพ (`student.ts`) |
| `chat/` `listings/` | กติกาดีลและสถานะประกาศ |
| `search/tokenize.ts` | ตัดคำไทย |
| `identity/` | เข้ารหัสรูปบัตร |
| `jobs/` | งานตั้งเวลา: ปิดดีล 48 ชม. · เตือน · ลบรูปบัตร 7 วัน · ลบข้อมูลเก่า |
| `backend/tests/` | เทสต์ 249 ตัว แยกไฟล์ตามเรื่อง |

**ถ้าถูกถามเรื่องเฉพาะ เปิดไฟล์นี้เลย**
- **กันจองซ้อน:** `backend/src/chat/deals.ts`
- **ค้นหาไทย:** `backend/src/search/tokenize.ts` + `routes/browse.ts`
- **ตรวจสิทธิ์:** `backend/src/auth/plugin.ts`
- **MVVM หนึ่งหน้า:** `ui/listing/ListingFormScreen.kt` + `ListingFormViewModel.kt`
- **ส่งตอนออฟไลน์:** `work/SyncWorker.kt`
- **สแปมซ้ำ:** `backend/src/routes/listings.ts` (ค้น `DUPLICATE_WARN_AFTER`)
