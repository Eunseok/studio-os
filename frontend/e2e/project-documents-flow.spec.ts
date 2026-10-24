import { test, expect, type Page } from "@playwright/test";

// All requests stay in the browser fixture; these checks never write Studio data.
const project = { id: "fixture", name: "화면 확인용 프로젝트", path: "/fixture", rules: "", testCommand: [], createdAt: "2026-10-24" };
type Doc = { id: string; title: string; body: string; revision: number; author: string; updatedAt: string };
async function mount(page: Page) {
  let archived = false;
  const original: Doc = { id: "fixture:brief", title: "프로젝트 개요", body: "기존 목표를 보존합니다", revision: 1, author: "사용자", updatedAt: "2026-10-24" };
  let current = original;
  const history = [original];
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  const unexpected: string[] = [];
  await page.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    if (path === "/api/events/stream") return route.abort();
    if (request.method() === "POST") {
      const body = request.postDataJSON();
      writes.push({ path, body });
      if (path === "/api/projects/fixture/archive") {
        archived = body.archived;
        return route.fulfill({ json: {} });
      }
      if (path === "/api/projects/fixture/documents/brief") {
        if (body.expectedRevision !== current.revision) return route.fulfill({ status: 409, json: { message: "다른 변경이 있습니다" } });
        current = { ...current, body: body.body, revision: current.revision + 1 };
        history.push(current);
        return route.fulfill({ json: current });
      }
    } else {
      if (path === "/api/snapshot") return route.fulfill({ json: { projects: archived ? [] : [project], agents: [], tasks: [], workflowRuns: [], workflows: [], skills: [], adapters: [], eventCursor: 0, verificationSandbox: true } });
      if (path === "/api/chief") return route.fulfill({ json: [] });
      if (path === "/api/projects/all") return route.fulfill({ json: { projects: [project], archived: archived ? [project.id] : [] } });
      if (path === "/api/projects/fixture/documents") return route.fulfill({ json: [current] });
      if (path === "/api/projects/fixture/document-history") return route.fulfill({ json: history });
      if (path === "/api/projects/fixture/repository-documents") return route.fulfill({ json: [{ path: "README.md", bytes: 100 }] });
    }
    unexpected.push(`${request.method()} ${path}`);
    return route.fulfill({ status: 500, json: { message: "예상하지 않은 요청" } });
  });
  await page.goto("/");
  return { writes, unexpected, history };
}

test.afterEach(async ({ page }, testInfo) => {
  await testInfo.attach("project-documents-screen", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
});

test("hidden projects leave the everyday list and can be restored with records preserved", async ({ page }, testInfo) => {
  const f = await mount(page);
  await page.getByRole("navigation").getByRole("button", { name: "프로젝트", exact: true }).click();
  const card = page.locator(".project-card");
  await expect(card).toContainText(project.name);
  await card.getByRole("button", { name: "목록에서 숨기기" }).click();
  await expect(card).toHaveCount(0);
  await page.getByText("목록에서 제거한 프로젝트 · 복원", { exact: true }).click();
  await expect(page.locator(".archived-projects")).toContainText(project.name);
  await expect(page.locator(".archived-projects")).toContainText("저장소 파일과 실행 기록은 보존");
  await testInfo.attach("hidden-project", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.getByRole("button", { name: "목록으로 복원" }).click();
  await expect(card).toContainText(project.name);
  await page.reload();
  await expect(page.getByLabel("현재 프로젝트")).toHaveValue(project.id);
  expect(f.writes).toEqual([
    { path: "/api/projects/fixture/archive", body: { archived: true } },
    { path: "/api/projects/fixture/archive", body: { archived: false } },
  ]);
  expect(f.unexpected).toEqual([]);
});

test("shared documents retain revisions and inspect repository documents without deletion", async ({ page }, testInfo) => {
  const f = await mount(page);
  await page.getByRole("navigation").getByRole("button", { name: "공유 문서", exact: true }).click();
  const editor = page.getByLabel("공유 문서 내용");
  await expect(editor).toHaveValue("기존 목표를 보존합니다");
  await editor.fill("확인한 새 목표");
  await page.getByRole("button", { name: "공유 내용 저장" }).click();
  await expect.poll(() => f.history.length).toBe(2);
  await page.reload();
  await page.getByRole("navigation").getByRole("button", { name: "공유 문서", exact: true }).click();
  await expect(editor).toHaveValue("확인한 새 목표");
  await page.getByText("변경 이력", { exact: true }).click();
  const versions = page.locator(".shared-library details").filter({ has: page.locator("summary", { hasText: /^변경 이력$/ }) });
  await expect(versions.locator("pre")).toHaveText(["확인한 새 목표", "기존 목표를 보존합니다"]);
  for (const version of await versions.locator("details").all()) await version.locator("summary").click();
  await page.getByText("기존 Git 문서 살펴보기", { exact: true }).click();
  await page.getByRole("button", { name: "문서 목록 확인" }).click();
  await expect(page.locator(".shared-library li")).toHaveText("README.md");
  await testInfo.attach("document-revisions-and-original-inventory", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.getByRole("button", { name: "결정 기록", exact: true }).click();
  await expect(editor).toHaveValue("");
  await page.getByRole("button", { name: "작업 인수인계", exact: true }).click();
  await expect(editor).toHaveValue("");
  await page.getByRole("button", { name: "프로젝트 개요", exact: true }).click();
  await expect(editor).toHaveValue("확인한 새 목표");
  expect(f.writes).toEqual([{ path: "/api/projects/fixture/documents/brief", body: { title: "프로젝트 개요", body: "확인한 새 목표", expectedRevision: 1 } }]);
  expect(f.unexpected).toEqual([]);
});
