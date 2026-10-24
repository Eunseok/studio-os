import { test, expect, type Page, type Route } from "@playwright/test";

function conversation(state = "THINKING") {
  return {
    id: "conversation", projectId: "project", state, requestKey: "request-first",
    messages: [{ role: "user", text: "원래 요청", at: "2026-10-24T00:00:00Z" }],
    plan: state === "PLAN_READY" ? {
      response: "두 단계로 진행합니다", questions: [],
      steps: [{ title: "첫 단계", instruction: "첫 상세 지시" }, { title: "둘째 단계", instruction: "둘째 상세 지시" }],
    } : undefined,
  };
}

async function mockStudio(page: Page, chief: (route: Route) => Promise<void>) {
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path.startsWith("/api/chief")) return chief(route);
    if (path === "/api/snapshot") return route.fulfill({ json: {
      projects: [{ id: "project", name: "테스트 프로젝트" }], agents: [], tasks: [],
      workflows: [], workflowRuns: [], skills: [], adapters: [], eventCursor: 0, verificationSandbox: true,
    } });
    if (path === "/api/events/stream") return route.fulfill({ contentType: "text/event-stream", body: ": connected\n\n" });
    return route.fulfill({ json: [] });
  });
}

test("failed delivery preserves input and reuses the request key", async ({ page }) => {
  const sends: { requestKey: string; message: string }[] = [];
  await mockStudio(page, async route => {
    if (route.request().method() === "GET") return route.fulfill({ json: [] });
    sends.push(route.request().postDataJSON());
    if (sends.length === 1) return route.abort("failed");
    return route.fulfill({ json: { ...conversation(), requestKey: sends[0].requestKey } });
  });
  await page.goto("/");
  const input = page.getByLabel("총괄에게 이야기하기");
  await input.fill("실패해도 보존할 내용");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(input).toHaveValue("실패해도 보존할 내용");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(input).toHaveValue("");
  expect(sends).toHaveLength(2);
  expect(sends[1]).toEqual(sends[0]);
});

test("stop ignores an older poll response and retry submits once", async ({ page }) => {
  let current = conversation();
  let holdPoll = false;
  let delayed: Route | undefined;
  let releaseRetry: (() => void) | undefined;
  const retries: { expectedRequestKey: string; requestKey: string }[] = [];
  await mockStudio(page, async route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "GET") {
      if (holdPoll && !delayed) { delayed = route; return; }
      return route.fulfill({ json: [current] });
    }
    if (path.endsWith("/stop")) {
      expect(route.request().postDataJSON()).toEqual({ requestKey: "request-first" });
      current = { ...current, state: "STOPPED" };
    }
    if (path.endsWith("/retry")) {
      retries.push(route.request().postDataJSON());
      await new Promise<void>(resolve => { releaseRetry = resolve; });
      current = { ...current, state: "THINKING", requestKey: retries[0].requestKey };
    }
    return route.fulfill({ json: current });
  });
  await page.goto("/");
  await page.locator(".chief-history").click();
  holdPoll = true;
  await expect.poll(() => !!delayed).toBe(true);
  await page.getByRole("button", { name: "진행 중지", exact: true }).click();
  await expect(page.locator(".chief-state")).toHaveText("중지됨");
  await delayed!.fulfill({ json: [{ ...conversation("PLAN_READY"), messages: [
    { role: "assistant", text: "취소된 늦은 응답", at: "2026-10-24T00:00:01Z" },
  ] }] });
  await expect(page.getByText("취소된 늦은 응답", { exact: true })).toHaveCount(0);
  const retry = page.getByRole("button", { name: "마지막 요청 다시 시도", exact: true });
  await retry.click();
  await expect.poll(() => retries.length).toBe(1);
  await expect(retry).toBeDisabled();
  releaseRetry!();
  await expect(page.locator(".chief-state")).toHaveText("총괄이 프로젝트를 살펴보고 있습니다");
  expect(retries).toHaveLength(1);
  expect(retries[0].expectedRequestKey).toBe("request-first");
  expect(retries[0].requestKey).not.toBe("request-first");
});

test("plan steps can be revised through conversation before approval", async ({ page }) => {
  let current = conversation("PLAN_READY");
  const paths: string[] = [];
  await mockStudio(page, async route => {
    if (route.request().method() === "GET") return route.fulfill({ json: [current] });
    paths.push(new URL(route.request().url()).pathname);
    expect(route.request().postDataJSON().message).toBe("둘째 단계를 삭제하고 첫 단계를 수정해");
    current = { ...current, state: "THINKING", plan: undefined };
    return route.fulfill({ json: current });
  });
  await page.goto("/");
  await page.locator(".chief-history").click();
  const input = page.getByLabel("총괄에게 이야기하기");
  await expect(input).toBeEnabled();
  await input.fill("둘째 단계를 삭제하고 첫 단계를 수정해");
  await page.getByRole("button", { name: "보내기", exact: true }).click();
  await expect(page.locator(".chief-state")).toHaveText("총괄이 프로젝트를 살펴보고 있습니다");
  expect(paths).toEqual(["/api/chief"]);
});
