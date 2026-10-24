package dev.studioos;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChiefQuestionsTest {
  private final ObjectMapper json = new ObjectMapper();
  private final RuntimeService runtime = mock(RuntimeService.class);
  private final WorkflowService workflows = mock(WorkflowService.class);
  private final Store store = mock(Store.class);
  private final ChiefService chief = new ChiefService(runtime, workflows, null, null, store, json);

  private ChiefService.Conversation conversation(String questions) throws Exception {
    var c = new ChiefService.Conversation();
    c.id = "conversation";
    c.projectId = "project";
    c.state = "DISCUSSING";
    c.plan = json.readValue("{\"response\":\"계획\",\"questions\":" + questions
        + ",\"steps\":[{\"title\":\"구현\",\"instruction\":\"기능 구현\"}]}", ChiefService.Plan.class);
    ChiefService.validate(c.plan);
    when(store.get("conversation", c.id, ChiefService.Conversation.class)).thenReturn(c);
    return c;
  }

  @Test
  void legacyStringsAndMessagesRemainReadableAndRoundTrip() throws Exception {
    var c = conversation("[\"기존 질문?\",{\"id\":\"scope\",\"text\":\"범위?\",\"options\":[\"작게\",\"크게\"],\"required\":false}]");
    assertThat(c.plan.questions().getFirst()).isEqualTo(
        new ChiefService.Question("legacy-question-1", "기존 질문?", List.of(), true));
    assertThat(json.readValue(json.writeValueAsString(c), ChiefService.Conversation.class).plan)
        .isEqualTo(c.plan);
    var message = json.readValue("{\"role\":\"user\",\"text\":\"예전 대화\",\"at\":\"time\"}", ChiefService.Message.class);
    assertThat(message.questionId()).isNull();
    assertThat(message.execution()).isNull();
  }

  @Test
  void selectionAndCustomAnswersAreLinkedPersistedAndGateEveryStartPath() throws Exception {
    var c = conversation("[{\"id\":\"scope\",\"text\":\"범위?\",\"options\":[\"작게\",\"크게\"],\"required\":true},\"추가 조건?\"]");
    // Even a stale/forged ready state cannot bypass required answers.
    c.state = "PLAN_READY";
    assertThatThrownBy(() -> chief.start(c.id)).hasMessageContaining("필수 질문");
    assertThatThrownBy(() -> chief.send(c.id, c.projectId, "진행해", "request-key"))
        .hasMessageContaining("필수 질문");
    chief.answer(c.id, "scope", "작게");
    assertThat(c.state).isEqualTo("DISCUSSING");
    assertThatThrownBy(() -> chief.start(c.id)).hasMessageContaining("필수 질문");
    chief.answer(c.id, "legacy-question-2", "직접 쓴 조건");
    assertThat(c.state).isEqualTo("PLAN_READY");
    assertThat(c.messages).extracting(ChiefService.Message::questionId)
        .containsExactly("scope", "legacy-question-2");
    assertThat(c.messages.getLast().text()).contains("추가 조건?", "직접 쓴 조건");
    var restored = json.readValue(json.writeValueAsString(c), ChiefService.Conversation.class);
    assertThat(restored.answers).containsEntry("scope", "작게").containsEntry("legacy-question-2", "직접 쓴 조건");
    assertThat(restored.messages).isEqualTo(c.messages);
    verify(store, times(2)).save(eq("conversation"), eq(c.id), eq(c), eq("chief.updated"), isNull(), anyMap());
    verifyNoInteractions(workflows);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void optionalQuestionsDoNotBlockAndRecordedAnswersReachImplementation() throws Exception {
    var c = conversation("[{\"id\":\"scope\",\"text\":\"범위?\",\"options\":[\"작게\"],\"required\":true},{\"id\":\"extra\",\"text\":\"추가?\",\"options\":[],\"required\":false}]");
    chief.answer(c.id, "scope", "선택지 외 직접 입력");
    var definition = new WorkflowDomain.Definition();
    definition.id = "workflow";
    var run = new WorkflowDomain.Run();
    run.id = "run";
    when(runtime.project(c.projectId)).thenReturn(new Domain.Project(c.projectId, "project", ".", "", List.of("test"), "time"));
    when(workflows.saveDefinition(isNull(), anyString(), eq(c.projectId), anyList(), isNull())).thenReturn(definition);
    when(workflows.start(definition.id, "chief-" + c.id + "-0")).thenReturn(run);
    chief.start(c.id);
    assertThat(c.state).isEqualTo("RUNNING");
    ArgumentCaptor<List<WorkflowDomain.Node>> nodes = ArgumentCaptor.forClass((Class) List.class);
    verify(workflows).saveDefinition(isNull(), anyString(), eq(c.projectId), nodes.capture(), isNull());
    assertThat(nodes.getValue().getFirst().prompt).contains("기능 구현", "범위?", "선택지 외 직접 입력");
    assertThatThrownBy(() -> chief.answer(c.id, "scope", "바꾸기")).hasMessageContaining("답변할 수");
  }

  @Test
  void rejectsInvalidAnswersWithoutRecordingAndDeduplicatesRetries() throws Exception {
    var c = conversation("[\"질문?\"]");
    assertThatThrownBy(() -> chief.answer(c.id, "unknown", "답변")).hasMessageContaining("찾을 수");
    assertThatThrownBy(() -> chief.answer(c.id, "legacy-question-1", "  ")).hasMessageContaining("답변을 입력");
    assertThatThrownBy(() -> chief.answer(c.id, "legacy-question-1", "x".repeat(1001))).hasMessageContaining("1000");
    assertThat(c.messages).isEmpty();
    assertThat(c.answers).isEmpty();
    chief.answer(c.id, "legacy-question-1", "답변");
    chief.answer(c.id, "legacy-question-1", "답변");
    assertThat(c.messages).hasSize(1);
    chief.answer(c.id, "legacy-question-1", "수정 답변");
    assertThat(c.answers).containsEntry("legacy-question-1", "수정 답변");
    assertThat(c.messages).hasSize(2);
  }

  @Test
  void rejectsDuplicateIdsAndMalformedOptions() throws Exception {
    var q = new ChiefService.Question("same", "질문", List.of("선택"), true);
    assertThatThrownBy(() -> ChiefService.validate(new ChiefService.Plan("응답", List.of(q, q), List.of())))
        .hasMessageContaining("질문 규격");
    assertThatThrownBy(() -> ChiefService.validate(new ChiefService.Plan("응답", List.of(
        new ChiefService.Question("q", "질문", List.of("중복", "중복"), true)), List.of())))
        .hasMessageContaining("선택지 규격");
    assertThatThrownBy(() -> json.readValue("{\"id\":\"q\",\"text\":\"질문\",\"options\":[3]}", ChiefService.Question.class))
        .hasMessageContaining("선택지 규격");
  }
}
