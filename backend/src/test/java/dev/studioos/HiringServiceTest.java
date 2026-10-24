package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class HiringServiceTest {
  @TempDir Path temp;
  RuntimeService runtime;
  ExpertiseService expertise;
  ExpertiseController assessments;
  CapabilityService skills;
  VerificationSandbox sandbox;
  Store store;
  HiringService service;
  final ObjectMapper json = new ObjectMapper();
  final Map<String, String> saved = new HashMap<>();
  final Map<String, ExpertiseService.Assessment> qualifications = new HashMap<>();
  final Map<String, Task> tasks = new HashMap<>();
  Agent implementation = new Agent("implement", "Implementation", "Candidate", "codex"),
      reviewer = new Agent("review", "Review", "Candidate", "claude");

  @BeforeEach
  void setup() throws Exception {
    runtime = mock(RuntimeService.class);
    expertise = mock(ExpertiseService.class);
    assessments = mock(ExpertiseController.class);
    skills = mock(CapabilityService.class);
    sandbox = mock(VerificationSandbox.class);
    store = mock(Store.class);
    ReflectionTestUtils.setField(runtime, "data", temp);
    when(store.all(eq("hiring"), eq(HiringService.Job.class)))
        .thenAnswer(
            i ->
                saved.values().stream()
                    .map(
                        v -> {
                          try {
                            return json.readValue(v, HiringService.Job.class);
                          } catch (Exception e) {
                            throw new RuntimeException(e);
                          }
                        })
                    .toList());
    when(store.get(eq("hiring"), anyString(), eq(HiringService.Job.class)))
        .thenAnswer(i -> json.readValue(saved.get(i.getArgument(1)), HiringService.Job.class));
    doAnswer(
            i -> {
              saved.put(i.getArgument(1), json.writeValueAsString(i.getArgument(2)));
              return null;
            })
        .when(store)
        .save(eq("hiring"), anyString(), any(), anyString(), isNull(), anyMap());
    when(runtime.project(anyString()))
        .thenReturn(new Project("p", "Project", temp.toString(), "", List.of("true"), now()));
    when(runtime.agents()).thenReturn(List.of(implementation, reviewer));
    when(runtime.task(anyString())).thenAnswer(i -> tasks.get(i.getArgument(0)));
    when(expertise.get(anyString())).thenAnswer(i -> qualifications.get(i.getArgument(0)));
    when(expertise.eligible(any(), any(), anyString()))
        .thenAnswer(
            i ->
                ((Agent) i.getArgument(1))
                    .id()
                    .equals(i.getArgument(2).equals("IMPLEMENT") ? "implement" : "review"));
    when(sandbox.command(any(), anyList())).thenReturn(List.of("/bin/sh", "-c", "exit 1"));
    var skill = new CapabilityService.Skill();
    skill.id = "skill";
    skill.name = "fixture";
    skill.source = "fixture";
    when(skills.get("skill")).thenReturn(skill);
    when(assessments.create(eq("p"), any()))
        .thenAnswer(
            i -> {
              var c = (ExpertiseController.Candidate) i.getArgument(1);
              var a = new ExpertiseService.Assessment();
              a.id = id();
              a.taskId = id();
              a.scope = c.scope();
              a.purpose = c.purpose();
              a.projectId = "p";
              a.agentId = c.agentId();
              qualifications.put(a.id, a);
              var t = new Task();
              t.id = a.taskId;
              t.projectId = "assessment";
              t.status = Status.WAITING_APPROVAL;
              tasks.put(t.id, t);
              return Map.of("assessment", a, "task", t);
            });
    when(assessments.confirm(eq("p"), anyString(), any()))
        .thenAnswer(
            i -> {
              var a = qualifications.get(i.getArgument(1));
              a.status = "QUALIFIED";
              return a;
            });
    service =
        new HiringService(
            runtime,
            expertise,
            assessments,
            skills,
            mock(ModelPlanner.class),
            sandbox,
            mock(ExecutionWorker.class),
            store,
            json);
  }

  @AfterEach
  void close() {
    service.close();
  }

  HiringService.Candidate candidate(String purpose) {
    return new HiringService.Candidate(
        "README editing",
        purpose,
        purpose.equals("REVIEW") ? "claude" : "codex",
        "skill",
        "",
        "",
        "",
        "Existing verified source candidate",
        "Evaluate the exact task in isolation with failure cases.",
        "exit 1");
  }

  HiringService.Design design() {
    return new HiringService.Design(
        List.of("README editing"),
        "Only the delegated task scope",
        List.of(candidate("IMPLEMENT"), candidate("REVIEW")),
        "");
  }

  @Test
  void incompleteOrDuplicateAssessmentCoverageIsRejected() {
    assertThatCode(() -> HiringService.validate(design())).doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                HiringService.validate(
                    new HiringService.Design(
                        List.of("README editing"), "reason", List.of(candidate("IMPLEMENT")), "")))
        .hasMessageContaining("독립 검토");
    assertThatThrownBy(
            () ->
                HiringService.validate(
                    new HiringService.Design(
                        List.of("README editing"),
                        "reason",
                        List.of(candidate("IMPLEMENT"), candidate("IMPLEMENT")),
                        "")))
        .hasMessageContaining("후보");
  }

  @Test
  void repeatedDelegationIsIdempotentAndCancelledJobsNeverAdvance() throws Exception {
    var j = service.request("c", "key", "p", "request");
    assertThat(service.request("c", "key", "p", "request").id).isEqualTo(j.id);
    service.cancel(j.id);
    service.advance(j);
    assertThat(service.get(j.id).state).isEqualTo("CANCELLED");
    verifyNoInteractions(assessments);
  }

  @Test
  void preparesAndStartsEvaluationsWithoutUserConfigurationThenResumesOnlyAfterEvidence()
      throws Exception {
    var j = service.request("c", "key", "p", "request");
    service.installPlan(j, design(), Map.of("id:skill", "fixture"));
    assertThat(service.get(j.id).state).isEqualTo("EVALUATING");
    verify(runtime, times(2)).approve(anyString());
    service.advance(j);
    assertThat(service.ready(j.id)).isFalse();
    verify(assessments, never()).confirm(anyString(), anyString(), any());
    for (var t : tasks.values()) {
      t.startedAt = now();
      t.verification = Verification.PASSED;
      t.reviewStatus = "PASSED";
      t.review =
          new ReviewService.Result("PASS", "Assertions and rejection cases inspected", List.of());
    }
    service.advance(j);
    assertThat(service.ready(j.id)).isTrue();
    verify(assessments, times(2)).confirm(eq("p"), anyString(), any());
    assertThat(qualifications.values())
        .allMatch(a -> a.confirmationMode.equals("AUTOMATED_ASSESSMENT"));
  }

  @Test
  void alwaysPassingBaselineNeverStartsCandidate() throws Exception {
    when(sandbox.command(any(), anyList())).thenReturn(List.of("/bin/sh", "-c", "exit 0"));
    var j = service.request("c", "key", "p", "request");
    assertThatThrownBy(() -> service.installPlan(j, design(), Map.of("id:skill", "fixture")))
        .hasMessageContaining("빈 과제");
    verify(runtime, never()).approve(anyString());
  }

  @Test
  void failedReviewNeverBecomesQualification() throws Exception {
    var j = service.request("c", "key", "p", "request");
    service.installPlan(j, design(), Map.of("id:skill", "fixture"));
    for (var t : tasks.values()) {
      t.startedAt = now();
      t.verification = Verification.PASSED;
      t.reviewStatus = "CHANGES_REQUIRED";
    }
    assertThatThrownBy(() -> service.advance(j)).hasMessageContaining("독립 리뷰");
    verify(assessments, never()).confirm(anyString(), anyString(), any());
    assertThat(service.ready(j.id)).isFalse();
  }
}
