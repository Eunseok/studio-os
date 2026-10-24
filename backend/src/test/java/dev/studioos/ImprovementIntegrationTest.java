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

@SpringBootTest
class ImprovementIntegrationTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("improvement-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("studio.data", DATA::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:improvements;DB_CLOSE_DELAY=-1");
  }

  @Autowired RuntimeService runtime;
  @Autowired ImprovementService service;
  @Autowired WorkflowService workflows;
  @Autowired Store store;
  @TempDir Path temp;

  Project project() throws Exception {
    Files.writeString(temp.resolve("README.md"), "# Fixture\n");
    Processes.quick(temp, "git", "init");
    Processes.quick(temp, "git", "add", ".");
    Processes.quick(
        temp,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-m",
        "Fixture");
    return runtime.addProject(
        "Improvement test",
        temp.toString(),
        "Do not commit",
        List.of("python3", "-c", "print('ok')"));
  }

  ImprovementPayload.Proposal proposal() {
    return new ImprovementPayload.Proposal(
        "docs",
        "Improve README",
        "Missing usage",
        List.of("README.md contains title only"),
        "small",
        List.of(),
        List.of("README contains usage"),
        "Add Usage section to README",
        List.of());
  }

  void finishAnalysis(Task task) {
    // Fixture state: no external AI invocation in unit/integration regression tests.
    task.status = Status.SUCCEEDED;
    task.verification = Verification.PASSED;
    task.improvementProposals = List.of(proposal());
    store.save("task", task.id, task, "test.proposal", task.id, Map.of());
  }

  @Test
  void approvalGatesSelectionIdempotencyAndRouting() throws Exception {
    var p = project();
    var i = service.create(p.id(), "Improve README", "codex", "improve-key-001");
    var t = runtime.task(i.analysisTaskId);
    assertThat(t.improvementAnalysis).isTrue();
    assertThat(t.testCommand).isEmpty();
    assertThat(t.model).isNotBlank();
    assertThat(t.reasoning).isEqualTo("low");
    assertThat(service.create(p.id(), "Improve README", "codex", "improve-key-001").id)
        .isEqualTo(i.id);
    assertThatThrownBy(() -> service.select(i.id, "docs", "codex")).hasMessageContaining("승인");
    finishAnalysis(t);
    var selected = service.select(i.id, "docs", "codex");
    var run = workflows.run(selected.workflowRunId);
    assertThat(run.steps.get("implement").status).isEqualTo("WAITING_APPROVAL");
    var implement = runtime.task(run.steps.get("implement").attempts.getFirst());
    assertThat(implement.requireVerification).isTrue();
    assertThat(implement.testCommand).isEqualTo(p.testCommand());
    assertThat(implement.improvementAnalysis).isFalse();
    assertThat(service.select(i.id, "docs", "codex").workflowRunId).isEqualTo(run.id);
    assertThatThrownBy(() -> service.select(i.id, "other", "codex")).hasMessageContaining("다른 개선안");
    workflows.cancel(run.id);
  }

  @Test
  void dirtyRepositoryAndChangedBaseAreRejected() throws Exception {
    var p = project();
    Files.writeString(temp.resolve("new.txt"), "uncommitted");
    assertThatThrownBy(() -> service.create(p.id(), "Improve", "codex", "improve-key-002"))
        .hasMessageContaining("미커밋");
    Files.delete(temp.resolve("new.txt"));
    var i = service.create(p.id(), "Improve", "codex", "improve-key-002");
    finishAnalysis(runtime.task(i.analysisTaskId));
    Files.writeString(temp.resolve("README.md"), "# Changed\n");
    Processes.quick(temp, "git", "add", ".");
    Processes.quick(
        temp,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "Changed");
    assertThatThrownBy(() -> service.select(i.id, "docs", "codex")).hasMessageContaining("기준 커밋");
  }

  @Test
  void selectionRequiresVerificationAndAnalysisRetriesPreserveHistory() throws Exception {
    var p = project();
    var i = service.create(p.id(), "Improve", "codex", "improve-key-003");
    assertThatThrownBy(() -> service.retry(i.id, i.analysisTaskId, "codex"))
        .hasMessageContaining("실패");
    runtime.cancel(i.analysisTaskId);
    var next = service.retry(i.id, i.analysisTaskId, "codex");
    assertThat(next.analysisAttempts).hasSize(2);
    finishAnalysis(runtime.task(next.analysisTaskId));
    runtime.settings(p.id(), p.rules(), List.of());
    assertThatThrownBy(() -> service.select(i.id, "docs", "codex")).hasMessageContaining("검증 명령");
  }

  @Test
  void proposalValidationAndReadOnlyProviderCommands() throws Exception {
    Path file = temp.resolve("proposals.json");
    Files.writeString(file, store.encode(new ImprovementPayload.Result(List.of(proposal()))));
    assertThat(ImprovementPayload.read(file).proposals()).hasSize(1);
    Files.writeString(file, "{\"proposals\":[]}");
    assertThatThrownBy(() -> ImprovementPayload.read(file)).hasMessageContaining("1~3");
    Task t = new Task();
    t.improvementAnalysis = true;
    t.worktree = temp.toString();
    t.model = "gpt-6.1-sol";
    t.reasoning = "high";
    var codex = new CodexAdapter("codex").command(t, temp);
    assertThat(codex)
        .contains("read-only", "--output-schema", "--model", "model_reasoning_effort=\"high\"");
    t.model = "sonnet";
    var claude = new ClaudeAdapter("claude").command(t, temp);
    assertThat(claude)
        .contains("--restricted", "--safe-mode", "Read,Glob,Grep", "--json-schema")
        .doesNotContain("Bash", "--dangerously-skip-permissions");
  }

  @Test
  void manualModelOverridesOnlyAnUnapprovedPlan() throws Exception {
    var p = project();
    var idea = service.create(p.id(), "README 안내", "codex", "manual-model-key");
    var changed = runtime.manualModel(idea.analysisTaskId, "codex", "gpt-6.1-sol", "high");
    assertThat(changed.reasoning).isEqualTo("high");
    assertThat(changed.routingReason).contains("사용자");
    assertThatThrownBy(() -> runtime.manualModel(changed.id, "codex", "gpt-6.1-sol", "unsupported"))
        .hasMessageContaining("지원하지 않는");
    changed.startedAt = now();
    store.save("task", changed.id, changed, "test.started", changed.id, Map.of());
    assertThatThrownBy(() -> runtime.manualModel(changed.id, "codex", "gpt-6.1-sol", "low"))
        .hasMessageContaining("승인 전");
    runtime.cancel(changed.id);
  }
}
