import { test, expect } from "@playwright/test";
import path from "node:path";
test("register, approve, execute, verify and review a real Git worktree", async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByText("운영 상세", { exact: true }).click();
  await page.getByRole("button", { name: "스튜디오", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "오늘의 스튜디오" }),
  ).toBeVisible();
  const snapshot = await (await request.get("/api/snapshot")).json();
  let project = snapshot.projects.find(
    (p: { name: string }) => p.name === "Hello Studio",
  );
  if (!project) {
    await page
      .getByRole("button", { name: "프로젝트 등록", exact: true })
      .click();
    await page.getByLabel("프로젝트 이름").fill("Hello Studio");
    await page
      .getByLabel("로컬 Git 저장소 경로")
      .fill(path.resolve("..", "examples", "hello-studio"));
    await page
      .getByLabel("프로젝트 규칙")
      .fill("Keep the original repository unchanged.");
    await page
      .getByLabel("테스트 명령 (인자 배열)")
      .fill('["/bin/sh","verify.sh"]');
    await page.getByRole("button", { name: "등록", exact: true }).click();
    await expect(page.getByRole("dialog")).not.toBeVisible();
  }
  await page.getByRole("button", { name: "새 작업", exact: true }).click();
  await page.getByLabel("작업 제목").fill("첫 번째 Studio 실행");
  await page
    .getByRole("combobox", { name: "프로젝트", exact: true })
    .selectOption({ label: "Hello Studio" });
  await page.getByLabel("담당 직원").selectOption("demo-developer");
  await page
    .getByLabel("작업 지시")
    .fill("Create the demo artifact and verify the isolated worktree.");
  await page.getByRole("button", { name: "작업 생성", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "첫 번째 Studio 실행" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "계획 승인 및 실행" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "계획 승인 및 실행" }).click();
  await expect(
    page
      .getByRole("dialog")
      .getByText("테스트 통과 · 변경 사항을 검토하고 완료를 승인하세요"),
  ).toBeVisible({ timeout: 20000 });
  await page.getByRole("button", { name: "코드 변경", exact: true }).click();
  await expect(page.locator(".diff-output")).toContainText("STUDIO_DEMO.md");
  await page.getByRole("button", { name: "로그", exact: true }).click();
  await expect(page.getByRole("heading", { name: "tests.log" })).toBeVisible();
  await expect(page.locator(".log-output").last()).toContainText(
    "Verification passed",
  );
  await page.getByRole("button", { name: "결과 검토 완료" }).click();
  await expect(
    page.getByRole("dialog").getByText("테스트와 사용자 검토를 통과했습니다"),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "빈 worktree 정리", exact: true })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "modified or untracked",
  );
  await page.getByRole("button", { name: "닫기", exact: true }).click();
  await page.getByRole("button", { name: "오류 닫기", exact: true }).click();
  await expect(page.locator(".activity-section")).toContainText(
    "테스트와 사용자 검토를 통과했습니다",
  );
  await page.screenshot({ path: "../docs/dashboard.png", fullPage: true });
  await page.reload();
  await expect(
    page.getByRole("button").filter({ hasText: "첫 번째 Studio 실행" }).first(),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "../docs/mobile.png", fullPage: true });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBeTruthy();
  expect(errors).toEqual([]);
});
test("local proxy rejects an untrusted host", async ({ request }) => {
  const response = await request.get("/api/health", {
    headers: { Host: "evil.example" },
  });
  expect(response.status()).toBe(403);
});
