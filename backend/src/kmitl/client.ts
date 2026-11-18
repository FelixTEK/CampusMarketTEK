import { createRemoteJWKSet, jwtVerify } from "jose";
import { env } from "../env";
import { parseStudentInfo, type StudentInfo } from "./student";

export type { StudentInfo };

export type KmitlProfile = {
  sub: string;
  /** preferred_username — ถ้าเป็นเลข 8 หลักถือว่าเป็นรหัสนักศึกษา (ต้องตรวจตอนล็อกอินจริงครั้งแรก) */
  username: string | null;
  givenName: string | null;
  /** อีเมลสถาบันจาก claim `email` (ถ้ามี) — ใช้เป็นค่าตั้งต้นของอีเมลบัญชี */
  email?: string | null;
};

/** ตัวคุยกับระบบของสถาบัน — แยกเป็น interface เพื่อให้ทดสอบ flow ด้วยตัวปลอมได้โดยไม่ต้องมี SSO จริง */
export interface KmitlClient {
  authorizeUrl(p: { state: string; nonce: string; challenge: string }): string;
  exchange(p: { code: string; verifier: string; nonce: string }): Promise<KmitlProfile>;
  studentInfo(studentId: string): Promise<StudentInfo | null>;
}

type Config = NonNullable<typeof env.kmitl>;

export function createKmitlClient(
  cfg: Config,
  redirectUri: string,
  fetchFn: typeof fetch = fetch,
): KmitlClient {
  const oidc = `${cfg.issuer}/protocol/openid-connect`;
  const jwks = createRemoteJWKSet(new URL(`${oidc}/certs`));

  return {
    authorizeUrl({ state, nonce, challenge }) {
      const q = new URLSearchParams({
        client_id: cfg.clientId,
        redirect_uri: redirectUri,
        response_type: "code",
        scope: "openid profile email",
        state,
        nonce,
        code_challenge: challenge,
        code_challenge_method: "S256",
      });
      return `${oidc}/auth?${q}`;
    },

    async exchange({ code, verifier, nonce }) {
      const res = await fetchFn(`${oidc}/token`, {
        method: "POST",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "authorization_code",
          client_id: cfg.clientId,
          client_secret: cfg.clientSecret,
          code,
          redirect_uri: redirectUri,
          code_verifier: verifier,
        }),
      });
      if (!res.ok) throw new Error(`token endpoint ${res.status}`);
      const { id_token } = (await res.json()) as { id_token?: string };
      if (!id_token) throw new Error("ไม่มี id_token");

      // ตรวจลายเซ็นกับกุญแจสาธารณะของสถาบัน + ผู้ออก + ผู้รับ (client ของเรา) + nonce
      const { payload } = await jwtVerify(id_token, jwks, {
        issuer: cfg.issuer,
        audience: cfg.clientId,
      });
      if (payload.nonce !== nonce) throw new Error("nonce ไม่ตรง");
      if (typeof payload.sub !== "string") throw new Error("ไม่มี sub");

      return {
        sub: payload.sub,
        username: typeof payload.preferred_username === "string" ? payload.preferred_username : null,
        givenName: typeof payload.given_name === "string" ? payload.given_name : null,
        email: typeof payload.email === "string" ? payload.email : null,
      };
    },

    async studentInfo(studentId) {
      if (!cfg.apiKey) throw new Error("ยังไม่ได้ตั้ง KMITL_API_KEY");
      const res = await fetchFn(`${cfg.studentApiBase}/students/${encodeURIComponent(studentId)}`, {
        headers: { apikey: cfg.apiKey },
      });
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(`student api ${res.status}`);
      // ดึงฟิลด์ตามเอกสาร OpenAPI ของ Student API ทั้งหมด (ชื่อ คณะ หลักสูตร สถานภาพ ระดับ)
      return parseStudentInfo(await res.json());
    },
  };
}
