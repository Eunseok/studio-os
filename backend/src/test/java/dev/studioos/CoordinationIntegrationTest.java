package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class CoordinationIntegrationTest {
  static Path data;

  static {
    try {
      data = Files.createTempDirectory("coordination-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("studio.data", data::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:coordination;DB_CLOSE_DELAY=-1");
  }

  @Autowired RuntimeService runtime;
  @Autowired WorkflowService workflows;
  @Autowired ProjectLibrary library;
  @Autowired Store store;
  @Autowired GitWorkspaces git;
  @MockitoBean TaskPlanningWorker planning;
  @MockitoBean ChiefService chief;
  @TempDir Path repo;

  Project project() throws Exception {
    Files.writeString(repo.resolve("README.md"), "# Fixture\n");
    Processes.quick(repo, "git", "init");
    Processes.quick(repo, "git", "add", ".");
    Processes.quick(
        repo,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "Fixture");
    return runtime.addProject(
        "Ordinary project",
        repo.toString(),
        "Preserve source",
        List.of("python3", "-c", "print('ok')"));
  }

  ModelPlanner.Decision decision(Project p) {
    return new ModelPlanner.Decision(
        id(),
        now(),
        p.id(),
        "test",
        List.of(),
        new ModelPlanner.Choice(
            "codex", "gpt-6.1-sol", "medium", "reason", "medium", List.of("evidence")),
        "codex",
        "gpt-6.1-sol");
  }

  void persist(Task t) {
    store.save("task", t.id, t, "test.fixture", t.id, Map.of());
  }

  @Test
  void autoCannotExecuteBeforeChoiceAndLateChoiceCannotReplaceManualOverrideOrCancel()
      throws Exception {
    Project p = project();
    Task t = runtime.create(p.id(), "auto", "small change", "Update README", 120);
    assertThat(t.routingPending).isTrue();
    assertThat(t.requireReview).isTrue();
    assertThatThrownBy(() -> runtime.approve(t.id)).isInstanceOf(IllegalArgumentException.class);
    runtime.manualModel(t.id, "codex", "gpt-6.1-sol", "low");
    runtime.routingComplete(t.id, decision(p), null);
    assertThat(runtime.task(t.id).reasoning).isEqualTo("low");
    assertThat(runtime.task(t.id).autoRouting).isFalse();
    Task cancelled = runtime.create(p.id(), "auto", "cancel", "Update README", 120);
    runtime.cancel(cancelled.id);
    runtime.routingComplete(cancelled.id, decision(p), null);
    assertThat(runtime.task(cancelled.id).status).isEqualTo(Status.CANCELLED);
  }

  @Test
  void adaptiveRetriesAreIdempotentBoundedAndPreserveEnvironmentModel() throws Exception {
    Project p = project();
    Task old = runtime.create(p.id(), runtime.autoAgent().id(), "change", "Update README", 120);
    old.status = Status.FAILED;
    old.failure = new FailurePolicy.Assessment("TEST", true, "assertion failed");
    persist(old);
    Task retry = runtime.retryTask(old.id, "fix acceptance");
    assertThat(retry.routingPending).isTrue();
    assertThat(runtime.retryTask(old.id, "same").id).isEqualTo(retry.id);
    retry.status = Status.FAILED;
    retry.routingPending = false;
    retry.model = old.model;
    retry.reasoning = old.reasoning;
    retry.failure = new FailurePolicy.Assessment("ENVIRONMENT", false, "permission denied");
    persist(retry);
    Task third = runtime.retryTask(retry.id, "environment restored");
    assertThat(third.routingPending).isFalse();
    assertThat(third.model).isEqualTo(old.model);
    assertThat(third.attempt).isEqualTo(3);
    third.status = Status.FAILED;
    persist(third);
    assertThatThrownBy(() -> runtime.retryTask(third.id, "")).hasMessageContaining("3회");
  }

  @Test
  void sharedDocumentsAreVersionedVisibleAndFrozenForAgents() throws Exception {
    Project p = project();
    var d = library.save(p.id(), "brief", "Overview", "Goal one", 0, "User");
    Task t = runtime.create(p.id(), "demo-developer", "read context", "Read project goal", 60);
    assertThat(t.sharedContext).contains("Goal one");
    assertThat(t.sharedDocuments).contains(new DocumentRef(d.id(), "Overview", 1));
    library.save(p.id(), "brief", "Overview", "Goal two", 1, "User");
    assertThat(runtime.task(t.id).sharedContext).contains("Goal one").doesNotContain("Goal two");
    assertThatThrownBy(() -> library.save(p.id(), "brief", "Overview", "stale", 1, "User"))
        .hasMessageContaining("변경");
    library.archive(p.id(), true);
    assertThat(((List<?>) runtime.snapshot().get("projects")).contains(p)).isFalse();
    assertThat(runtime.project(p.id())).isEqualTo(p);
    assertThat(Files.exists(repo.resolve("README.md"))).isTrue();
    library.archive(p.id(), false);
    assertThat(((List<?>) runtime.snapshot().get("projects")).contains(p)).isTrue();
  }

  @Test
  void workflowAutoAndReviewGateProtectsFinalApprovalIncludingChangedContent() throws Exception {
    Project p = project();
    var node = new WorkflowDomain.Node();
    node.id = "first";
    node.title = "Change";
    node.prompt = "Update README";
    node.agentId = "auto";
    var def = workflows.saveDefinition(null, "Automatic", p.id(), List.of(node), null);
    var run = workflows.start(def.id, "request-123456");
    Task t = runtime.task(run.steps.get("first").attempts.getFirst());
    assertThat(t.routingPending).isTrue();
    assertThat(t.requireReview).isTrue();
    runtime.routingComplete(t.id, decision(p), null);
    t = runtime.task(t.id);
    git.create(p, t);
    Files.createDirectories(runtime.artifacts(t.id));
    t.inputTree = git.applyInputs(t, runtime.data.resolve("artifacts"), runtime.artifacts(t.id));
    Files.writeString(Path.of(t.worktree).resolve("README.md"), "# Changed\n");
    Files.createDirectories(runtime.artifacts(t.id));
    git.artifacts(t, runtime.artifacts(t.id));
    t.status = Status.WAITING_APPROVAL;
    t.startedAt = now();
    t.agentExitCode = 0;
    t.verification = Verification.PASSED;
    persist(t);
    String taskId = t.id;
    assertThatThrownBy(() -> runtime.approve(taskId)).hasMessageContaining("독립 리뷰");
    t.review = new ReviewService.Result("PASS", "Verified", List.of());
    t.reviewStatus = "PASSED";
    t.reviewDigest = GitWorkspaces.patchDigest(runtime.artifacts(t.id).resolve("changes.patch"));
    persist(t);
    Files.writeString(Path.of(t.worktree).resolve("README.md"), "# Tampered\n");
    assertThatThrownBy(() -> runtime.approve(taskId)).hasMessageContaining("무결성");
    Files.writeString(Path.of(t.worktree).resolve("README.md"), "# Changed\n");
    runtime.approve(taskId);
    workflows.reconcile(run.id);
    Task applied = runtime.applyToProject(taskId);
    assertThat(applied.appliedCommit).isEqualTo(git.head(p));
    assertThat(Files.readString(repo.resolve("README.md"))).isEqualTo("# Changed\n");
    assertThat(runtime.applyToProject(taskId).appliedCommit).isEqualTo(applied.appliedCommit);
  }

  @Test
  void failureEvidenceAndReviewSchemaDoNotConfuseEnvironmentWithCode() throws Exception {
    Task t = new Task();
    t.message = "Test failure";
    t.agentExitCode = 0;
    t.testExitCode = 1;
    Files.writeString(repo.resolve("tests.log"), "Permission denied");
    assertThat(FailurePolicy.assess(t, repo).category()).isEqualTo("ENVIRONMENT");
    Files.writeString(repo.resolve("tests.log"), "AssertionError: expected Quick Start");
    assertThat(FailurePolicy.assess(t, repo).adapt()).isTrue();
    t.reviewStatus = "CHANGES_REQUIRED";
    t.testExitCode = 0;
    assertThat(FailurePolicy.assess(t, repo).category()).isEqualTo("REVIEW");
    Path output = repo.resolve("review.json");
    Files.writeString(
        output,
        "{\"verdict\":\"PASS\",\"summary\":\"ok\",\"findings\":[{\"severity\":\"blocking\",\"file\":\"README\",\"detail\":\"missing"
            + " requirement\"}]}");
    assertThatThrownBy(() -> ReviewService.read(output)).hasMessageContaining("불일치");
  }
}
