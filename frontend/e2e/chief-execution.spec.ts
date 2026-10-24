import { test, expect, type Page } from "@playwright/test";
import type { Conversation } from "../app/chief-data";
import type { Task } from "../app/types";

test.afterEach(async ({ page }, testInfo) => {
  await testInfo.attach("execution-screen", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
});

function fixture() {
  const conversation: Conversation = {
    id: "conversation", projectId: "project", state: "PLAN_READY", messages: [{ role: "user", text: "대화 흐름 개선", at: "2026-10-24" }],
    plan: { response: "대화 개선", questions: [], steps: [{ title: "대화 구현", instruction: "입력 동작 개선", requiredSkills: ["ui-guide"], skillReason: "기존 화면 규칙에 맞추기 위해 선택" }] },
  };
  const task: Task = {
    id: "task", projectId: "project", agentId: "developer", title: "대화 구현", prompt: "입력 동작 개선",
    status: "RUNNING", verification: "NOT_RUN", reviewStatus: "NOT_RUN", adapter: "codex", role: "Developer",
    model: "fixture-model", reasoning: "medium", routingReason: "기존 입력 컴포넌트와 작은 변경 범위를 확인해 선택",
    rules: "", testCommand: ["offline-check"], timeoutSeconds: 120, agentExitCode: null, testExitCode: null,
    createdAt: "2026-10-24", updatedAt: "2026-10-24", startedAt: "2026-10-24", finishedAt: null,
    branch: "fixture", baseCommit: "base", worktree: "/fixture/worktree", message: "구현 중", approvedAt: "2026-10-24",
    workflowRunId: "run", workflowNodeId: "step-1", workflowAttempt: 1, requireVerification: true, requireReview: true,
    workflowInputs: [], requiredSkills: ["ui-guide"], skills: [{ id: "skill", name: "ui-guide", version: "1.0.0", digest: "fixture-sha" }], requirements: [], cleaned: false,
  };
  const node = { id: "step-1", title: task.title, prompt: task.prompt, agentId: "auto", dependsOn: [], requiredSkills: ["ui-guide"], testCommand: ["offline-check"], requireVerification: true, requireReview: true, maxAttempts: 2, timeoutSeconds: 120 };
  const project = { id: "project", name: "개선 프로젝트", path: "/fixture", rules: "", testCommand: ["offline-check"], createdAt: "2026-10-24" };
  const run = { id: "run", status: "RUNNING", message: "승인된 범위 실행 중", baseCommit: "base", createdAt: "2026-10-24", updatedAt: "2026-10-24", finishedAt: null, project, definition: { id: "definition", name: "대화 개선", projectId: "project", revision: 1, createdAt: "2026-10-24", updatedAt: "2026-10-24", nodes: [node] }, steps: { "step-1": { nodeId: "step-1", status: "RUNNING", message: "구현 중", attempts: ["task"], outputDigest: null } } };
  const snapshot = { projects: [project], agents: [{ id: "developer", name: "개발 전문가", role: "Developer", adapter: "codex" }], tasks: [task], workflowRuns: [run], workflows: [], skills: [], adapters: [], eventCursor: 0, verificationSandbox: true };
  return { conversation, task, run, snapshot, evidenceFails: false, sent: [] as string[], applied: false };
}
async function mount(page: Page, f: ReturnType<typeof fixture>) {
  await page.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === "/api/events/stream") return route.abort();
    if (path === "/api/snapshot") return route.fulfill({ json: f.snapshot });
    if (path === "/api/chief" && request.method() === "POST") {
      const body = request.postDataJSON();
      f.sent.push(body.message);
      f.conversation.feedbackPending = true;
      f.conversation.messages.push({ role: "user", text: body.message, at: "2026-10-24" });
      return route.fulfill({ json: f.conversation });
    }
    if (path === "/api/chief") return route.fulfill({ json: [f.conversation] });
    if (path.endsWith("/start")) { f.conversation.state = "RUNNING"; f.conversation.runId = "run"; return route.fulfill({ json: f.conversation }); }
    if (path.endsWith("/approve")) { f.conversation.state = "COMPLETE"; f.task.status = "SUCCEEDED"; return route.fulfill({ json: f.conversation }); }
    if (path.endsWith("/replan")) {
      f.conversation.state = "THINKING"; f.conversation.previousRunIds = ["run"]; f.conversation.runId = undefined; f.conversation.feedbackPending = false; f.conversation.plan = undefined; f.run.status = "CANCELLED"; f.task.status = "CANCELLED";
      return route.fulfill({ json: f.conversation });
    }
    if (path.endsWith("/apply")) { f.applied = true; return route.fulfill({ json: {} }); }
    if (path.endsWith("/changes")) return f.evidenceFails ? route.fulfill({ status: 503, json: { message: "변경 근거 연결 실패" } }) : route.fulfill({ json: { diff: "diff --git a/input.tsx b/input.tsx\n+의견 보내기" } });
    if (path.endsWith("/logs")) return route.fulfill({ json: { "tests.log": "offline-check: passed", "review.log": "fixture review evidence" } });
    return route.fulfill({ json: [] });
  });
  await page.goto("/");
  await page.locator(".chief-history").click();
}

test("scope approval connects assignment, implementation, verification, review and result confirmation", async ({ page }, testInfo) => {
  const f = fixture();
  await mount(page, f);
  await expect(page.locator(".chief-plan")).toContainText("기존 화면 규칙에 맞추기 위해 선택");
  await page.getByRole("button", { name: "이 계획으로 맡기고 진행" }).click();
  const results = page.getByRole("region", { name: "실행 결과와 근거" });
  await expect(results).toContainText("기존 입력 컴포넌트와 작은 변경 범위를 확인해 선택");
  await expect(results).toContainText("ui-guide · 1.0.0");
  await expect(results).toContainText("미실행·통과 확인 없음");
  await expect(page.getByRole("button", { name: "결과 확인 · 완료 승인" })).toHaveCount(0);
  await testInfo.attach("implementation-pending", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  f.task.agentExitCode = 0; f.task.verification = "PASSED"; f.task.testExitCode = 0; f.task.status = "VERIFYING"; f.task.reviewStatus = "RUNNING";
  await expect(results).toContainText("독립 리뷰 중");
  f.task.status = "WAITING_APPROVAL"; f.task.reviewStatus = "PASSED"; f.task.review = { verdict: "PASS", summary: "회귀 없이 수용 기준 충족", findings: [] }; f.conversation.state = "WAITING_RESULT";
  await expect(results).toContainText("회귀 없이 수용 기준 충족");
  await results.getByText("실제 변경·검증·리뷰 근거", { exact: true }).click();
  await expect(results).toContainText("+의견 보내기");
  await testInfo.attach("verified-result-before-approval", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.getByRole("button", { name: "결과 확인 · 완료 승인" }).click();
  await expect(results).toContainText("완료 승인 기록 있음");
  await expect(results).toContainText("미반영 · 격리 작업 공간의 결과");
  await expect(page.getByRole("button", { name: "승인한 변경을 프로젝트에 반영" })).toBeVisible();
  expect(f.applied).toBe(false);
});

test("running feedback holds result approval and preserves stopped execution during replanning", async ({ page }) => {
  const f = fixture(); f.conversation.state = "WAITING_RESULT"; f.conversation.runId = "run";
  await mount(page, f);
  await page.getByLabel("총괄에게 이야기하기").fill("원래 버튼 색상은 유지해 주세요");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  expect(f.sent).toEqual(["원래 버튼 색상은 유지해 주세요"]);
  await expect(page.getByText("의견은 현재 작업에 아직 반영되지 않았습니다.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "결과 확인 · 완료 승인" })).toHaveCount(0);
  await page.getByRole("button", { name: "의견으로 범위 다시 정하기" }).click();
  await expect(page.getByLabel("총괄에게 이야기하기")).toBeDisabled();
  await page.getByText("이전 실행·중단 이력 (1)", { exact: true }).click();
  await expect(page.getByRole("region", { name: "이전 실행 기록" })).toContainText("CANCELLED");
});

test("failed attempts and evidence errors remain visible without claiming review or application", async ({ page }) => {
  const f = fixture(); f.conversation.state = "NEEDS_HELP"; f.conversation.runId = "run";
  f.task.status = "FAILED"; f.task.verification = "FAILED"; f.task.testExitCode = 1;
  f.task.failure = { category: "ENVIRONMENT", adapt: false, reason: "인증 실패" }; f.evidenceFails = true;
  await mount(page, f);
  const results = page.getByRole("region", { name: "실행 결과와 근거" });
  await expect(results).toContainText("인증 실패 · 자동 재시도 중단");
  await expect(results).toContainText("미반영 · 격리 작업 공간의 결과");
  await results.getByText("실제 변경·검증·리뷰 근거", { exact: true }).click();
  await expect(results.getByRole("alert")).toContainText("변경 근거 연결 실패");
  f.evidenceFails = false;
  await results.getByRole("button", { name: "근거 새로 불러오기" }).click();
  await expect(results).toContainText("+의견 보내기");
  await expect(page.getByRole("button", { name: "결과 확인 · 완료 승인" })).toHaveCount(0);
});
