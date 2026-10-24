import { test, expect } from "@playwright/test";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";

test("Academy scans, validates, activates and resumes a blocked task", async ({
  page,
  request,
}) => {
  test.setTimeout(180000);
  const live = process.env.RUN_CODEX_E2E === "1";
  const run = Date.now().toString();
  const name = `readme-academy-${run}`;
  const marker = `Academy verified ${run}`;
  const repo = path.resolve("../../../work", `academy-${run}`);
  await mkdir(path.join(repo, ".agents/skills", name, "references"), {
    recursive: true,
  });
  const original = "# Academy fixture\n\nLocal test repository.\n";
  await writeFile(path.join(repo, "README.md"), original);
  await writeFile(
    path.join(repo, ".agents/skills", name, "SKILL.md"),
    `---\nname: ${name}\ndescription: README 검증 안내를 추가하는 테스트 Skill\nversion: 1.0.0\nrequired-tools: [git, python3]\n---\nRead [instructions](references/instructions.md) and follow them to update README.md. Preserve existing README content.\n`,
  );
  await writeFile(
    path.join(repo, ".agents/skills", name, "references/instructions.md"),
    `Append a section named Capability Academy to README.md. Include this exact line: ${marker}\nDo not modify other files.\n`,
  );
  await writeFile(
    path.join(repo, "verify.py"),
    live
      ? `from pathlib import Path\nassert ${JSON.stringify(marker)} in Path('README.md').read_text()\nprint('Academy Skill verification passed')\n`
      : "from pathlib import Path\nassert Path('STUDIO_DEMO.md').exists()\nprint('Academy demo verification passed')\n",
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
    "Academy fixture",
  );
  const headers = { "X-Studio-Client": "1" };
  const projectResponse = await request.post("/api/projects", {
    headers,
    data: {
      name: `Academy ${live ? "Codex" : "Demo"} ${run}`,
      path: repo,
      rules:
        "Only modify README.md for a real Codex task. Keep fixture files unchanged.",
      testCommand: ["python3", "verify.py"],
    },
  });
  expect(projectResponse.ok()).toBeTruthy();
  const project = await projectResponse.json();
  const taskResponse = await request.post("/api/tasks", {
    headers,
    data: {
      projectId: project.id,
      agentId: live ? "codex-developer" : "demo-developer",
      title: `Academy README ${run}`,
      prompt:
        "README에 안내 섹션을 추가하세요. 등록된 Skill과 참조 문서의 지시를 따르세요.",
      timeoutSeconds: 120,
      requiredSkills: [name],
    },
  });
  const task = await taskResponse.json();
  expect(task.status).toBe("BLOCKED");
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page.getByRole("button", { name: "Capability Academy" }).click();
  await page.getByLabel("등록·스캔 범위").selectOption(project.id);
  await page.getByLabel("전역 폴더 포함").uncheck();
  await page.getByLabel("Skill 검색").fill(name);
  await expect(page.getByText(`Skill: ${name}`, { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "설치 Skill 스캔" }).click();
  await page.locator(".skill-card").filter({ hasText: name }).click();
  const detail = page.getByRole("region", { name: "Skill 상세" });
  await expect(
    detail.getByRole("button", { name: "사용 승인 및 활성화" }),
  ).toBeDisabled();
  await detail.getByRole("button", { name: "검증 실행" }).click();
  await expect(
    detail.getByText("패키지에 포함됨", { exact: true }),
  ).toBeVisible();
  await detail.getByRole("button", { name: "사용 승인 및 활성화" }).click();
  await expect(detail.getByRole("button", { name: "비활성화" })).toBeVisible();
  await page.screenshot({
    path: live ? "../docs/academy-codex-activated.png" : "../docs/academy.png",
    fullPage: true,
  });
  await page
    .locator(".academy-blocked")
    .filter({ hasText: task.title })
    .getByRole("button", { name: "조건 재검사" })
    .click();
  await expect(page.getByRole("status")).toContainText("조건을 충족했습니다");
  await page
    .getByRole("navigation", { name: "주요 메뉴" })
    .getByRole("button", { name: /^작업/ })
    .click();
  await page
    .getByRole("button")
    .filter({ hasText: task.title })
    .first()
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText(`${name} @ 1.0.0`, { exact: true }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(
    dialog.getByText("테스트 통과 · 변경 사항을 검토하고 완료를 승인하세요", {
      exact: true,
    }),
  ).toBeVisible({ timeout: 140000 });
  await dialog.getByRole("button", { name: "코드 변경", exact: true }).click();
  await expect(dialog.locator(".diff-output")).toContainText(
    live ? marker : "STUDIO_DEMO.md",
  );
  await page.screenshot({
    path: live
      ? "../docs/academy-codex-diff.png"
      : "../docs/academy-demo-diff.png",
    fullPage: true,
  });
  await dialog.getByRole("button", { name: "결과 검토 완료" }).click();
  await expect(
    dialog.getByText("테스트와 사용자 검토를 통과했습니다", { exact: true }),
  ).toBeVisible();
  const result = await (await request.get(`/api/tasks/${task.id}`)).json();
  expect(result.status).toBe("SUCCEEDED");
  expect(result.verification).toBe("PASSED");
  expect(await readFile(path.join(repo, "README.md"), "utf8")).toBe(original);
  expect(git("status", "--porcelain")).toBe("");
  const manifest = await request.get(
    `/api/tasks/${task.id}/artifacts/skills.json`,
  );
  expect(await manifest.json()).toEqual(result.skills);
  if (live)
    await writeFile(
      path.resolve("../docs/academy-live-result.json"),
      JSON.stringify(
        {
          taskId: task.id,
          projectId: project.id,
          repo,
          marker,
          status: result.status,
          verification: result.verification,
          skills: result.skills,
        },
        null,
        2,
      ),
    );
  await dialog.getByRole("button", { name: "닫기", exact: true }).click();
  await page.getByRole("button", { name: "Capability Academy" }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  await page.screenshot({ path: "../docs/academy-mobile.png", fullPage: true });
  expect(errors).toEqual([]);
});

test("manual Skill registration exposes validation failures and requires explicit activation", async ({
  page,
}) => {
  const name = `manual-skill-${Date.now()}`;
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await page.getByRole("button", { name: "Capability Academy" }).click();
  await page.getByRole("button", { name: "Skill 등록", exact: true }).click();
  await page
    .getByLabel("SKILL.md 내용")
    .fill(
      `---\nname: ${name}\ndescription: Manual test\nversion: 1.0.0\n---\nRead [missing](references/missing.md).\n`,
    );
  await page.getByRole("button", { name: "스냅샷 등록" }).click();
  const detail = page.getByRole("region", { name: "Skill 상세" });
  await detail.getByRole("button", { name: "검증 실행" }).click();
  await expect(
    detail.getByText("파일이 없거나 패키지 밖을 참조합니다"),
  ).toBeVisible();
  await expect(
    detail.getByRole("button", { name: "사용 승인 및 활성화" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Skill 등록", exact: true }).click();
  await page
    .getByLabel("SKILL.md 내용")
    .fill(
      `---\nname: ${name}\ndescription: Manual test\nversion: 1.0.1\n---\nReview the README and explain changes.\n`,
    );
  await page.getByRole("button", { name: "스냅샷 등록" }).click();
  await detail.getByRole("button", { name: "검증 실행" }).click();
  await detail.getByRole("button", { name: "사용 승인 및 활성화" }).click();
  await expect(detail.getByRole("button", { name: "비활성화" })).toBeVisible();
  await page.getByRole("button", { name: "새 작업", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("작업 제목").fill("필수 Skill 입력 확인");
  await dialog
    .getByRole("combobox", { name: "프로젝트", exact: true })
    .selectOption({ label: "Hello Studio" });
  await dialog.getByLabel("담당 직원").selectOption("demo-developer");
  await dialog.getByLabel("필요한 Skill 이름").fill(`${name}, missing-${name}`);
  await dialog.getByLabel("작업 지시").fill("Check the skill gate.");
  await dialog.getByRole("button", { name: "작업 생성", exact: true }).click();
  await expect(
    dialog.getByRole("heading", { name: "필수 Skill 입력 확인" }),
  ).toBeVisible();
  await expect(
    dialog.getByText(`Skill: missing-${name}`, { exact: true }),
  ).toBeVisible();
  await expect(
    dialog.getByText(`${name} @ 1.0.1`, { exact: true }),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "작업 취소", exact: true }).click();
  await expect(
    dialog.getByText("사용자가 취소했습니다. 코드와 로그는 보존됩니다", {
      exact: true,
    }),
  ).toBeVisible();
});
