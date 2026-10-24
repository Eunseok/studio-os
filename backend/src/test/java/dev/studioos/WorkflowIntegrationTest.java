package dev.studioos;

import static dev.studioos.Domain.*;
import static dev.studioos.WorkflowDomain.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class WorkflowIntegrationTest {
  // These suites exercise execution/workflow behavior; expertise gates have their own integration suite.
  @org.springframework.test.context.bean.override.mockito.MockitoBean ExpertiseService expertise;
  @org.junit.jupiter.api.BeforeEach void eligibleFixture() { org.mockito.Mockito.when(expertise.eligible(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(true); }

  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("studio-workflow-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("studio.data", DATA::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:workflow-test;DB_CLOSE_DELAY=-1");
  }

  @Autowired RuntimeService runtime;
  @Autowired WorkflowService workflows;
  @Autowired CapabilityService capabilities;
  @Autowired ExecutionWorker worker;
  @Autowired Store store;
  @TempDir Path temp;

  @TestConfiguration
  static class Fixtures {
    @Bean
    AgentAdapter brokenInspector() {
      return new AgentAdapter() {
        public String type() {
          return "workflow-broken";
        }

        public List<Requirement> inspect() {
          throw new IllegalStateException("fixture preparation failure");
        }

        public List<String> command(Task t, Path p) {
          throw new AssertionError("must not execute");
        }
      };
    }

    @Bean
    AgentAdapter workflowFixture() {
      return new AgentAdapter() {
        public String type() {
          return "workflow-fixture";
        }

        public List<Requirement> inspect() {
          return List.of(new Requirement("fixture", true, "local test process"));
        }

        public List<String> command(Task task, Path artifacts) {
          return List.of("/bin/sh", "-ec", task.prompt.split("\n\n이전 시도:")[0]);
        }
      };
    }
  }

  @Test
  void failedRunCreationRollsBackAllPreparedTasksAndCanReuseRequestKey() throws Exception {
    var p = project();
    Node a = node("a", List.of(), "true", "true");
    Node b = node("b", List.of(), "true", "true");
    var def = workflows.saveDefinition(null, "Rollback", p.id(), List.of(a, b), null);
    var broken = runtime.addAgent("Broken inspector", "Developer", "workflow-broken");
    b.agentId = broken.id();
    def = workflows.saveDefinition(def.id, "Rollback", p.id(), List.of(a, b), def.revision);
    long tasks = runtime.tasks().size();
    long runs = workflows.runs().size();
    String key = UUID.randomUUID().toString();
    String definitionId = def.id;
    assertThatThrownBy(() -> workflows.start(definitionId, key))
        .isInstanceOf(IllegalStateException.class);
    assertThat(runtime.tasks()).hasSize((int) tasks);
    assertThat(workflows.runs()).hasSize((int) runs);
    b.agentId = a.agentId;
    workflows.saveDefinition(def.id, "Fixed", p.id(), List.of(a, b), def.revision);
    var run = workflows.start(def.id, key);
    assertThat(run.steps.values()).allMatch(step -> step.attempts.size() == 1);
    workflows.cancel(run.id);
  }

  Project project() throws Exception {
    Path repo = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
    Processes.quick(repo, "git", "init");
    Files.writeString(repo.resolve("README.md"), "# Workflow fixture\n");
    Processes.quick(repo, "git", "add", "README.md");
    Processes.quick(
        repo,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-m",
        "fixture");
    return runtime.addProject(
        "Workflow fixture", repo.toString(), "Frozen rules", List.of("/bin/sh", "-c", "true"));
  }

  Node node(String id, List<String> deps, String command, String test) {
    Node n = new Node();
    n.id = id;
    n.title = "Step " + id;
    n.agentId = runtime.addAgent("Fixture", "Developer", "workflow-fixture").id();
    n.prompt = command;
    n.dependsOn = deps;
    n.testCommand = List.of("/bin/sh", "-ec", test);
    n.timeoutSeconds = 30;
    return n;
  }

  Run start(Project p, Node... nodes) throws Exception {
    var d = workflows.saveDefinition(null, "Test DAG", p.id(), List.of(nodes), null);
    return workflows.start(d.id, UUID.randomUUID().toString());
  }

  Task task(Run run, String node) {
    var step = workflows.run(run.id).steps.get(node);
    return runtime.task(step.attempts.getLast());
  }

  Task awaitTask(String id, Status status) throws Exception {
    long until = System.currentTimeMillis() + 15000;
    while (System.currentTimeMillis() < until) {
      var t = runtime.task(id);
      if (t.status == status && (status != Status.WAITING_APPROVAL || t.agentExitCode != null))
        return t;
      if (t.status == Status.FAILED && status != Status.FAILED) throw new AssertionError(t.message);
      Thread.sleep(50);
    }
    throw new AssertionError(
        "Timed out: " + runtime.task(id).status + " " + runtime.task(id).message);
  }

  Task finish(Run run, String node) throws Exception {
    Task task = task(run, node);
    runtime.approve(task.id);
    var result = awaitTask(task.id, Status.WAITING_APPROVAL);
    assertThat(result.verification).isEqualTo(Verification.PASSED);
    runtime.approve(task.id);
    workflows.reconcile(run.id);
    return runtime.task(task.id);
  }

  @Test
  void sequentialWorkflowWaitsForResultApprovalAndPassesCodeToNextStep() throws Exception {
    var p = project();
    var run =
        start(
            p,
            node("a", List.of(), "printf A > a.txt", "test -f a.txt"),
            node(
                "b",
                List.of("a"),
                "test -f a.txt; printf B > b.txt",
                "test -f a.txt && test -f b.txt"));
    Task a = task(run, "a");
    assertThat(workflows.run(run.id).steps.get("b").attempts).isEmpty();
    runtime.approve(a.id);
    awaitTask(a.id, Status.WAITING_APPROVAL);
    workflows.reconcile(run.id);
    assertThat(workflows.run(run.id).steps.get("b").attempts).isEmpty();
    runtime.approve(a.id);
    workflows.reconcile(run.id);
    Task b = task(run, "b");
    assertThat(b.workflowInputs).hasSize(1);
    assertThat(b.baseCommit).isEqualTo(run.baseCommit);
    finish(run, "b");
    assertThat(workflows.run(run.id).status).isEqualTo("SUCCEEDED");
    assertThat(Files.readString(Path.of(b.worktree).resolve("a.txt"))).isEqualTo("A");
    assertThat(Files.readString(runtime.artifacts(b.id).resolve("step.patch")))
        .contains("b.txt")
        .doesNotContain("a.txt");
    assertThat(Files.readString(runtime.artifacts(b.id).resolve("changes.patch")))
        .contains("a.txt", "b.txt");
    assertThat(Processes.quick(Path.of(p.path()), "git", "status", "--porcelain")).isEmpty();
  }

  @Test
  void diamondGraphAppliesCommonAncestorExactlyOnce() throws Exception {
    var run =
        start(
            project(),
            node("a", List.of(), "printf A > root.txt", "test -f root.txt"),
            node("b", List.of("a"), "printf B > left.txt", "test -f root.txt"),
            node("c", List.of("a"), "printf C > right.txt", "test -f root.txt"),
            node(
                "d",
                List.of("b", "c"),
                "cat root.txt left.txt right.txt > joined.txt",
                "test $(cat joined.txt) = ABC"));
    finish(run, "a");
    assertThat(task(run, "b").status).isEqualTo(Status.WAITING_APPROVAL);
    assertThat(task(run, "c").status).isEqualTo(Status.WAITING_APPROVAL);
    finish(run, "b");
    assertThat(workflows.run(run.id).steps.get("d").attempts).isEmpty();
    finish(run, "c");
    Task d = task(run, "d");
    assertThat(d.workflowInputs.stream().map(Input::nodeId)).containsExactly("a", "b", "c");
    finish(run, "d");
    assertThat(Files.readString(Path.of(d.worktree).resolve("joined.txt"))).isEqualTo("ABC");
    assertThat(workflows.run(run.id).status).isEqualTo("SUCCEEDED");
  }

  @Test
  void verificationFailureRetriesInNewWorktreeAndPreservesHistory() throws Exception {
    Path flag = temp.resolve("fix-enabled");
    var run =
        start(
            project(),
            node(
                "a",
                List.of(),
                "if [ -f '"
                    + flag
                    + "' ]; then printf good > result.txt; else printf bad > result.txt; fi",
                "test $(cat result.txt) = good"),
            node("b", List.of("a"), "printf done > done.txt", "test -f result.txt"));
    Task first = task(run, "a");
    runtime.approve(first.id);
    awaitTask(first.id, Status.FAILED);
    workflows.reconcile(run.id);
    assertThat(runtime.task(first.id).verification).isEqualTo(Verification.FAILED);
    assertThatThrownBy(() -> runtime.approve(first.id))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(workflows.run(run.id).steps.get("b").attempts).isEmpty();
    workflows.reconcile(run.id);
    assertThat(workflows.run(run.id).steps.get("a").attempts).hasSize(1);
    Files.writeString(flag, "fixed");
    workflows.retry(run.id, "a", first.id, "Read the earlier test log.");
    Task second = task(run, "a");
    assertThat(second.id).isNotEqualTo(first.id);
    assertThat(second.worktree).isNotEqualTo(first.worktree);
    assertThat(second.workflowAttempt).isEqualTo(2);
    assertThat(second.status).isEqualTo(Status.WAITING_APPROVAL);
    assertThatThrownBy(() -> workflows.retry(run.id, "a", first.id, ""))
        .isInstanceOf(IllegalArgumentException.class);
    finish(run, "a");
    finish(run, "b");
    assertThat(workflows.run(run.id).steps.get("a").attempts).containsExactly(first.id, second.id);
    assertThat(runtime.task(first.id).status).isEqualTo(Status.FAILED);
    assertThat(Files.readString(Path.of(first.worktree).resolve("result.txt"))).isEqualTo("bad");
  }

  @Test
  void maximumAttemptsAndRequiredVerificationCannotBeBypassed() throws Exception {
    var run = start(project(), node("a", List.of(), "exit 7", "true"));
    Task first = task(run, "a");
    runtime.approve(first.id);
    awaitTask(first.id, Status.FAILED);
    workflows.retry(run.id, "a", first.id, "");
    Task second = task(run, "a");
    runtime.approve(second.id);
    awaitTask(second.id, Status.FAILED);
    assertThatThrownBy(() -> workflows.retry(run.id, "a", second.id, ""))
        .hasMessageContaining("최대 시도");
    // Even the generic Runtime approval endpoint enforces the workflow verification gate.
    var gate = start(project(), node("gate", List.of(), "true", "true"));
    Task t = task(gate, "gate");
    t.agentExitCode = 0;
    t.verification = Verification.NOT_RUN;
    store.save("task", t.id, t, "test.fixture", t.id, Map.of());
    assertThatThrownBy(() -> runtime.approve(t.id)).hasMessageContaining("필수 검증");
    workflows.cancel(gate.id);
  }

  @Test
  void academyBlockRechecksSameAttemptBeforeExecution() throws Exception {
    var p = project();
    Node n = node("a", List.of(), "printf ready > ready.txt", "test -f ready.txt");
    n.requiredSkills = List.of("workflow-skill");
    var run = start(p, n);
    var t = task(run, "a");
    assertThat(t.status).isEqualTo(Status.BLOCKED);
    assertThat(run.status).isEqualTo("BLOCKED");
    var skill =
        capabilities.register(
            p.id(),
            null,
            "---\nname: workflow-skill\ndescription: Workflow test\n---\nReview files.\n");
    capabilities.validate(skill.id);
    capabilities.activate(skill.id);
    runtime.recheck(t.id);
    workflows.reconcile(run.id);
    assertThat(task(run, "a").id).isEqualTo(t.id);
    assertThat(task(run, "a").skills.getFirst().id()).isEqualTo(skill.id);
    finish(run, "a");
    assertThat(workflows.run(run.id).status).isEqualTo("SUCCEEDED");
  }

  @Test
  void invalidGraphsAndStaleEditsAreRejectedAndRunKeepsFrozenPlan() throws Exception {
    var p = project();
    Node a = node("a", List.of("b"), "true", "true"), b = node("b", List.of("a"), "true", "true");
    assertThatThrownBy(() -> workflows.saveDefinition(null, "Cycle", p.id(), List.of(a, b), null))
        .hasMessageContaining("순환");
    a.dependsOn = List.of("missing");
    assertThatThrownBy(() -> workflows.saveDefinition(null, "Missing", p.id(), List.of(a), null))
        .hasMessageContaining("알 수 없는");
    a.dependsOn = List.of();
    a.testCommand = List.of();
    assertThatThrownBy(
            () -> workflows.saveDefinition(null, "Empty tests", p.id(), List.of(a), null))
        .hasMessageContaining("필수 검증");
    a.testCommand = null;
    a.prompt = "printf A > a.txt";
    b.prompt = "printf B > b.txt";
    var def = workflows.saveDefinition(null, "Frozen", p.id(), List.of(a, b), null);
    var run = workflows.start(def.id, UUID.randomUUID().toString());
    a.prompt = "exit 99";
    assertThatThrownBy(() -> workflows.saveDefinition(def.id, "Updated", p.id(), List.of(a, b), 0))
        .hasMessageContaining("정의가 변경");
    workflows.saveDefinition(def.id, "Updated", p.id(), List.of(a, b), def.revision);
    runtime.settings(p.id(), "Changed rules", List.of("/bin/false"));
    finish(run, "a");
    Task next = task(run, "b");
    assertThat(next.rules).isEqualTo("Frozen rules");
    assertThat(next.testCommand).containsExactly("/bin/sh", "-ec", "true");
    assertThat(workflows.run(run.id).definition.revision).isEqualTo(1);
    finish(run, "b");
  }

  @Test
  void concurrentStartAndRepeatedReconcileNeverDuplicateAttempts() throws Exception {
    var p = project();
    var def =
        workflows.saveDefinition(
            null, "Idempotent", p.id(), List.of(node("a", List.of(), "true", "true")), null);
    String key = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<Run>>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> workflows.start(def.id, key)));
      Set<String> ids = new HashSet<>();
      for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS).id);
      assertThat(ids).hasSize(1);
      String runId = ids.iterator().next();
      for (int i = 0; i < 5; i++) workflows.reconcile(runId);
      assertThat(workflows.run(runId).steps.get("a").attempts).hasSize(1);
      assertThat(runtime.tasks().stream().filter(t -> runId.equals(t.workflowRunId))).hasSize(1);
      workflows.cancel(runId);
    }
  }

  @Test
  void cancellationStopsRunningProcessAndPreventsPendingSteps() throws Exception {
    var run =
        start(
            project(),
            node("a", List.of(), "sleep 30", "true"),
            node("b", List.of("a"), "true", "true"));
    Task t = task(run, "a");
    runtime.approve(t.id);
    awaitTask(t.id, Status.RUNNING);
    long until = System.currentTimeMillis() + 5000;
    while (runtime.task(t.id).processId == null && System.currentTimeMillis() < until)
      Thread.sleep(25);
    Long pid = runtime.task(t.id).processId;
    assertThat(pid).isNotNull();
    workflows.cancel(run.id);
    assertThat(runtime.task(t.id).status).isEqualTo(Status.CANCELLED);
    assertThat(workflows.run(run.id).steps.get("b").attempts).isEmpty();
    assertThat(workflows.run(run.id).status).isEqualTo("CANCELLED");
    until = System.currentTimeMillis() + 5000;
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
        && System.currentTimeMillis() < until) Thread.sleep(25);
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    assertThatThrownBy(() -> workflows.retry(run.id, "a", t.id, ""))
        .hasMessageContaining("종료된 Workflow");
  }

  @Test
  void changedDependencyPatchFailsBeforeAgentAndConflictingBranchesDoNotOverwrite()
      throws Exception {
    var run =
        start(
            project(),
            node("a", List.of(), "printf A > a.txt", "true"),
            node("b", List.of("a"), "printf ran > agent-ran.txt", "true"));
    Task a = finish(run, "a");
    Task b = task(run, "b");
    Files.writeString(runtime.artifacts(a.id).resolve("step.patch"), "tampered");
    runtime.approve(b.id);
    var failed = awaitTask(b.id, Status.FAILED);
    assertThat(failed.message).contains("해시");
    assertThat(Files.exists(Path.of(b.worktree).resolve("agent-ran.txt"))).isFalse();
    var conflict =
        start(
            project(),
            node("left", List.of(), "printf left > README.md", "true"),
            node("right", List.of(), "printf right > README.md", "true"),
            node("join", List.of("left", "right"), "printf ran > agent-ran.txt", "true"));
    finish(conflict, "left");
    finish(conflict, "right");
    Task join = task(conflict, "join");
    runtime.approve(join.id);
    assertThat(awaitTask(join.id, Status.FAILED).message).contains("선행 변경 충돌");
    assertThat(Files.exists(Path.of(join.worktree).resolve("agent-ran.txt"))).isFalse();
  }

  @Test
  void interruptedRunRecoversAsFailedAndManualRetryKeepsAttempt() throws Exception {
    var run = start(project(), node("a", List.of(), "true", "true"));
    Task first = task(run, "a");
    first.status = Status.ANALYZING;
    store.save("task", first.id, first, "test.interrupted", first.id, Map.of());
    runtime.recover();
    workflows.reconcile(run.id);
    assertThat(runtime.task(first.id).status).isEqualTo(Status.FAILED);
    assertThat(workflows.run(run.id).status).isEqualTo("FAILED");
    workflows.retry(run.id, "a", first.id, "");
    assertThat(task(run, "a").workflowAttempt).isEqualTo(2);
    finish(run, "a");
    assertThat(workflows.run(run.id).status).isEqualTo("SUCCEEDED");
  }
}
