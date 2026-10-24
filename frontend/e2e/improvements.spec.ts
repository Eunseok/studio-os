import { test, expect } from "@playwright/test";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";

test("real AI model selection, proposals and selected implementation reach approval", async ({
  page,
  request,
}) => {
  test.skip(
    process.env.RUN_IMPROVEMENT_E2E !== "1",
    "Explicit opt-in for actual AI calls",
  );
  test.setTimeout(600000);
  const stamp = Date.now(),
    repo = path.resolve("../../../work", `improvement-${stamp}`),
    out = path.resolve("../docs/improvement-live");
  await mkdir(repo, { recursive: true });
  await mkdir(out, { recursive: true });
  const original = "# Tiny Studio\n\nA local sample project.\n";
  await writeFile(path.join(repo, "README.md"), original);
  await writeFile(path.join(repo, "app.py"), 'print("Hello Studio")\n');
  const verification = `from pathlib import Path\ns=Path('README.md').read_text()\nassert s.startswith(${JSON.stringify(original)})\nassert '## Quick Start' in s\nassert 'python3 app.py' in s\nprint('Quick Start verification passed')\n`;
  await writeFile(path.join(repo, "verify.py"), verification);
  const git = (...args: string[]) =>
    execFileSync("git", args, { cwd: repo, encoding: "utf8" });
  git("init");
  git("add", ".");
  git(
    "-c",
    "user.name=Test",
    "-c",
    "user.email=test@example.invalid",
    "-c",
    "core.hooksPath=/dev/null",
    "commit",
    "-m",
    "Fixture",
  );
  const headers = { "X-Studio-Client": "1" };
  const r = await request.post("/api/projects", {
    headers,
    data: {
      name: `Improvement AI ${stamp}`,
      path: repo,
      rules:
        "Only modify README.md. Do not change app.py or verify.py. Preserve the original README text. Do not commit.",
      testCommand: ["python3", "verify.py"],
    },
  });
  expect(r.ok()).toBeTruthy();
  const project = await r.json();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page
    .getByRole("button", { name: "대표실 개선함", exact: true })
    .click();
  await page.getByLabel("개선할 프로젝트").selectOption(project.id);
  await page
    .getByLabel("개선 의견", {exact:true})
    .fill(
      "README에 실행 방법이 없어 처음 온 사용자가 막힙니다. README 기존 내용을 그대로 유지하고 ## Quick Start 섹션과 python3 app.py 실행 명령을 추가하는 작은 개선안 하나만 제안하세요. app.py와 verify.py는 변경하지 마세요.",
    );
  await page
    .getByRole("button", { name: "의견 저장 · 분석 계획 만들기" })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible({ timeout: 120000 });
  await expect(dialog.locator(".improvement-routing")).toContainText("AI 선택");
  await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(dialog.locator(".task-message")).toContainText(
    "개선 후보 검증 통과",
    { timeout: 330000 },
  );
  await expect(dialog.locator(".proposal-output")).toContainText("Quick Start");
  await page.getByRole("button", { name: "결과 검토 완료" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  const idea = (await (await request.get("/api/improvements")).json()).find(
    (i: { projectId: string }) => i.projectId === project.id,
  );
  await writeFile(
    path.join(out, "started.json"),
    JSON.stringify({ repo, projectId: project.id, ideaId: idea.id }, null, 2),
  );
  await page
    .locator(".improvements")
    .screenshot({ path: path.join(out, "proposals.png") });
  await page
    .getByRole("button", { name: "이 개선안으로 구현 계획 만들기" })
    .first()
    .click();
  await expect(
    page.getByRole("button", { name: "코드 변경·검증·결과 승인", exact: true }),
  ).toBeVisible({ timeout: 120000 });
  await page
    .getByRole("button", { name: "코드 변경·검증·결과 승인", exact: true })
    .click();
  await expect(dialog.locator(".improvement-routing")).toContainText("AI 선택");
  await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(dialog.locator(".task-message")).toContainText("테스트 통과", {
    timeout: 300000,
  });
  await page.getByRole("button", { name: "코드 변경", exact: true }).click();
  await expect(page.locator(".diff-output")).toContainText("Quick Start");
  await page.getByRole("button", { name: "결과 검토 완료" }).click();
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(page.locator(".improvement-detail")).toContainText(
    "검증과 결과 승인이 완료됐습니다.",
  );
  const result = (await (await request.get("/api/improvements")).json()).find(
    (i: { id: string }) => i.id === idea.id,
  );
  const snapshot = await (await request.get("/api/snapshot")).json();
  const analysis = snapshot.tasks.find(
      (t: { id: string }) => t.id === result.analysisTaskId,
    ),
    run = snapshot.workflowRuns.find(
      (r: { id: string }) => r.id === result.workflowRunId,
    ),
    task = snapshot.tasks.find(
      (t: { id: string }) => t.id === run.steps.implement.attempts.at(-1),
    );
  expect(analysis.status).toBe("SUCCEEDED");
  expect(analysis.routingId).toBeTruthy();
  expect(task.routingId).toBeTruthy();
  expect(run.status).toBe("SUCCEEDED");
  expect(task.verification).toBe("PASSED");
  expect(await readFile(path.join(repo, "README.md"), "utf8")).toBe(original);
  expect(git("status", "--porcelain")).toBe("");
  expect(await readFile(path.join(task.worktree, "verify.py"), "utf8")).toBe(
    verification,
  );
  await writeFile(
    path.join(out, "result.json"),
    JSON.stringify(
      {
        idea: result,
        analysis,
        run,
        task,
        originalUnchanged: true,
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
  console.log(
    `Completed improvement ${idea.id}: analysis ${analysis.adapter}/${analysis.model}/${analysis.reasoning}; implementation ${task.adapter}/${task.model}/${task.reasoning}`,
  );
});
