package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;

class ChiefServiceTest {
  RuntimeService runtime = mock(RuntimeService.class);
  WorkflowService workflows = mock(WorkflowService.class);
  ProjectLibrary library = mock(ProjectLibrary.class);
  Store store = mock(Store.class);
  CapabilityService capabilities = mock(CapabilityService.class);
  ChiefService chief;
  ChiefService.Conversation c;
  WorkflowDomain.Run run;
  Task task;

  @BeforeEach
  void setup() {
    chief = new ChiefService(runtime, workflows, mock(ModelPlanner.class), library,
        capabilities, store, new ObjectMapper());
    c = new ChiefService.Conversation();
    c.id = "conversation";
    c.projectId = "project";
    c.state = "RUNNING";
    c.runId = "run";
    c.delegated = true;
    c.plan = new ChiefService.Plan("화면 개선", List.of(), List.of(
        new ChiefService.PlannedStep("구현", "기존 동작 보존", List.of("ui-guide"), "화면 규칙 적용")));
    run = new WorkflowDomain.Run();
    run.id = "run";
    run.definition = new WorkflowDomain.Definition();
    run.definition.id = "definition";
    var node = new WorkflowDomain.Node();
    node.id = "step-1";
    run.definition.nodes = List.of(node);
    var step = new WorkflowDomain.Step();
    step.nodeId = node.id;
    step.attempts.add("task");
    run.steps.put(node.id, step);
    task = new Task();
    task.id = "task";
    task.title = "구현";
    task.status = Status.WAITING_APPROVAL;
    when(store.get("conversation", c.id, ChiefService.Conversation.class)).thenReturn(c);
    when(store.encode(any())).thenReturn("approved scope");
    when(workflows.run("run")).thenReturn(run);
    when(workflows.reconcile("run")).thenReturn(run);
    when(runtime.task("task")).thenReturn(task);
    when(workflows.pauseForSteering(eq("run"),anyInt())).thenReturn(task);
  }

  @AfterEach
  void close() { chief.close(); }

  @Test
  void feedbackIsDurableIdempotentAndHoldsExecutionRetriesAndApproval() throws Exception {
    chief.send(c.id, c.projectId, "버튼을 더 크게", "feedback-0001");
    chief.send(c.id, c.projectId, "색상도 유지", "feedback-0002");
    chief.send(c.id, c.projectId, "버튼을 더 크게", "feedback-0001");
    assertThat(c.feedbackPending).isTrue();
    assertThat(c.pendingFeedback).containsExactly("버튼을 더 크게", "색상도 유지");
    assertThat(c.messages.stream().filter(m -> m.role().equals("user"))).hasSize(2);
    chief.advance(c.id);
    verify(runtime, never()).approve(anyString());
    task.status = Status.FAILED;
    task.failure = new FailurePolicy.Assessment("TEST", true, "assertion");
    chief.advance(c.id);
    verify(workflows, never()).retry(anyString(), anyString(), anyString(), anyString());
    c.state = "WAITING_RESULT";
    assertThatThrownBy(() -> chief.approve(c.id)).hasMessageContaining("의견");
    verify(store, atLeastOnce()).save(eq("conversation"), eq(c.id), eq(c), anyString(), isNull(), anyMap());
  }

  @Test
  void replanPreservesOldRunAndAllFeedbackAndRequiresAnotherScopeApproval() throws Exception {
    for (int i = 0; i < 8; i++) chief.send(c.id, c.projectId, "의견 " + i, "feedback-000" + i);
    chief.replan(c.id);
    verify(workflows).cancel("run");
    assertThat(c.previousRunIds).containsExactly("run");
    assertThat(c.runId).isNull();
    assertThat(c.state).isEqualTo("THINKING");
    assertThat(c.plan).isNull();
    assertThat(c.feedbackPending).isFalse();
    assertThat(c.planningContext).contains("approved scope", "의견 0", "의견 7");
    assertThatThrownBy(() -> chief.start(c.id)).hasMessageContaining("실행 범위");
    assertThatThrownBy(() -> chief.replan(c.id)).hasMessageContaining("먼저");
  }

  @Test
  void approvedScopeCarriesSkillReasonAndUsesANewRunKeyAfterReplanning() throws Exception {
    c.state = "PLAN_READY";
    c.runId = null;
    c.previousRunIds.add("old-run");
    var project = new Project(c.projectId, "Fixture", "/fixture", "", List.of("verify"), now());
    when(runtime.project(c.projectId)).thenReturn(project);
    when(workflows.saveDefinition(isNull(), anyString(), eq(c.projectId), anyList(), isNull()))
        .thenAnswer(invocation -> {
          List<WorkflowDomain.Node> nodes = invocation.getArgument(3);
          assertThat(nodes.getFirst().requiredSkills).containsExactly("ui-guide");
          assertThat(nodes.getFirst().prompt).contains("화면 규칙 적용");
          assertThat(nodes.getFirst().requireReview).isTrue();
          assertThat(nodes.getFirst().requireVerification).isTrue();
          return run.definition;
        });
    when(workflows.start(eq("definition"), eq("chief-conversation-1"))).thenReturn(run);
    chief.start(c.id);
    assertThat(c.state).isEqualTo("RUNNING");
    assertThat(c.runId).isEqualTo("run");
  }

  @Test
  void implementationAloneCannotReachResultButPassedVerificationAndReviewCan() throws Exception {
    task.startedAt = now();
    task.agentExitCode = 0;
    task.verification = Verification.PASSED;
    chief.advance(c.id);
    assertThat(c.state).isEqualTo("RUNNING");
    task.reviewStatus = "PASSED";
    task.review = new ReviewService.Result("PASS", "확인 완료", List.of());
    chief.advance(c.id);
    assertThat(c.state).isEqualTo("WAITING_RESULT");
    verify(runtime, never()).approve(anyString());
    when(runtime.approve("task")).thenAnswer(invocation -> { task.status = Status.SUCCEEDED; return task; });
    when(workflows.reconcile("run")).thenAnswer(invocation -> { run.status = "SUCCEEDED"; return run; });
    chief.approve(c.id);
    assertThat(c.state).isEqualTo("COMPLETE");
    assertThat(task.appliedCommit).isNull();
    verify(runtime, never()).applyToProject(anyString());
  }

  @Test
  void legacyPlansRemainReadableWithoutInventingSkillEvidence() throws Exception {
    var saved = new ObjectMapper().readValue(
        "{\"id\":\"old\",\"plan\":{\"response\":\"기존 계획\",\"questions\":[],\"steps\":[{\"title\":\"구현\",\"instruction\":\"기존 범위\"}]}}",
        ChiefService.Conversation.class);
    ChiefService.validate(saved.plan);
    assertThat(saved.plan.steps().getFirst().requiredSkills()).isEmpty();
    assertThat(saved.plan.steps().getFirst().skillReason()).isNull();
    assertThat(saved.feedbackKeys).isEmpty();
    assertThat(saved.previousRunIds).isEmpty();
    assertThat(saved.feedbackPending).isFalse();
  }

  @Test
  void failuresAndExternalCancellationDoNotAppearAsSuccess() {
    task.status = Status.FAILED;
    task.failure = new FailurePolicy.Assessment("ENVIRONMENT", false, "인증 실패");
    chief.advance(c.id);
    assertThat(c.state).isEqualTo("NEEDS_HELP");
    assertThat(c.messages.getLast().text()).contains("인증 실패");
    verify(workflows, never()).retry(anyString(), anyString(), anyString(), anyString());
    c.state = "RUNNING";
    task.status = Status.CANCELLED;
    chief.advance(c.id);
    assertThat(c.state).isEqualTo("NEEDS_HELP");
    assertThat(c.messages.getLast().text()).contains("취소");
    c.state = "RUNNING";
    run.status = "CANCELLED";
    chief.advance(c.id);
    assertThat(c.state).isEqualTo("STOPPED");
    assertThat(c.delegated).isFalse();
  }
}
