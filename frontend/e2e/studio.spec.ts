import { test, expect } from "@playwright/test";
import { projectStudio, taskRoom, taskLabel } from "../app/studio-model";
import type { Snapshot, Task } from "../app/types";
import { mkdir, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";

test("projection preserves approval phases, execution priority and project scope", () => {
  const task = {
    id: "one",
    agentId: "a",
    projectId: "p",
    status: "WAITING_APPROVAL",
    startedAt: null,
    updatedAt: "2026-01-01",
    requirements: [],
  } as unknown as Task;
  expect(taskLabel(task)).toBe("계획 승인 대기");
  expect(taskLabel({ ...task, startedAt: "2026-01-01" })).toBe(
    "결과 승인 대기",
  );
  expect(
    taskRoom({
      ...task,
      status: "BLOCKED",
      requirements: [{ name: "Skill: missing", available: false, detail: "" }],
    }),
  ).toBe("academy");
  expect(taskRoom({ ...task, status: "BLOCKED" })).toBe("planning");
  const snapshot = {
    agents: [{ id: "a" }],
    tasks: [
      task,
      { ...task, id: "two", status: "VERIFYING" },
      { ...task, id: "three", projectId: "other", status: "RUNNING" },
    ],
    skills: [],
    workflowRuns: [],
  } as unknown as Snapshot;
  expect(projectStudio(snapshot, "p").employees[0].current?.id).toBe("two");
  expect(projectStudio(snapshot, "p").employees[0].room).toBe("qa");
  expect(projectStudio(snapshot, "p").tasks).toHaveLength(2);
  snapshot.tasks = [
    { ...task, status: "FAILED", updatedAt: "2026-01-01" },
    { ...task, id: "done", status: "SUCCEEDED", updatedAt: "2026-01-02" },
  ];
  expect(projectStudio(snapshot, "p").employees[0].current?.status).toBe(
    "SUCCEEDED",
  );
});

test("studio follows real verification and both approvals, skills, offline recovery and mobile", async ({
  page,
  request,
  context,
}) => {
  test.setTimeout(90000);
  const repo = path.resolve("../../../work", `studio-${Date.now()}`);
  await mkdir(repo, { recursive: true });
  await writeFile(path.join(repo, "README.md"), "# Virtual Studio fixture\n");
  await writeFile(
    path.join(repo, "verify.py"),
    "import time\nfrom pathlib import Path\ntime.sleep(7)\nassert Path('STUDIO_DEMO.md').exists()\nprint('Studio verification passed')\n",
  );
  const git = (...args: string[]) => execFileSync("git", args, { cwd: repo });
  git("init");
  git("add", ".");
  git(
    "-c",
    "user.name=Test",
    "-c",
    "user.email=test@example.invalid",
    "commit",
    "-m",
    "Fixture",
  );
  const post = async (url: string, data: unknown) => {
    const res = await request.post("/api" + url, {
      headers: { "X-Studio-Client": "1" },
      data,
    });
    expect(res.ok(), await res.text()).toBeTruthy();
    return res.json();
  };
  const project = await post("/projects", {
    name: `Studio ${Date.now()}`,
    path: repo,
    rules: "Test only",
    testCommand: ["python3", "verify.py"],
  });
  const task = await post("/tasks", {
    projectId: project.id,
    agentId: "demo-developer",
    title: "Studio live approval",
    prompt: "Create demo artifact",
    timeoutSeconds: 60,
  });
  const blocked = await post("/tasks", {
    projectId: project.id,
    agentId: "demo-developer",
    title: "Studio missing skill",
    prompt: "Test blocked skill",
    requiredSkills: ["studio-missing-skill"],
    timeoutSeconds: 60,
  });
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page.getByLabel("스튜디오 프로젝트").selectOption(project.id);
  const studio = page.getByRole("region", {
    name: "가상 스튜디오",
    exact: true,
  });
  const director = studio.getByRole("region", {
    name: "CEO Office",
    exact: true,
  });
  const qa = studio.getByRole("region", { name: "QA Lab", exact: true });
  const academy = studio.getByRole("region", {
    name: "Capability Academy",
    exact: true,
  });
  await expect(director.locator(`[data-task-id="${task.id}"]`)).toContainText(
    "계획 승인 대기",
  );
  await expect(academy.locator(`[data-task-id="${blocked.id}"]`)).toContainText(
    "조건 부족",
  );
  await director.locator(`[data-task-id="${task.id}"]`).click();
  await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(qa.locator(`[data-task-id="${task.id}"]`)).toContainText(
    "검증 중",
    { timeout: 20000 },
  );
  await expect(director.locator(`[data-task-id="${task.id}"]`)).toContainText(
    "결과 승인 대기",
    { timeout: 20000 },
  );
  await expect(director.locator(`[data-task-id="${task.id}"]`)).toContainText(
    "검증 통과",
  );
  await director.locator(`[data-task-id="${task.id}"]`).click();
  await page.getByRole("button", { name: "결과 검토 완료" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(director.locator(`[data-task-id="${task.id}"]`)).toHaveCount(0);
  await studio.getByRole("button", { name: "목록 보기", exact: true }).click();
  await expect(studio.locator(".vs-map")).toHaveClass(/vs-list/);
  await studio.getByRole("button", { name: "연출 정지", exact: true }).click();
  await expect(studio).toHaveClass(/motion-paused/);
  await page.screenshot({ path: "../docs/virtual-studio.png", fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    )
    .toBeTruthy();
  await page.screenshot({
    path: "../docs/virtual-studio-mobile.png",
    fullPage: true,
  });
  // Deny the stream while keeping HTTP snapshot polling available.
  await page.route("**/api/events/stream?**", (route) => route.abort());
  await page.reload();
  await page.getByLabel("스튜디오 프로젝트").selectOption(project.id);
  await expect(studio.locator(".vs-connection")).toContainText("연결 복구 중");
  await post(`/tasks/${blocked.id}/cancel`, {});
  await expect(academy.locator(`[data-task-id="${blocked.id}"]`)).toHaveCount(
    0,
    { timeout: 12000 },
  );
  await page.unroute("**/api/events/stream?**");
  await expect(studio.locator(".vs-connection")).toContainText("실시간 연결", {
    timeout: 35000,
  });
  const node = {
    agentId: "demo-developer",
    prompt: "Create demo artifact",
    requiredSkills: [],
    testCommand: ["python3", "verify.py"],
    requireVerification: true,
    timeoutSeconds: 60,
    maxAttempts: 2,
  };
  const definition = await post("/workflows", {
    name: "Studio dependency fixture",
    projectId: project.id,
    nodes: [
      { ...node, id: "prepare", title: "Studio prepare", dependsOn: [] },
      { ...node, id: "review", title: "Studio review", dependsOn: ["prepare"] },
    ],
  });
  const run = await post(`/workflows/${definition.id}/runs`, {
    requestKey: `studio-${Date.now()}`,
  });
  const factory = studio.getByRole("region", {
    name: "Workflow Factory",
    exact: true,
  });
  await expect(factory).toContainText("선행 작업 승인 대기");
  await factory
    .getByRole("button")
    .filter({ hasText: "Studio prepare" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Studio prepare");
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await post(`/workflow-runs/${run.id}/cancel`, {});
  await expect(factory).not.toContainText("Studio dependency fixture");
  expect(errors).toEqual([]);
});
