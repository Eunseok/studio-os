import { test, expect } from "@playwright/test";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";

test("Workflow connects Academy, approvals, inherited changes and a fresh retry", async ({
  page,
  request,
}) => {
  test.setTimeout(240000);
  const live = process.env.RUN_WORKFLOW_CODEX_E2E === "1";
  const stamp = Date.now().toString();
  const name = `Workflow ${live ? "Codex" : "Demo"} ${stamp}`;
  const skillName = `workflow-guide-${stamp}`;
  const marker = `Workflow skill verified ${stamp}`;
  const ending = `Workflow finished ${stamp}`;
  const repo = path.resolve("../../../work", `workflow-${stamp}`);
  const flag = path.resolve(repo, "..", `workflow-retry-${stamp}.flag`);
  const skillRoot = path.join(repo, ".agents/skills", skillName);
  await mkdir(skillRoot, { recursive: true });
  const original = "# Workflow fixture\n\nOriginal repository.\n";
  await writeFile(path.join(repo, "README.md"), original);
  await writeFile(
    path.join(skillRoot, "SKILL.md"),
    `---\nname: ${skillName}\ndescription: Workflow README preparation\nversion: 1.0.0\nrequired-tools: [git, python3]\n---\nPreserve README.md content and append a Capability section containing exactly this line: ${marker}\nOnly modify README.md. Do not modify verification scripts or other files.\n`,
  );
  await writeFile(
    path.join(repo, "verify.py"),
    live
      ? `from pathlib import Path\nimport sys\ntext=Path('README.md').read_text()\nassert ${JSON.stringify(marker)} in text\nif sys.argv[1]=='second': assert ${JSON.stringify(ending)} in text\nprint('Workflow Codex verification passed: '+sys.argv[1])\n`
      : `from pathlib import Path\nimport sys\nassert Path('STUDIO_DEMO.md').exists()\nif sys.argv[1]=='first': Path('handoff.txt').write_text(${JSON.stringify(marker)})\nelse:\n assert Path('handoff.txt').read_text()==${JSON.stringify(marker)}, 'Predecessor change is missing'\n assert Path(${JSON.stringify(flag)}).exists(), 'Intentional first-attempt test failure'\nprint('Workflow demo verification passed: '+sys.argv[1])\n`,
  );
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
    "Workflow fixture",
  );
  const headers = { "X-Studio-Client": "1" };
  const projectResponse = await request.post("/api/projects", {
    headers,
    data: {
      name,
      path: repo,
      rules:
        "Only change README.md for Codex tasks. Preserve all inherited content. Never modify verify.py or Skill files. Do not commit.",
      testCommand: ["python3", "verify.py", "first"],
    },
  });
  expect(projectResponse.ok()).toBeTruthy();
  const project = await projectResponse.json();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page
    .getByRole("button", { name: "Workflow Factory", exact: true })
    .click();
  await page.getByRole("button", { name: "Workflow 만들기" }).click();
  const editor = page.getByRole("region", { name: "Workflow 정의 편집" });
  await editor.getByLabel("Workflow 이름").fill(name);
  await editor.getByLabel("Workflow 프로젝트").selectOption(project.id);
  const first = editor.locator("fieldset").nth(0),
    second = editor.locator("fieldset").nth(1);
  await first.getByLabel("단계 이름").fill("README 준비");
  await first
    .getByLabel("담당 직원")
    .selectOption(live ? "codex-developer" : "demo-developer");
  await first
    .getByLabel("단계 작업 지시")
    .fill(
      "등록된 Skill의 지시대로 README를 갱신하세요. 검증 파일과 Skill 파일은 수정하지 마세요.",
    );
  await first.getByLabel("필요한 Skill 이름").fill(skillName);
  await first
    .getByLabel("단계 테스트 명령")
    .fill('["python3","verify.py","first"]');
  await first.getByLabel("단계 시간 제한 (초)").fill("120");
  await second.getByLabel("단계 이름").fill("후속 검토");
  await second
    .getByLabel("담당 직원")
    .selectOption(live ? "codex-developer" : "demo-developer");
  await second
    .getByLabel("단계 작업 지시")
    .fill(
      `선행 단계가 작성한 README 내용을 유지하고 마지막 줄에 ${ending}를 추가하세요. README.md만 변경하세요.`,
    );
  await second
    .getByLabel("단계 테스트 명령")
    .fill('["python3","verify.py","second"]');
  await second.getByLabel("단계 시간 제한 (초)").fill("120");
  await editor
    .getByRole("button", { name: "Workflow 저장", exact: true })
    .click();
  await expect(editor).not.toBeVisible();
  const definitionCard = page
    .locator(".workflow-definition")
    .filter({ has: page.getByRole("heading", { name, exact: true }) });
  await definitionCard
    .getByRole("button", { name: "실행 준비", exact: true })
    .click();
  const detail = page.getByRole("region", { name: "Workflow 실행 상세" });
  const stage1 = detail.getByRole("article", {
    name: "단계 README 준비",
    exact: true,
  });
  const stage2 = detail.getByRole("article", {
    name: "단계 후속 검토",
    exact: true,
  });
  await expect(stage1.locator(".badge")).toHaveText("역량 부족");
  await expect(stage2.locator(".badge")).toHaveText("선행 단계 대기");
  const runs = await (await request.get("/api/workflow-runs")).json();
  const run = runs.find(
    (r: { definition: { name: string } }) => r.definition.name === name,
  );
  await stage1.getByRole("button", { name: "Academy 열기" }).click();
  await page.getByLabel("등록·스캔 범위").selectOption(project.id);
  await page.getByLabel("전역 폴더 포함").uncheck();
  await page.getByLabel("Skill 검색").fill(skillName);
  await page.getByRole("button", { name: "설치 Skill 스캔" }).click();
  await page.locator(".skill-card").filter({ hasText: skillName }).click();
  const skill = page.getByRole("region", { name: "Skill 상세" });
  await skill.getByRole("button", { name: "검증 실행" }).click();
  await skill.getByRole("button", { name: "사용 승인 및 활성화" }).click();
  await expect(skill.getByRole("button", { name: "비활성화" })).toBeVisible();
  await page
    .getByRole("button", { name: "Workflow Factory", exact: true })
    .click();
  await stage1.getByRole("button", { name: "조건 재검사" }).click();
  await stage1.getByRole("button", { name: "계획 검토 및 승인" }).click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("heading", { name: "Workflow 연결" }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(
    dialog.getByText("테스트 통과 · 변경 사항을 검토하고 완료를 승인하세요", {
      exact: true,
    }),
  ).toBeVisible({ timeout: 140000 });
  await expect(stage2.locator(".badge")).toHaveText("선행 단계 대기");
  await dialog.getByRole("button", { name: "결과 검토 완료" }).click();
  await dialog.getByRole("button", { name: "닫기", exact: true }).click();
  await stage2.getByRole("button", { name: "계획 검토 및 승인" }).click();
  await expect(dialog.getByText(/선행 변경: step-1/)).toBeVisible();
  await dialog.getByRole("button", { name: "계획 승인 및 실행" }).click();
  if (!live) {
    await expect(dialog.locator(".task-message")).toContainText("테스트 실패", {
      timeout: 20000,
    });
    await expect(
      dialog.getByRole("button", { name: "결과 검토 완료" }),
    ).toHaveCount(0);
    await dialog.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(stage2.locator(".badge")).toHaveText("실패");
    await page.screenshot({
      path: "test-results/evidence/workflow-retry.png",
      fullPage: true,
    });
    await writeFile(flag, "Verification prerequisite resolved.");
    await stage2
      .getByLabel("재시도 보완 지시 (선택)")
      .fill(
        "검증 환경의 선행 조건을 해결했습니다. 보존된 선행 결과로 다시 검증하세요.",
      );
    await stage2.getByRole("button", { name: "새 시도 준비" }).click();
    await expect(stage2.getByText(/시도 2\/2/)).toBeVisible();
    await stage2.getByRole("button", { name: "계획 검토 및 승인" }).click();
    await dialog.getByRole("button", { name: "계획 승인 및 실행" }).click();
  }
  await expect(
    dialog.getByText("테스트 통과 · 변경 사항을 검토하고 완료를 승인하세요", {
      exact: true,
    }),
  ).toBeVisible({ timeout: 140000 });
  await dialog.getByRole("button", { name: "코드 변경", exact: true }).click();
  await expect(dialog.locator(".diff-output")).toContainText(
    live ? marker : "handoff.txt",
  );
  if (live) await expect(dialog.locator(".diff-output")).toContainText(ending);
  await page.screenshot({
    path: live
      ? "test-results/evidence/workflow-codex-diff.png"
      : "test-results/evidence/workflow-demo-diff.png",
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "결과 검토 완료" }).click();
  await dialog.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(detail.locator(".workflow-run-message")).toHaveText(
    "모든 단계의 검증과 결과 승인이 완료됐습니다",
  );
  const finished = await (
    await request.get(`/api/workflow-runs/${run.id}`)
  ).json();
  expect(finished.status).toBe("SUCCEEDED");
  expect(finished.steps["step-2"].attempts).toHaveLength(live ? 1 : 2);
  const taskId = finished.steps["step-2"].attempts.at(-1);
  const finalTask = await (await request.get(`/api/tasks/${taskId}`)).json();
  expect(finalTask.workflowInputs).toHaveLength(1);
  expect(finalTask.verification).toBe("PASSED");
  if (!live) {
    const failed = await (
      await request.get(`/api/tasks/${finished.steps["step-2"].attempts[0]}`)
    ).json();
    expect(failed.status).toBe("FAILED");
    expect(failed.verification).toBe("FAILED");
    expect(failed.worktree).not.toBe(finalTask.worktree);
  }
  expect(await readFile(path.join(repo, "README.md"), "utf8")).toBe(original);
  expect(git("status", "--porcelain")).toBe("");
  const manifest = await (
    await request.get(`/api/tasks/${taskId}/artifacts/workflow.json`)
  ).json();
  expect(manifest.runId).toBe(run.id);
  expect(manifest.inputs).toEqual(finalTask.workflowInputs);
  await page.screenshot({
    path: live ? "test-results/evidence/workflow-codex-complete.png" : "test-results/evidence/workflow.png",
    fullPage: true,
  });
  if (live)
    await writeFile(
      path.resolve("test-results/evidence/workflow-live-result.json"),
      JSON.stringify(
        {
          runId: run.id,
          definitionId: run.definition.id,
          repo,
          marker,
          ending,
          status: finished.status,
          steps: finished.steps,
          finalTaskId: taskId,
          verification: finalTask.verification,
        },
        null,
        2,
      ),
    );
  await page.reload();
  await page.getByText("운영 상세",{exact:true}).click();
  await page
    .getByRole("button", { name: "Workflow Factory", exact: true })
    .click();
  await expect(detail.locator(".workflow-run-message")).toHaveText(
    "모든 단계의 검증과 결과 승인이 완료됐습니다",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({
    path: "test-results/evidence/workflow-mobile.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
