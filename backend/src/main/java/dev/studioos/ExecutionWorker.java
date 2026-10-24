package dev.studioos;

import static dev.studioos.Domain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExecutionWorker {
  private final RuntimeService runtime;
  private final GitWorkspaces git;
  private final VerificationSandbox sandbox;
  private final ObjectMapper json;
  private final ReviewService reviews;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final Map<String, Process> active = new ConcurrentHashMap<>();

  public ExecutionWorker(
      RuntimeService runtime, GitWorkspaces git, VerificationSandbox sandbox, ObjectMapper json, ReviewService reviews) {
    this.runtime = runtime;
    this.reviews = reviews;
    this.git = git;
    this.sandbox = sandbox;
    this.json = json;
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!busy.compareAndSet(false, true)) return;
    try {
      var next =
          runtime.tasks().stream()
              .filter(t -> t.status == Status.QUEUED)
              .min(Comparator.comparing(t -> t.createdAt));
      if (next.isEmpty()) {
        busy.set(false);
        return;
      }
      executor.submit(
          () -> {
            try {
              execute(next.get().id);
            } finally {
              busy.set(false);
            }
          });
    } catch (RuntimeException e) {
      busy.set(false);
      throw e;
    }
  }

  public void stop(String id) {
    Process p = active.get(id);
    if (p != null) Processes.kill(p);
  }

  private boolean cancelled(String id) {
    return RuntimeService.terminal(runtime.task(id).status);
  }

  private void execute(String id) {
    Path dir = runtime.artifacts(id);
    Task t = runtime.task(id);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(t.timeoutSeconds);
    try {
      t = runtime.update(id, Status.ANALYZING, "실행 조건과 격리 작업 폴더를 준비합니다", null, null, null);
      if (cancelled(id)) return;
      if (runtime.inspect(t).stream().anyMatch(r -> !r.available())) {
        runtime.update(id, Status.BLOCKED, "실행 조건이 변경되었습니다. 다시 확인하세요", null, null, null);
        return;
      }
      Files.createDirectories(dir);
      t.skillInstructions = runtime.prepareSkills(t, dir);
      Files.writeString(
          dir.resolve("skills.json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(t.skills));
      git.create(runtime.project(t.projectId), t);
      if (t.workflowRunId != null) {
        t.inputTree = git.applyInputs(t, runtime.data.resolve("artifacts"), dir);
        runtime.recordInputTree(id, t.inputTree);
        Files.writeString(
            dir.resolve("workflow.json"),
            json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(
                    Map.of(
                        "runId",
                        t.workflowRunId,
                        "nodeId",
                        t.workflowNodeId,
                        "attempt",
                        t.workflowAttempt,
                        "inputs",
                        t.workflowInputs,
                        "inputTree",
                        t.inputTree,
                        "requireVerification",
                        t.requireVerification)));
      }
      Files.writeString(
          dir.resolve("execution.json"),
          json.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "taskId",
                      id,
                      "adapter",
                      t.adapter,
                      "baseCommit",
                      t.baseCommit,
                      "branch",
                      t.branch,
                      "worktree",
                      t.worktree,
                      "testCommand",
                      t.testCommand,
                      "timeoutSeconds",
                      t.timeoutSeconds,
                      "approvedAt",
                      t.approvedAt)));
      if (t.improvementAnalysis)
        Files.writeString(dir.resolve("proposal-schema.json"), ImprovementPayload.schema());
      if (cancelled(id)) return;
      runtime.update(id, Status.RUNNING, "에이전트가 독립 worktree에서 작업 중입니다", null, null, null);
      var adapter = runtime.adapter(t.adapter);
      int exit;
      if (t.adapter.equals("demo")) {
        adapter.simulate(t, dir);
        exit = 0;
      } else
        exit =
            Processes.run(
                adapter.command(t, dir),
                Path.of(t.worktree),
                dir.resolve("agent.jsonl"),
                remaining(deadline),
                p -> register(id, p));
      active.remove(id);
      if (cancelled(id)) return;
      git.artifacts(t, dir);
      if (exit != 0) {
        runtime.update(
            id, Status.FAILED, "에이전트가 종료 코드 " + exit + "로 실패했습니다. 로그를 확인하세요", exit, null, null);
        return;
      }
      if (t.adapter.equals("codex") && !completed(dir.resolve("agent.jsonl"))) {
        runtime.update(id, Status.FAILED, "Codex 완료 이벤트가 없습니다. 로그를 확인하세요", exit, null, null);
        return;
      }
      if (t.adapter.equals("claude")) {
        var response = json.readTree(Files.readString(dir.resolve("agent.jsonl")));
        if (response.path("is_error").asBoolean(true)
            || !"success".equals(response.path("subtype").asText()))
          throw new IllegalArgumentException("Claude 완료 응답 실패: 로그를 확인하세요");
        if (t.improvementAnalysis)
          Files.writeString(
              dir.resolve("proposals.json"),
              json.writeValueAsString(response.get("structured_output")));
      }
      runtime.update(id, Status.VERIFYING, "변경 사항을 저장하고 완료 조건을 검증합니다", exit, null, null);
      if (t.improvementAnalysis) {
        if (!Processes.quick(Path.of(t.worktree), "git", "status", "--porcelain").isBlank())
          throw new IllegalArgumentException("조사 단계에서 저장소 변경이 발생했습니다");
        var result = ImprovementPayload.read(dir.resolve("proposals.json"));
        runtime.recordProposals(id, result.proposals());
        Files.writeString(
            dir.resolve("tests.log"),
            "Proposal schema and bounds verified; source worktree unchanged.\n");
        runtime.update(
            id,
            Status.WAITING_APPROVAL,
            "개선 후보 검증 통과 · 내용을 검토하고 결과를 승인하세요",
            exit,
            0,
            Verification.PASSED);
      } else if (!t.testCommand.isEmpty()) {
        int testExit =
            Processes.run(
                sandbox.command(Path.of(t.worktree), t.testCommand),
                Path.of(t.worktree),
                dir.resolve("tests.log"),
                remaining(deadline),
                p -> register(id, p));
        active.remove(id);
        if (cancelled(id)) return;
        if (testExit != 0) {
          runtime.update(
              id, Status.FAILED, "테스트 실패 · 종료 코드 " + testExit, exit, testExit, Verification.FAILED);
          return;
        }
        git.artifacts(t, dir);
        runtime.verificationPassed(id, testExit);
        if (t.requireReview) reviews.review(t, dir, deadline, p -> register(id, p));
        active.remove(id);
        if (cancelled(id)) return;
        runtime.update(
            id,
            Status.WAITING_APPROVAL,
            "테스트 및 필수 리뷰 통과 · 변경 사항을 검토하고 완료를 승인하세요",
            exit,
            testExit,
            Verification.PASSED);
      } else {
        git.artifacts(t, dir);
        if (t.requireReview) reviews.review(t, dir, deadline, p -> register(id, p));
        active.remove(id);
        if (cancelled(id)) return;
        runtime.update(
            id,
            Status.WAITING_APPROVAL,
            "자동 테스트 미실행 · 변경 사항과 로그를 직접 검토하세요",
            exit,
            null,
            Verification.NOT_RUN);
      }
    } catch (Exception e) {
      try {
        Files.createDirectories(dir);
        Files.writeString(
            dir.resolve("worker.log"),
            e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "실행 실패") + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
      } catch (Exception ignored) {
      }
      runtime.update(
          id,
          Status.FAILED,
          "실행 실패: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()),
          null,
          null,
          null);
    } finally {
      Process p = active.remove(id);
      if (p != null && p.isAlive()) Processes.kill(p);
      try {
        if (Set.of(Status.FAILED, Status.CANCELLED).contains(runtime.task(id).status)
            && Files.isDirectory(Path.of(t.worktree))) git.artifacts(t, dir);
      } catch (Exception ignored) {
      }
    }
  }

  private Duration remaining(long deadline) throws TimeoutException {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new TimeoutException("작업 시간 제한 초과");
    return Duration.ofNanos(nanos);
  }

  private void register(String id, Process p) {
    active.put(id, p);
    runtime.processStarted(id, p);
    if (cancelled(id)) Processes.kill(p);
  }

  private boolean completed(Path log) throws Exception {
    boolean done = false;
    for (String line : Files.readAllLines(log))
      try {
        var event = json.readTree(line);
        String type = event.path("type").asText();
        if (type.equals("turn.failed") || type.equals("error")) return false;
        if (type.equals("turn.completed")) done = true;
      } catch (Exception ignored) {
      }
    return done;
  }

  @PreDestroy
  public void close() {
    active.values().forEach(Processes::kill);
    executor.shutdownNow();
  }
}
