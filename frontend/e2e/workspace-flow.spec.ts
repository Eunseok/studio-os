import { test, expect, type Page } from "@playwright/test";

test.afterEach(async ({ page }, testInfo) => {
  await testInfo.attach("workspace-screen", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
});

const projects = ["alpha", "beta"].map(id => ({
  id, name: `${id} 프로젝트`, path: `/fixtures/${id}`, rules: "", testCommand: [], createdAt: "2026-10-24T00:00:00Z",
}));
const conversations = projects.map(p => ({
  id: `conversation-${p.id}`, projectId: p.id, state: "PLAN_READY",
  messages: [{ role: "user", text: `${p.id} 화면 개선`, at: "2026-10-24T00:00:00Z" }],
  plan: { response: "계획", questions: [], steps: [{ title: `${p.id} 구현`, instruction: "화면 정리" }] },
}));
const snapshot = {
  projects, agents: [{ id: "developer", name: "개발 전문가", role: "developer", adapter: "codex" }],
  tasks: [{ id: "beta-task", projectId: "beta", agentId: "developer", title: "beta 구현 작업", status: "RUNNING", model: "선택된 모델", reasoning: "high", requirements: [], updatedAt: "2026-10-24T00:00:00Z" }],
  workflowRuns: [], workflows: [], skills: [], adapters: [{ type: "codex", requirements: [] }], eventCursor: 0, verificationSandbox: true,
};
async function mockStudio(page: Page, options: { empty?: boolean; failChief?: boolean; rejectMessage?: boolean } = {}) {
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname === "/api/events/stream") return route.abort();
    if (url.pathname === "/api/snapshot") return route.fulfill({ json: options.empty ? { ...snapshot, projects: [], tasks: [] } : snapshot });
    if (url.pathname === "/api/chief") {
      if (options.failChief || (options.rejectMessage && route.request().method() === "POST")) return route.fulfill({ status: 503, json: { message: "대화 연결 실패" } });
      return route.fulfill({ json: options.empty ? [] : conversations });
    }
    return route.fulfill({ json: [] });
  });
}

test("project context connects attention, conversation, documents, team and operations", async ({ page }, testInfo) => {
  await mockStudio(page);
  await page.goto("/");
  const context = page.getByRole("region", { name: "현재 프로젝트와 다음 행동" });
  await expect(context.getByLabel("현재 프로젝트")).toHaveValue("alpha");
  await context.getByLabel("현재 프로젝트").selectOption("beta");
  await expect(context).toContainText("진행 중 1개 작업");
  await page.getByRole("navigation").getByRole("button", { name: "확인할 일", exact: true }).click();
  const attention = page.getByRole("region", { name: "프로젝트에서 확인할 일" });
  await expect(attention).toContainText("beta 화면 개선");
  await expect(attention).not.toContainText("alpha 화면 개선");
  await attention.getByRole("button", { name: "대화에서 확인" }).click();
  await expect(page.getByRole("button", { name: "이 계획으로 맡기고 진행" })).toBeVisible();
  await expect(page.getByLabel("대화할 프로젝트")).toHaveValue("beta");
  await testInfo.attach("project-conversation", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.locator(".chief-history").click();
  await expect(page.getByRole("button", { name: "이 계획으로 맡기고 진행" })).toBeVisible();
  await page.getByRole("button", { name: "공유 문서 열기" }).click();
  await expect(page.locator(".shared-library select")).toHaveValue("beta");
  await page.getByRole("navigation").getByRole("button", { name: "전문가 팀", exact: true }).click();
  await expect(page.locator(".agent-card")).toContainText("beta 구현 작업");
  await expect(page.locator(".agent-card")).toContainText("선택된 모델 · high");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("navigation").getByRole("button", { name: "작업", exact: true }).click();
  await expect(page.getByRole("heading", { name: "작업 보드" })).toBeVisible();
});

test("conversation failures are visible and failed messages keep the draft", async ({ page }) => {
  const options = { failChief: true, rejectMessage: true };
  await mockStudio(page, options);
  await page.goto("/");
  await expect(page.getByRole("alert")).toContainText("대화 연결 실패");
  options.failChief = false;
  await page.getByRole("button", { name: "대화 다시 불러오기" }).click();
  await expect(page.getByText("대화 상태를 갱신하지 못했습니다.", { exact: false })).toHaveCount(0);
  await page.getByLabel("총괄에게 이야기하기").fill("이 초안을 보존해 주세요");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.locator(".chief-conversation").getByRole("alert")).toContainText("대화 연결 실패");
  await expect(page.getByLabel("총괄에게 이야기하기")).toHaveValue("이 초안을 보존해 주세요");
});

test("empty workspace provides a project connection action", async ({ page }) => {
  await mockStudio(page, { empty: true });
  await page.goto("/");
  await expect(page.getByRole("region", { name: "현재 프로젝트와 다음 행동" })).toContainText("프로젝트 연결이 필요합니다");
  await expect(page.getByRole("button", { name: "첫 프로젝트 연결", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "보내기", exact: true })).toBeDisabled();
});
