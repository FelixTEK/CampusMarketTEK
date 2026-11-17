import { Elysia } from "elysia";
import { cors } from "@elysiajs/cors";
import { openapi } from "@elysiajs/openapi";
import { sql } from "./db";

// แยก app ออกจาก index.ts เพื่อให้ test เรียก app.handle() ได้โดยไม่ต้องเปิด port
export const app = new Elysia()
  .use(cors())
  .use(
    openapi({
      documentation: {
        info: { title: "Campus Market API", version: "0.1.0" },
      },
    }),
  )
  .get("/", () => ({ name: "Campus Market API", docs: "/openapi" }))
  .get("/health", async ({ status }) => {
    try {
      await sql`SELECT 1`;
      return { status: "ok", database: "up" };
    } catch {
      return status(503, { status: "degraded", database: "down" });
    }
  });

export type App = typeof app;
