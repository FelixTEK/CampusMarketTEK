import { Elysia } from "elysia";
import { cors } from "@elysiajs/cors";
import { openapi } from "@elysiajs/openapi";
import { sql } from "./db";
import { errorHandler } from "./errors";
import { requestLogger } from "./logger";
import { env } from "./env";
import { createKmitlClient } from "./kmitl/client";
import { createKmitlService } from "./kmitl/service";
import { createDummyKmitlClient } from "./kmitl/dummy";
import { kmitlDummyRoutes } from "./routes/kmitl-dummy";
import { authRoutes } from "./routes/auth";
import { categoryRoutes } from "./routes/categories";
import { adminListingRoutes } from "./routes/admin-listings";
import { browseRoutes } from "./routes/browse";
import { chatRoutes } from "./routes/chat";
import { dealRoutes } from "./routes/deals";
import { notificationRoutes } from "./routes/notifications";
import { reviewRoutes } from "./routes/reviews";
import { reportRoutes, adminReportRoutes } from "./routes/reports";
import { adminUserRoutes } from "./routes/admin-users";
import { statsRoutes, adminStatsRoutes } from "./routes/stats";
import { bannerRoutes, adminBannerRoutes } from "./routes/banners";
import { privacyRoutes } from "./routes/privacy";
import { catalogRoutes } from "./routes/catalog";
import { listingRoutes } from "./routes/listings";
import { adminVerificationRoutes } from "./routes/admin-verifications";
import { userRoutes } from "./routes/users";
import { verificationRoutes } from "./routes/verifications";

// แยก app ออกจาก index.ts เพื่อให้ test เรียก app.handle() ได้โดยไม่ต้องเปิด port
export const app = new Elysia()
  .use(errorHandler)
  .use(requestLogger)
  .use(cors())
  .use(
    openapi({
      documentation: {
        info: { title: "Campus Market API", version: "0.1.0" },
      },
    }),
  )
  .get("/", () => ({ name: "Campus Market API", docs: "/openapi" }))
  .use(
    authRoutes(
      env.kmitlDummy
        ? createKmitlService(createDummyKmitlClient(env.publicBaseUrl))
        : env.kmitl
          ? createKmitlService(createKmitlClient(env.kmitl, `${env.publicBaseUrl}/auth/kmitl/callback`), Date.now, { skipStudentCheck: env.kmitl.skipStudentCheck })
          : null,
    ),
  )
  .use(env.kmitlDummy ? kmitlDummyRoutes(`${env.publicBaseUrl}/auth/kmitl/callback`) : new Elysia({ name: "no-kmitl-dummy" }))
  .use(userRoutes)
  .use(verificationRoutes)
  .use(adminVerificationRoutes)
  .use(categoryRoutes)
  .use(catalogRoutes)
  .use(browseRoutes)
  .use(listingRoutes)
  .use(adminListingRoutes)
  .use(chatRoutes)
  .use(dealRoutes)
  .use(notificationRoutes)
  .use(reviewRoutes)
  .use(reportRoutes)
  .use(adminReportRoutes)
  .use(adminUserRoutes)
  .use(statsRoutes)
  .use(adminStatsRoutes)
  .use(bannerRoutes)
  .use(adminBannerRoutes)
  .use(privacyRoutes)
  .get("/health", async ({ status }) => {
    try {
      await sql`SELECT 1`;
      return { status: "ok", database: "up" };
    } catch {
      return status(503, { status: "degraded", database: "down" });
    }
  });

export type App = typeof app;
