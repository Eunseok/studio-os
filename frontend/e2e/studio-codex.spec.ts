import { test, expect } from "@playwright/test";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import path from "node:path";

test("real Codex README workflow follows every Virtual Studio state", async ({
  page,
  request,
}) => {
  test.skip(
    process.env.RUN_STUDIO_CODEX_E2E !== "1",
    "Explicit opt-in for real Codex calls",
  );
  test.setTimeout(360000);
  const stamp = Date.now().toString();
  const repo = path.resolve("../../../work", `studio-codex-${stamp}`);
  const output = path.resolve("test-results/evidence/studio-codex");
  await mkdir(repo, { recursive: true });
  await mkdir(output, { recursive: true });
  const original =
    "# Studio Codex verification\n\nOriginal README content must remain intact.\n";
  const markers = [`Studio preparation ${stamp}`, `Studio completion ${stamp}`];
  const verifier = `from pathlib import Path\nimport sys,time\ntime.sleep(10) # Keep actual verification visible for browser assertions.\ntext=Path('README.md').read_text()\nassert text.startswith(${JSON.stringify(original)})\nassert ${JSON.stringify(markers[0])} in text\nif sys.argv[1]=='second': assert ${JSON.stringify(markers[1])} in text\nprint('Actual README verification passed: '+sys.argv[1])\n`;
  await writeFile(path.join(repo, "README.md"), original);
  await writeFile(path.join(repo, "verify.py"), verifier);
  const git = (...args: string[]) =>
    execFileSync("git", args, { cwd: repo, encoding: "utf8" });
  git("init");
  git("add", ".");
  git(
    "-c",
    "user.name=Studio Test",
    "-c",
    "user.email=studio@example.invalid",
    "-c",
    "core.hooksPath=/dev/null",
    "commit",
    "-m",
    "Real Codex Studio fixture",
  );
  const post = async (url: string, data: unknown) => {
    const r = await request.post("/api" + url, {
      headers: { "X-Studio-Client": "1" },
      data,
    });
    expect(r.ok(), await r.text()).toBeTruthy();
    return r.json();
  };
  const get = async (url: string) => (await request.get("/api" + url)).json();
  const project = await post("/projects", {
    name: `Virtual Studio Codex ${stamp}`,
    path: repo,
    rules:
      "Only modify README.md. Preserve existing content. Do not modify verify.py, create other files or commit. Runtime runs verification after you finish.",
    testCommand: ["python3", "verify.py", "first"],
  });
  const common = {
    agentId: "codex-developer",
    requiredSkills: [],
    requireVerification: true,
    timeoutSeconds: 150,
    maxAttempts: 1,
  };
  const definition = await post("/workflows", {
    name: `README Studio Codex ${stamp}`,
    projectId: project.id,
    nodes: [
      {
        ...common,
        id: "prepare",
        title: "README 준비",
        prompt: `Append a section titled Preparation to README.md containing the exact line: ${markers[0]}. Preserve existing content. Only modify README.md.`,
        dependsOn: [],
        testCommand: ["python3", "verify.py", "first"],
      },
      {
        ...common,
        id: "finish",
        title: "README 마무리",
        prompt: `Preserve all inherited README.md content, including the Preparation section. Append a section titled Completion containing the exact line: ${markers[1]}. Only modify README.md.`,
        dependsOn: ["prepare"],
        testCommand: ["python3", "verify.py", "second"],
      },
    ],
  });
  const run = await post(`/workflows/${definition.id}/runs`, {
    requestKey: `studio-codex-${stamp}`,
  });
  await writeFile(
    path.join(output, "started.json"),
    JSON.stringify(
      { projectId: project.id, runId: run.id, repo, markers },
      null,
      2,
    ),
  );
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
  const room = (name: string) =>
    studio.getByRole("region", { name, exact: true });
  const director = room("CEO Office"),
    development = room("Development Room"),
    qa = room("QA Lab"),
    factory = room("Workflow Factory");
  const observations: unknown[] = [];
  const observe = async (
    id: string,
    node: string,
    state: string,
    location: string,
  ) => {
    const task = await get(`/tasks/${id}`);
    expect(task.status).toBe(state);
    observations.push({
      node,
      taskId: id,
      state,
      location,
      verification: task.verification,
      at: new Date().toISOString(),
    });
    await studio.screenshot({
      path: path.join(
        output,
        `${node}-${state.toLowerCase()}-${observations.length}.png`,
      ),
    });
  };
  const tasks: any[] = [];
  for (const node of ["prepare", "finish"]) {
    let taskId = "";
    await expect
      .poll(
        async () => {
          const current = await get(`/workflow-runs/${run.id}`);
          taskId = current.steps[node].attempts.at(-1) || "";
          return taskId;
        },
        { timeout: 15000 },
      )
      .not.toBe("");
    const card = (region: typeof director) =>
      region.locator(`[data-task-id="${taskId}"]`);
    await expect(card(director)).toContainText("계획 승인 대기");
    await observe(taskId, node, "WAITING_APPROVAL", "CEO Office / 계획");
    if (node === "prepare")
      await expect(factory).toContainText("선행 작업 승인 대기");
    await card(director).click();
    await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(card(development)).toContainText("작업 중", {
      timeout: 30000,
    });
    await expect(development.locator(".vs-person.running")).toContainText(
      "Codex developer",
    );
    await observe(taskId, node, "RUNNING", "Development Room");
    await expect(card(qa)).toContainText("검증 중", { timeout: 160000 });
    await expect(qa.locator(".vs-person.verifying")).toContainText(
      "Codex developer",
    );
    await observe(taskId, node, "VERIFYING", "QA Lab");
    await expect(card(director)).toContainText("결과 승인 대기", {
      timeout: 30000,
    });
    await expect(card(director)).toContainText("검증 통과");
    await observe(taskId, node, "WAITING_APPROVAL", "CEO Office / 결과");
    const task = await get(`/tasks/${taskId}`);
    expect(task.adapter).toBe("codex");
    expect(task.agentExitCode).toBe(0);
    expect(task.testExitCode).toBe(0);
    expect(task.verification).toBe("PASSED");
    const readme = await readFile(
      path.join(task.worktree, "README.md"),
      "utf8",
    );
    expect(readme).toContain(markers[0]);
    if (node === "finish") expect(readme).toContain(markers[1]);
    expect(await readFile(path.join(task.worktree, "verify.py"), "utf8")).toBe(
      verifier,
    );
    const changed = execFileSync(
      "git",
      ["diff", "--name-only", run.baseCommit],
      { cwd: task.worktree, encoding: "utf8" },
    ).trim();
    expect(changed).toBe("README.md");
    expect(
      execFileSync("git", ["ls-files", "--others", "--exclude-standard"], {
        cwd: task.worktree,
        encoding: "utf8",
      }).trim(),
    ).toBe("");
    if (node === "prepare") {
      const current = await get(`/workflow-runs/${run.id}`);
      expect(current.steps.finish.attempts).toEqual([]);
      await expect(factory).toContainText("선행 작업 승인 대기");
    }
    await card(director).click();
    await page.getByRole("button", { name: "코드 변경", exact: true }).click();
    await expect(page.locator(".diff-output")).toContainText(
      markers[node === "prepare" ? 0 : 1],
    );
    await page.getByRole("button", { name: "결과 검토 완료" }).click();
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(card(director)).toHaveCount(0);
    await expect
      .poll(async () => (await get(`/tasks/${taskId}`)).status)
      .toBe("SUCCEEDED");
    tasks.push(await get(`/tasks/${taskId}`));
    await writeFile(path.join(output, `${node}-README.md`), readme);
  }
  await expect
    .poll(async () => (await get(`/workflow-runs/${run.id}`)).status)
    .toBe("SUCCEEDED");
  await expect(development.locator(".vs-person.succeeded")).toContainText(
    "작업 완료",
  );
  await expect(factory).toContainText("완료 1");
  await studio.screenshot({ path: path.join(output, "complete.png") });
  expect(await readFile(path.join(repo, "README.md"), "utf8")).toBe(original);
  expect(git("status", "--porcelain")).toBe("");
  await page.reload();
  await page.getByLabel("스튜디오 프로젝트").selectOption(project.id);
  await expect(development.locator(".vs-person.succeeded")).toContainText(
    "작업 완료",
  );
  expect(errors).toEqual([]);
  await writeFile(
    path.join(output, "result.json"),
    JSON.stringify(
      {
        run: await get(`/workflow-runs/${run.id}`),
        tasks,
        observations,
        originalRepositoryUnchanged: true,
        browserErrors: errors,
      },
      null,
      2,
    ),
  );
  console.log(`Verified real Codex Workflow ${run.id}; evidence: ${output}`);
});
