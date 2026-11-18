import { Elysia, t } from "elysia";
import { DUMMY_STUDENTS, dummyStudentApi, issueDummyCode, renderDummyLogin } from "../kmitl/dummy";
import { normalizeKmitlEmail } from "../kmitl/student";

const html = (body: string, status = 200) =>
  new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });

/**
 * เส้นทางของ "สถาบันจำลอง" — ติดตั้งเฉพาะเมื่อ KMITL_DUMMY=true (ดู app.ts) ใช้ใน production ไม่ได้ (env.ts ไม่ยอมเริ่ม)
 *  - หน้าล็อกอินจำลอง แทนหน้า sso.kmitl.ac.th
 *  - Student API จำลอง: GET /dummy/student-catalog/v1/students[/{id}] (ต้องมี header apikey) — curl ดูผลได้
 */
export const kmitlDummyRoutes = (redirectUri: string) =>
  new Elysia({ name: "routes-kmitl-dummy" })
    .get("/auth/kmitl/dummy/login", ({ query }) => html(renderDummyLogin({ state: query.state })), {
      query: t.Object({ state: t.String({ minLength: 1, maxLength: 200 }) }),
    })

    .post(
      "/auth/kmitl/dummy/login",
      ({ body }) => {
        const student = DUMMY_STUDENTS.find((s) => String(s.student_id) === body.studentId);
        const typed = body.email?.trim() ?? "";
        const email = normalizeKmitlEmail(typed);
        if (!student) return html(renderDummyLogin({ state: body.state, error: "กรุณาเลือกนักศึกษา", email: typed }), 400);
        if (typed && !email)
          return html(renderDummyLogin({ state: body.state, selected: body.studentId, email: typed, error: "อีเมลต้องลงท้ายด้วย @kmitl.ac.th และมีเฉพาะตัวอักษร ตัวเลข . _ - (เช่น 69010012@kmitl.ac.th)" }), 400);

        const code = issueDummyCode(body.studentId, email);
        const back = new URL(redirectUri);
        back.searchParams.set("code", code);
        back.searchParams.set("state", body.state);
        return new Response(null, { status: 302, headers: { location: back.toString() } });
      },
      { body: t.Object({ state: t.String({ minLength: 1, maxLength: 200 }), studentId: t.String(), email: t.Optional(t.String()) }) },
    )

    .get("/dummy/student-catalog/v1/students", ({ headers, set }) => {
      if (!headers.apikey) { set.status = 401; return { message: "Unauthorized" }; }
      return DUMMY_STUDENTS;
    })
    .get("/dummy/student-catalog/v1/students/:id", ({ params, headers, set }) => {
      const r = dummyStudentApi(params.id, headers.apikey);
      set.status = r.status;
      return r.body;
    });
