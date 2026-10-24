import { test, expect } from "@playwright/test";

test("running conversation accepts instructions and shows receipt, waiting and applied states", async ({ page }) => {
  type Instruction = { requestKey: string; text: string; status: string; revision: number };
  let current = {
    id: "conversation", projectId: "project", state: "RUNNING", requestKey: "original-request",
    runId: "run", steeringKey: "", instructions: [] as Instruction[],
    messages: [{ role: "user", text: "원래 요청", at: "2026-10-24T00:00:00Z" }],
  };
  const submissions: { requestKey: string; message: string }[] = [];
  let approvals = 0;
  await page.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/chief" && route.request().method() === "GET")
      return route.fulfill({ json: [current] });
    if (path === "/api/chief") {
      const body = route.request().postDataJSON();
      submissions.push(body);
      if (submissions.length === 1) return route.abort("failed");
      current = { ...current, state: "STEERING", steeringKey: body.requestKey,
        instructions: [{ requestKey: body.requestKey, text: body.message, status: "RECEIVED", revision: 0 }] };
      return route.fulfill({ json: current });
    }
    if (path.endsWith("/instructions/approve")) {
      expect(route.request().postDataJSON()).toEqual({ requestKey: current.steeringKey });
      approvals++;
      current = { ...current, state: "RUNNING", instructions: current.instructions.map(i => ({ ...i, status: "PENDING", revision: 1 })) };
      return route.fulfill({ json: current });
    }
    if (path === "/api/snapshot") return route.fulfill({ json: {
      projects: [{ id: "project", name: "테스트 프로젝트" }], agents: [], tasks: [], workflows: [],
      workflowRuns: [], skills: [], adapters: [], eventCursor: 0, verificationSandbox: true,
    } });
    if (path === "/api/events/stream") return route.fulfill({ contentType: "text/event-stream", body: ": connected\n\n" });
    return route.fulfill({ json: [] });
  });
  await page.goto("/");
  await page.getByRole("button", { name: /원래 요청/ }).click();
  const input = page.getByLabel("총괄에게 이야기하기");
  await expect(input).toBeEnabled();
  await input.fill("방향을 바꾸고 범위도 추가해");
  await page.getByRole("button", { name: "추가 지시 보내기", exact: true }).click();
  await expect(page.locator(".chief-conversation [role=alert]")).toBeVisible();
  await expect(input).toHaveValue("방향을 바꾸고 범위도 추가해");
  await page.getByRole("button", { name: "추가 지시 보내기", exact: true }).click();
  await expect(input).toHaveValue("");
  expect(submissions[1]).toEqual(submissions[0]);
  await expect(page.getByText("접수 · 기존 호출 종료 대기", { exact: true })).toBeVisible();
  const approve = page.getByRole("button", { name: "추가 지시 적용 승인", exact: true });
  await expect(approve).toBeDisabled();
  current.instructions[0].status = "WAITING_APPROVAL";
  await expect(page.getByText("반영 대기 · 적용 승인 필요", { exact: true })).toBeVisible();
  await approve.click();
  await expect(page.getByText("반영 대기", { exact: true })).toBeVisible();
  current.instructions[0].status = "APPLIED";
  await expect(page.getByText("반영 완료 · 새 호출에 전달됨", { exact: true })).toBeVisible();
  expect(approvals).toBe(1);
  await expect(input).toBeEnabled();
});
