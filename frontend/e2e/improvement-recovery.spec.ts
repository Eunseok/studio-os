import { test, expect } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";
test("resume the real model-selected improvement after adapter repair", async ({
  page,
  request,
}) => {
  test.skip(
    process.env.RESUME_IMPROVEMENT_E2E !== "1",
    "Explicit recovery of saved live fixture",
  );
  test.setTimeout(240000);
  const out = path.resolve("test-results/evidence/improvement-live"),
    started = JSON.parse(
      await readFile(path.join(out, "started.json"), "utf8"),
    );
  const get = async (url: string) => (await request.get("/api" + url)).json();
  const idea = (await get("/improvements")).find(
    (i: { id: string }) => i.id === started.ideaId,
  );
  const run = await get(`/workflow-runs/${idea.workflowRunId}`),
    oldId = run.steps.implement.attempts.at(-1);
  const response = await request.post(
    `/api/workflow-runs/${run.id}/nodes/implement/retry`,
    {
      headers: { "X-Studio-Client": "1" },
      data: {
        expectedTaskId: oldId,
        correction:
          "The previous attempt failed before receiving a prompt because of an adapter argument parsing error. The adapter is now fixed. Implement the original selected improvement. No model escalation is necessary.",
      },
    },
  );
  expect(response.ok(), await response.text()).toBeTruthy();
  const retried = await response.json(),
    id = retried.steps.implement.attempts.at(-1);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page
    .getByRole("button", { name: "대표실 개선함", exact: true })
    .click();
  await page
    .getByRole("button", { name: "코드 변경·검증·결과 승인", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.locator(".improvement-routing")).toContainText("AI 선택");
  await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(dialog.locator(".task-message")).toContainText("테스트 통과", {
    timeout: 180000,
  });
  await page.getByRole("button", { name: "코드 변경", exact: true }).click();
  await expect(page.locator(".diff-output")).toContainText("Quick Start");
  await page.getByRole("button", { name: "결과 검토 완료" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(page.locator(".improvement-detail")).toContainText(
    "검증과 결과 승인이 완료됐습니다.",
  );
  const analysis = await get(`/tasks/${idea.analysisTaskId}`),
    task = await get(`/tasks/${id}`),
    finished = await get(`/workflow-runs/${run.id}`);
  expect(task.verification).toBe("PASSED");
  expect(finished.status).toBe("SUCCEEDED");
  expect(finished.steps.implement.attempts).toHaveLength(2);
  expect(
    execFileSync("git", ["status", "--porcelain"], {
      cwd: started.repo,
      encoding: "utf8",
    }),
  ).toBe("");
  expect(await readFile(path.join(task.worktree, "verify.py"), "utf8")).toBe(
    await readFile(path.join(started.repo, "verify.py"), "utf8"),
  );
  const decisions = {
    analysis: await get(`/models/decisions/${analysis.routingId}`),
    implementation: await get(`/models/decisions/${task.routingId}`),
  };
  await writeFile(
    path.join(out, "result.json"),
    JSON.stringify(
      {
        idea,
        analysis,
        run: finished,
        task,
        decisions,
        originalUnchanged: true,
        adapterRepair:
          "Claude variadic options consumed prompt; explicit -- separator added",
        browserErrors: errors,
      },
      null,
      2,
    ),
  );
  await writeFile(
    path.join(out, "README-result.md"),
    await readFile(path.join(task.worktree, "README.md"), "utf8"),
  );
  await page
    .locator(".improvements")
    .screenshot({ path: path.join(out, "complete.png") });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.reload();
  await page
    .getByRole("button", { name: "대표실 개선함", exact: true })
    .click();
  await expect(page.locator(".improvement-detail")).toContainText(
    "검증과 결과 승인이 완료됐습니다.",
  );
  expect(errors).toEqual([]);
});
