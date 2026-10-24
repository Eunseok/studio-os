package dev.studioos;

import static dev.studioos.Domain.*;

import com.fasterxml.jackson.databind.*;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GithubService {
  public static class Binding {
    public String projectId, repo, owner, base, boardId, boardUrl, fieldId, since, error, lastSync;
    public int number;
    public Map<String, String> options = new HashMap<>();
  }

  public static class Work {
    public String id,
        projectId,
        taskId,
        title,
        issueUrl,
        itemId,
        prUrl,
        head,
        prState,
        column = "Todo",
        error,
        runtimeStatus,
        message,
        reviewedHead;
    public boolean canPublish;
    public int issueNumber;
  }

  public record Connect(String repo, String owner, int number) {}

  private final RuntimeService runtime;
  private final Store store;
  private final GitWorkspaces git;
  private final GithubCommand cmd;
  private final ObjectMapper json;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean syncing = new AtomicBoolean();

  public GithubService(
      RuntimeService runtime,
      Store store,
      GitWorkspaces git,
      GithubCommand cmd,
      ObjectMapper json) {
    this.runtime = runtime;
    this.store = store;
    this.git = git;
    this.cmd = cmd;
    this.json = json;
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }

  private String run(Path cwd, String... args) throws Exception {
    return cmd.run(cwd, args);
  }

  private JsonNode gh(Path cwd, String... args) throws Exception {
    List<String> a = new ArrayList<>(List.of("gh"));
    a.addAll(List.of(args));
    return json.readTree(run(cwd, a.toArray(String[]::new)));
  }

  private Path root(String id) {
    return Path.of(runtime.project(id).path());
  }

  public List<Binding> bindings() {
    return store.all("github-binding", Binding.class);
  }

  public Binding binding(String id) {
    return store.get("github-binding", "github-binding:" + id, Binding.class);
  }

  public List<Work> works(String id) {
    return store.all("github-work", Work.class).stream()
        .filter(w -> w.projectId.equals(id))
        .toList();
  }

  private void save(Binding b) {
    store.save(
        "github-binding",
        "github-binding:" + b.projectId,
        b,
        "github.updated",
        null,
        Map.of("projectId", b.projectId));
  }

  private void save(Work w) {
    store.save(
        "github-work", w.id, w, "github.work.updated", w.taskId, Map.of("projectId", w.projectId));
  }

  public synchronized Binding connect(String id, Connect c) throws Exception {
    if (c.repo() == null
        || !c.repo().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
        || c.owner() == null
        || !c.owner().matches("[A-Za-z0-9-]+")
        || c.number() < 1) throw new IllegalArgumentException("저장소와 Projects 소유자·번호를 확인하세요");
    Path cwd = root(id);
    JsonNode repo = gh(cwd, "repo", "view", c.repo(), "--json", "nameWithOwner,defaultBranchRef");
    JsonNode board =
        gh(cwd, "project", "view", "" + c.number(), "--owner", c.owner(), "--format", "json");
    Binding b = new Binding();
    b.projectId = id;
    b.repo = repo.path("nameWithOwner").asText();
    b.base = repo.path("defaultBranchRef").path("name").asText();
    b.owner = c.owner();
    b.number = c.number();
    b.boardId = board.path("id").asText();
    b.boardUrl = board.path("url").asText();
    b.since = now();
    for (JsonNode f :
        gh(cwd, "project", "field-list", "" + b.number, "--owner", b.owner, "--format", "json")
            .path("fields"))
      if (f.path("name").asText().equals("Status")) {
        b.fieldId = f.path("id").asText();
        for (JsonNode o : f.path("options"))
          b.options.put(o.path("name").asText(), o.path("id").asText());
      }
    if (!b.options.keySet().containsAll(List.of("Todo", "In Progress", "In Review", "Done")))
      throw new IllegalArgumentException(
          "Projects Status에 Todo, In Progress, In Review, Done 옵션을 준비하세요");
    if (b.base.isBlank()) throw new IllegalArgumentException("첫 커밋이 있는 저장소가 필요합니다");
    for (Binding old : bindings())
      if (old.projectId.equals(id)) {
        if (!old.repo.equals(b.repo) || !old.boardId.equals(b.boardId))
          throw new IllegalArgumentException("이미 연결된 보드입니다. 기존 작업 연결을 보존합니다");
        b.since = old.since;
      }
    save(b);
    return b;
  }

  public Map<String, Object> board(String id) {
    return Map.of(
        "bindings",
        bindings().stream().filter(b -> b.projectId.equals(id)).toList(),
        "items",
        works(id),
        "syncing",
        syncing.get());
  }

  @Scheduled(fixedDelay = 30000)
  public void tick() {
    requestSync();
  }

  public void requestSync() {
    if (syncing.compareAndSet(false, true))
      executor.submit(
          () -> {
            try {
              for (Binding b : bindings())
                try {
                  sync(b.projectId);
                } catch (Exception ignored) {
                }
            } finally {
              syncing.set(false);
            }
          });
  }

  public synchronized void sync(String id) throws Exception {
    Binding b = binding(id);
    try {
      Map<String, Task> latest = new LinkedHashMap<>();
      for (Task t : runtime.tasks())
        if (t.projectId.equals(id) && !t.improvementAnalysis) {
          String key = t.workflowRunId == null ? t.id : t.workflowRunId;
          latest.putIfAbsent(key, t);
        }
      for (var e : latest.entrySet()) {
        Task t = e.getValue();
        Work w =
            works(id).stream()
                .filter(x -> x.id.equals("github-work:" + e.getKey()))
                .findFirst()
                .orElse(null);
        if (w == null) {
          if (t.createdAt.compareTo(b.since) < 0
              && Set.of(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED).contains(t.status)
              && store.all("conversation", ChiefService.Conversation.class).stream()
                  .noneMatch(
                      c ->
                          Objects.equals(c.runId, t.workflowRunId)
                              && c.runId != null
                              && !Set.of("COMPLETE", "CANCELLED").contains(c.state))) continue;
          w = new Work();
          w.id = "github-work:" + e.getKey();
          w.projectId = id;
          w.taskId = t.id;
          w.title =
              t.workflowRunId == null
                  ? t.title
                  : store.get("workflow-run", t.workflowRunId, WorkflowDomain.Run.class)
                      .definition
                      .name;
          save(w);
        }
        w.taskId = t.id;
        try {
          ensureIssue(b, w);
          refresh(b, w, t);
          w.error = null;
        } catch (Exception ex) {
          w.error = ex.getMessage();
        }
        save(w);
      }
      b.error = null;
      b.lastSync = now();
      save(b);
    } catch (Exception ex) {
      b.error = ex.getMessage();
      save(b);
      throw ex;
    }
  }

  private void ensureIssue(Binding b, Work w) throws Exception {
    Path cwd = root(b.projectId);
    if (w.issueUrl == null) {
      JsonNode found =
          gh(
              cwd,
              "issue",
              "list",
              "--repo",
              b.repo,
              "--state",
              "all",
              "--search",
              w.id + " in:body",
              "--json",
              "url,number,body",
              "--limit",
              "100");
      for (JsonNode n : found)
        if (n.path("body").asText().contains("<!-- " + w.id + " -->")) {
          w.issueUrl = n.path("url").asText();
          w.issueNumber = n.path("number").asInt();
          break;
        }
      if (w.issueUrl == null) {
        w.issueUrl =
            run(
                    cwd,
                    "gh",
                    "issue",
                    "create",
                    "--repo",
                    b.repo,
                    "--title",
                    w.title,
                    "--body",
                    "Studio OS에서 진행하는 작업입니다. 검증·독립 리뷰·결과 승인 후 PR로 반영합니다.\n\n<!-- " + w.id + " -->")
                .trim();
        w.issueNumber = Integer.parseInt(w.issueUrl.substring(w.issueUrl.lastIndexOf('/') + 1));
      }
      save(w);
    }
    if (w.itemId == null) {
      w.itemId =
          gh(
                  cwd,
                  "project",
                  "item-add",
                  "" + b.number,
                  "--owner",
                  b.owner,
                  "--url",
                  w.issueUrl,
                  "--format",
                  "json")
              .path("id")
              .asText();
      save(w);
    }
  }

  static String column(Task t, String prState) {
    if ("MERGED".equals(prState)) return "Done";
    if (prState != null || t.status == Status.SUCCEEDED || t.status == Status.WAITING_APPROVAL)
      return "In Review";
    return Set.of(Status.QUEUED, Status.BLOCKED).contains(t.status) ? "Todo" : "In Progress";
  }

  private void refresh(Binding b, Work w, Task t) throws Exception {
    if (w.prUrl != null) {
      JsonNode pr =
          gh(
              root(b.projectId),
              "pr",
              "view",
              w.prUrl,
              "--repo",
              b.repo,
              "--json",
              "state,headRefOid");
      w.prState = pr.path("state").asText();
      w.head = pr.path("headRefOid").asText();
    }
    w.column = column(t, w.prState);
    w.runtimeStatus = t.status.name();
    w.message = t.message;
    w.canPublish =
        t.status == Status.SUCCEEDED
            && t.verification == Verification.PASSED
            && "PASSED".equals(t.reviewStatus);
    run(
        root(b.projectId),
        "gh",
        "project",
        "item-edit",
        "--id",
        w.itemId,
        "--project-id",
        b.boardId,
        "--field-id",
        b.fieldId,
        "--single-select-option-id",
        b.options.get(w.column));
  }

  public synchronized Work publish(String id, String workId) throws Exception {
    Binding b = binding(id);
    Work w = works(id).stream().filter(x -> x.id.equals(workId)).findFirst().orElseThrow();
    Task t = runtime.task(w.taskId);
    Path cwd = root(id);
    if (w.prUrl != null) return w;
    if (t.status != Status.SUCCEEDED
        || t.verification != Verification.PASSED
        || !"PASSED".equals(t.reviewStatus))
      throw new IllegalArgumentException("검증·독립 리뷰·결과 승인이 먼저 필요합니다");
    if (t.workflowRunId != null) {
      var r = store.get("workflow-run", t.workflowRunId, WorkflowDomain.Run.class);
      Set<String> included = new HashSet<>();
      included.add(t.workflowNodeId);
      t.workflowInputs.forEach(i -> included.add(i.nodeId()));
      if (!"SUCCEEDED".equals(r.status) || !included.containsAll(r.steps.keySet()))
        throw new IllegalArgumentException("전체 Workflow의 최종 승인 결과만 PR로 만들 수 있습니다");
    }
    git.artifacts(t, runtime.artifacts(t.id));
    Path patch = runtime.artifacts(t.id).resolve("changes.patch");
    if (!Objects.equals(t.reviewDigest, GitWorkspaces.patchDigest(patch)) || Files.size(patch) == 0)
      throw new IllegalArgumentException("리뷰한 변경과 현재 코드가 다르거나 변경이 없습니다");
    String branch = "studio/result-" + t.id;
    JsonNode existing =
        gh(
            cwd, "pr", "list", "--repo", b.repo, "--head", branch, "--state", "all", "--json",
            "url", "--limit", "10");
    if (!existing.isEmpty()) {
      w.prUrl = existing.get(0).path("url").asText();
      save(w);
      refresh(b, w, t);
      save(w);
      return w;
    }
    String remote = "https://github.com/" + b.repo + ".git";
    run(cwd, "git", "fetch", "--no-tags", remote, b.base);
    if (!run(cwd, "git", "rev-parse", "FETCH_HEAD").equals(t.baseCommit))
      throw new IllegalArgumentException("GitHub 기준 커밋이 작업 기준과 다릅니다. 최신 기준에서 다시 검증해야 합니다");
    Path staging = runtime.data.resolve("github-publish");
    Files.createDirectories(staging);
    Path dir = Files.createTempDirectory(staging, "pr-");
    Files.delete(dir);
    try {
      run(
          cwd,
          "git",
          "-c",
          "core.hooksPath=/dev/null",
          "worktree",
          "add",
          "--detach",
          dir.toString(),
          t.baseCommit);
      run(dir, "git", "apply", "--check", "--index", patch.toString());
      run(dir, "git", "apply", "--index", patch.toString());
      run(
          dir,
          "git",
          "-c",
          "core.hooksPath=/dev/null",
          "-c",
          "commit.gpgsign=false",
          "-c",
          "user.name=Studio OS",
          "-c",
          "user.email=studio@localhost",
          "commit",
          "-m",
          t.title);
      // Never force-push. A different remote result requires inspection.
      w.reviewedHead = run(dir, "git", "rev-parse", "HEAD");
      save(w);
      run(dir, "git", "push", remote, "HEAD:refs/heads/" + branch);
      ensureIssue(b, w);
      Path body = Files.createTempFile(staging, "body-", ".md");
      try {
        Files.writeString(
            body,
            "Closes #"
                + w.issueNumber
                + "\n\n"
                + "Studio OS: automated verification and independent review passed; result approved"
                + " by the user.\n");
        w.prUrl =
            run(
                    cwd,
                    "gh",
                    "pr",
                    "create",
                    "--repo",
                    b.repo,
                    "--base",
                    b.base,
                    "--head",
                    branch,
                    "--title",
                    t.title,
                    "--body-file",
                    body.toString())
                .trim();
        save(w);
      } finally {
        Files.deleteIfExists(body);
      }
    } finally {
      try {
        run(cwd, "git", "worktree", "remove", "--force", dir.toString());
      } catch (Exception ignored) {
      }
    }
    refresh(b, w, t);
    save(w);
    return w;
  }

  public synchronized Work merge(String id, String workId, String expectedHead) throws Exception {
    Binding b = binding(id);
    Work w = works(id).stream().filter(x -> x.id.equals(workId)).findFirst().orElseThrow();
    if (w.prUrl == null || expectedHead == null || !expectedHead.matches("[0-9a-f]{40}"))
      throw new IllegalArgumentException("PR을 먼저 확인하세요");
    if (!Objects.equals(w.head, expectedHead) || !Objects.equals(w.reviewedHead, expectedHead))
      throw new IllegalArgumentException("PR이 변경되었습니다. 새 결과를 확인하세요");
    JsonNode remotePr =
        gh(
            root(id),
            "pr",
            "view",
            w.prUrl,
            "--repo",
            b.repo,
            "--json",
            "state,isDraft,headRefOid,statusCheckRollup,mergeStateStatus");
    if (!"OPEN".equals(remotePr.path("state").asText())
        || remotePr.path("isDraft").asBoolean()
        || !expectedHead.equals(remotePr.path("headRefOid").asText()))
      throw new IllegalArgumentException("열린 PR의 최신 변경을 다시 확인하세요");
    if (Set.of("DIRTY", "BLOCKED", "UNKNOWN", "BEHIND")
        .contains(remotePr.path("mergeStateStatus").asText()))
      throw new IllegalArgumentException("GitHub 머지 조건이 충족되지 않았습니다");
    for (JsonNode check : remotePr.path("statusCheckRollup")) {
      String state =
          check.has("conclusion")
              ? check.path("conclusion").asText()
              : check.path("state").asText();
      if (!Set.of("SUCCESS", "NEUTRAL", "SKIPPED").contains(state))
        throw new IllegalArgumentException("GitHub 검사가 진행 중이거나 실패했습니다");
    }
    run(
        root(id),
        "gh",
        "pr",
        "merge",
        w.prUrl,
        "--repo",
        b.repo,
        "--squash",
        "--match-head-commit",
        expectedHead);
    refresh(b, w, runtime.task(w.taskId));
    save(w);
    return w;
  }

  public synchronized Map<String, String> pull(String id) throws Exception {
    Binding b = binding(id);
    Path cwd = root(id);
    if (runtime.tasks().stream()
        .anyMatch(
            t ->
                t.projectId.equals(id)
                    && Set.of(Status.QUEUED, Status.ANALYZING, Status.RUNNING, Status.VERIFYING)
                        .contains(t.status)))
      throw new IllegalArgumentException("실행 중인 작업이 끝난 뒤 업데이트하세요");
    if (!run(cwd, "git", "status", "--porcelain").isBlank()
        || !run(cwd, "git", "branch", "--show-current").equals(b.base))
      throw new IllegalArgumentException("기본 브랜치의 깨끗한 작업 폴더에서만 업데이트할 수 있습니다");
    run(cwd, "git", "fetch", "--no-tags", "https://github.com/" + b.repo + ".git", b.base);
    run(cwd, "git", "-c", "core.hooksPath=/dev/null", "merge", "--ff-only", "FETCH_HEAD");
    return Map.of(
        "commit",
        run(cwd, "git", "rev-parse", "HEAD"),
        "message",
        "코드 업데이트 완료. 실행 중인 앱은 평소 실행 스크립트로 다시 시작하세요.");
  }
}
