import { app } from "./app";
import { env } from "./env";

// hostname 0.0.0.0 = รับการเชื่อมต่อจากเครื่องอื่นด้วย (emulator ผ่าน 10.0.2.2, มือถือจริงผ่าน IP ในวง LAN)
app.listen({ port: env.port, hostname: "0.0.0.0" });

console.log(`Campus Market API  http://localhost:${env.port}`);
console.log(`เอกสาร API         http://localhost:${env.port}/openapi`);
