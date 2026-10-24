import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  timeout: 30000,
  workers: 1,
  use: {
    baseURL: process.env.STUDIO_E2E_URL || "http://127.0.0.1:3000",
    browserName: "chromium",
    channel: "chrome",
    viewport: { width: 1440, height: 1000 },
  },
});
