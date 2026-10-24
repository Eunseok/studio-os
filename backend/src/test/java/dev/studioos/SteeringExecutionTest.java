package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SteeringExecutionTest {
  @TempDir Path temp;
  final ObjectMapper json = new ObjectMapper();
  final Store store = mock(Store.class);
  final ProjectLibrary library = mock(ProjectLibrary.class);
  final CapabilityService capabilities = mock(CapabilityService.class);
  final GitWorkspaces git = mock(GitWorkspaces.class);
  final VerificationSandbox sandbox = mock(VerificationSandbox.class);
  final ReviewService reviews = mock(ReviewService.class);
  final AgentAdapter adapter = mock(AgentAdapter.class);
  final Map<String, String> saved = new ConcurrentHashMap<>();
  RuntimeService runtime;
  ExecutionWorker worker;
  Task task;

  void setup(String provider) throws Exception {
    when(store.get(eq("task"), anyString(), eq(Task.class))).thenAnswer(i ->
        json.readValue(saved.get(i.getArgument(1)), Task.class));
    when(store.all("task", Task.class)).thenAnswer(i -> {
      List<Task> result = new ArrayList<>();
      for (String value : saved.values()) result.add(json.readValue(value, Task.class));
      return result;
    });
    doAnswer(i -> { saved.put(i.getArgument(1), json.writeValueAsString(i.getArgument(2))); return null; })
        .when(store).save(eq("task"), anyString(), any(), anyString(), any(), anyMap());
    when(adapter.type()).thenReturn(provider);
    when(adapter.inspect()).thenReturn(List.of());
    when(adapter.steeringCapability(anyList())).thenReturn(provider + " single-call restart");
    when(capabilities.requirements(any())).thenReturn(List.of());
    when(capabilities.materialize(any(), any())).thenReturn("");
    when(sandbox.available()).thenReturn(true);
    when(sandbox.command(any(), anyList())).thenAnswer(i -> i.getArgument(1));
    runtime = new RuntimeService(store, library, null, capabilities, mock(ExpertiseService.class), git, sandbox,
        List.of(adapter), temp.resolve("data").toString());
    worker = new ExecutionWorker(runtime, git, sandbox, json, reviews);
    task = new Task();
    task.id = "task"; task.projectId = "project"; task.adapter = provider;
    task.prompt = "승인한 작업"; task.status = Status.QUEUED; task.createdAt = now();
    task.baseCommit = "base"; task.branch = "branch"; task.approvedAt = now();
    task.worktree = Files.createDirectory(temp.resolve("workspace")).toString();
    task.workspacePrepared = true; task.requireVerification = true; task.requireReview = true;
    task.testCommand = List.of("python3", "-c", "from pathlib import Path; Path('tested').write_text('passed')");
    when(adapter.command(any(), any())).thenAnswer(i -> {
      Task t = i.getArgument(0);
      String response = provider.equals("codex") ? "{\"type\":\"turn.completed\"}" : "{\"is_error\":false,\"subtype\":\"success\"}";
      String childScript = "import time\nfrom pathlib import Path\nwhile True:\n with Path('child-writes').open('a') as f: f.write('x')\n time.sleep(.01)\n";
      String script = t.steeringRevision == 0
          ? "import os,time,subprocess,sys; from pathlib import Path; Path('preserved').write_text('old changes'); "
              + "child=subprocess.Popen([sys.executable,'-c'," + json.writeValueAsString(childScript) + "]); "
              + "Path('child.pid').write_text(str(child.pid)); "
              + "Path('old.pid').write_text(str(os.getpid())); print('old output',flush=True); time.sleep(30)"
          : "import os; from pathlib import Path; "
              + "pid=int(Path('old.pid').read_text()); "
              + "assert not Path('/proc/'+str(pid)).exists() if os.path.exists('/proc') else True; "
              + "assert Path('preserved').read_text()=='old changes'; "
              + "Path('resumed').write_text('new direction'); print('" + response + "',flush=True)";
      if (t.steeringRevision > 0) assertThat(t.prompt).contains("방향 변경");
      return List.of("python3", "-c", script);
    });
    doAnswer(i -> {
      Task t = i.getArgument(0);
      assertThat(Files.readString(Path.of(t.worktree).resolve("tested"))).isEqualTo("passed");
      runtime.reviewProgress(t.id, decision(provider), new ReviewService.Result("PASS", "확인", List.of()), "digest");
      return null;
    }).when(reviews).review(any(), any(), anyLong(), any());
    saveTask();
  }

  @AfterEach void close() { if (worker != null) worker.close(); }

  @ParameterizedTest @ValueSource(strings = {"codex", "claude"})
  void interruptionPreservesWorkspaceAndRechecksTheNewResult(String provider) throws Exception {
    setup(provider);
    worker.tick();
    Path cwd = Path.of(task.worktree);
    await(() -> Files.exists(cwd.resolve("old.pid")) && Files.exists(cwd.resolve("child-writes")));
    long oldPid = Long.parseLong(Files.readString(cwd.resolve("old.pid")));
    long childPid = Long.parseLong(Files.readString(cwd.resolve("child.pid")));
    worker.pauseForSteering(task.id);
    await(() -> runtime.task(task.id).steeringStopped);
    assertThat(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    assertThat(runtime.task(task.id).verification).isEqualTo(Verification.NOT_RUN);
    assertThatThrownBy(() -> runtime.approve(task.id)).hasMessageContaining("추가 지시");
    runtime.resumeSteering(task.id, "방향 변경");
    worker.tick();
    await(() -> runtime.task(task.id).status == Status.WAITING_APPROVAL);
    Task result = runtime.task(task.id);
    assertThat(result.worktree).isEqualTo(task.worktree);
    assertThat(result.verification).isEqualTo(Verification.PASSED);
    assertThat(result.reviewStatus).isEqualTo("PASSED");
    assertThat(result.steeringAppliedRevision).isEqualTo(1);
    assertThat(result.steeringCapability).contains(provider);
    assertThat(Files.readString(runtime.artifacts(task.id).resolve("agent.jsonl"))).doesNotContain("old output");
    assertThat(Files.readString(runtime.artifacts(task.id).resolve("invocations/before-1/agent.jsonl"))).contains("old output");
    verify(reviews).review(any(), any(), anyLong(), any());
    verify(git, never()).create(any(), any());
    verify(git, never()).applyInputs(any(), any(), any());
  }

  @Test void resumeWaitsForTheWholeWorkerAndInvalidatesLateReview() throws Exception {
    setup("codex");
    var inReview = new CountDownLatch(1);
    var releaseReview = new CountDownLatch(1);
    // Run the first implementation to completion, then pause while its review is in flight.
    doReturn(List.of("python3", "-c", "print('{\"type\":\"turn.completed\"}')"))
        .when(adapter).command(any(), any());
    doAnswer(i -> {
      inReview.countDown();
      assertThat(releaseReview.await(5, TimeUnit.SECONDS)).isTrue();
      runtime.reviewProgress(task.id, decision("codex"), new ReviewService.Result("PASS", "늦은 리뷰", List.of()), "old-digest");
      return null;
    }).when(reviews).review(any(), any(), anyLong(), any());
    worker.tick();
    assertThat(inReview.await(5, TimeUnit.SECONDS)).isTrue();
    worker.pauseForSteering(task.id);
    assertThat(runtime.task(task.id).steeringStopped).isFalse();
    assertThatThrownBy(() -> runtime.resumeSteering(task.id, "방향 변경")).hasMessageContaining("완전히 종료");
    releaseReview.countDown();
    await(() -> runtime.task(task.id).steeringStopped);
    assertThat(runtime.task(task.id).review).isNull();
    assertThat(runtime.task(task.id).reviewDigest).isNull();
    assertThat(runtime.task(task.id).verification).isEqualTo(Verification.NOT_RUN);
    assertThat(runtime.task(task.id).status).isEqualTo(Status.VERIFYING);
  }

  @Test void queuedPauseDoesNotStartUntilApproved() throws Exception {
    setup("codex");
    worker.pauseForSteering(task.id);
    worker.tick();
    assertThat(runtime.task(task.id).steeringStopped).isTrue();
    verify(adapter, never()).command(any(), any());
    assertThat(runtime.task(task.id).steeringAppliedRevision).isZero();
  }

  @Test void unconfirmedTerminationBlocksResume() throws Exception {
    setup("codex");
    worker.pauseForSteering(task.id);
    runtime.steeringStopFailed(task.id);
    runtime.steeringStopped(task.id);
    assertThatThrownBy(() -> runtime.resumeSteering(task.id, "방향 변경")).hasMessageContaining("완전히 종료");
    worker.tick();
    verify(adapter, never()).command(any(), any());
  }

  @Test void steeringDuringModelSelectionWaitsForItsFrozenDecision() throws Exception {
    setup("codex");
    task.routingPending = true;
    task.status = Status.BLOCKED;
    saveTask();
    worker.pauseForSteering(task.id);
    Task resumed = runtime.resumeSteering(task.id, "방향 변경");
    assertThat(resumed.status).isEqualTo(Status.BLOCKED);
    assertThat(resumed.routingPending).isTrue();
    worker.tick();
    verify(adapter, never()).command(any(), any());
  }

  private void saveTask() throws Exception { saved.put(task.id, json.writeValueAsString(task)); }
  private ModelPlanner.Decision decision(String provider) {
    return new ModelPlanner.Decision("id", now(), "project", "review", List.of(),
        new ModelPlanner.Choice(provider, "model", "low", "reason", "high", List.of("evidence")), provider, "model");
  }
  static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    while (!condition.call()) {
      if (System.nanoTime() > deadline) fail("실행 상태 변경 시간 초과");
      Thread.sleep(20);
    }
  }
}
