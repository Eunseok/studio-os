package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;

class ChiefSteeringTest {
  final ObjectMapper json = new ObjectMapper();
  final Store store = mock(Store.class);
  final RuntimeService runtime = mock(RuntimeService.class);
  final WorkflowService workflows = mock(WorkflowService.class);
  final ProjectLibrary library = mock(ProjectLibrary.class);
  final Map<String, String> saved = new HashMap<>();
  ChiefService chief;
  Task task;

  @BeforeEach void setup() throws Exception {
    when(library.archived()).thenReturn(Set.of());
    when(store.get(eq("conversation"), anyString(), eq(ChiefService.Conversation.class)))
        .thenAnswer(i -> json.readValue(saved.get(i.getArgument(1)), ChiefService.Conversation.class));
    when(store.all("conversation", ChiefService.Conversation.class)).thenAnswer(i -> {
      List<ChiefService.Conversation> result = new ArrayList<>();
      for (String value : saved.values()) result.add(json.readValue(value, ChiefService.Conversation.class));
      return result;
    });
    doAnswer(i -> { saved.put(i.getArgument(1), json.writeValueAsString(i.getArgument(2))); return null; })
        .when(store).save(eq("conversation"), anyString(), any(), anyString(), isNull(), anyMap());
    chief = new ChiefService(runtime, workflows, mock(ModelPlanner.class), library, store, json);
    task = new Task(); task.id = "task"; task.prompt = "승인된 범위";
    task.status = Status.RUNNING;
    when(runtime.task(task.id)).thenReturn(task);
    when(workflows.pauseForSteering(eq("run"), anyInt())).thenReturn(task);
    when(workflows.resumeSteering(eq("run"), eq(task.id), anyString())).thenAnswer(i -> {
      if (!task.steeringStopped) throw new IllegalArgumentException("기존 호출 종료 대기");
      task.steeringRevision++;
      return task;
    });
    var c = new ChiefService.Conversation();
    c.id = "conversation"; c.projectId = "project"; c.state = "RUNNING";
    c.updatedAt = now(); c.requestKey = "initial-request"; c.runId = "run"; c.delegated = true;
    saved.put(c.id, json.writeValueAsString(c));
  }

  @AfterEach void close() { chief.close(); }

  @Test void receivesDuringExecutionAndWaitsForApprovalBeforeApplying() throws Exception {
    var c = chief.send("conversation", "project", "방향을 바꾸고 기능도 추가해", "steer-request");
    assertThat(c.state).isEqualTo("STEERING");
    assertThat(c.instructions).hasSize(1);
    assertThat(c.instructions.getFirst().status).isEqualTo("RECEIVED");
    assertThat(chief.send(c.id, c.projectId, "방향을 바꾸고 기능도 추가해", "steer-request").instructions).hasSize(1);
    chief.tick();
    verify(workflows, never()).resumeSteering(any(), any(), any());
    assertThatThrownBy(() -> chief.approveInstruction(c.id, "stale-request")).hasMessageContaining("변경");
    assertThatThrownBy(() -> chief.approveInstruction(c.id, "steer-request")).hasMessageContaining("종료 대기");
    task.steeringStopped = true;
    chief.tick();
    assertThat(chief.get(c.id).instructions.getFirst().status).isEqualTo("WAITING_APPROVAL");
    var resumed = chief.approveInstruction(c.id, "steer-request");
    assertThat(resumed.instructions.getFirst().status).isEqualTo("PENDING");
    assertThat(resumed.runId).isEqualTo("run");
    assertThat(resumed.state).isEqualTo("RUNNING");
    assertThat(chief.approveInstruction(c.id, "steer-request").instructions).hasSize(1);
    verify(workflows, times(2)).resumeSteering("run", task.id, "\n방향을 바꾸고 기능도 추가해");
  }

  @Test void laterInstructionInvalidatesOlderApprovalAndKeepsArrivalOrder() throws Exception {
    var c = chief.send("conversation", "project", "첫 지시", "request-first");
    chief.send(c.id, c.projectId, "둘째 지시", "request-second");
    task.steeringStopped = true;
    chief.tick();
    assertThatThrownBy(() -> chief.approveInstruction(c.id, "request-first")).hasMessageContaining("변경");
    chief.approveInstruction(c.id, "request-second");
    verify(workflows).resumeSteering("run", task.id, "\n첫 지시\n둘째 지시");
    assertThat(chief.get(c.id).instructions).extracting(i -> i.status).containsOnly("PENDING");
  }

  @Test void thinkingCanBeRedirectedWithoutStartingImplementation() throws Exception {
    var c = chief.get("conversation");
    c.runId = null; c.state = "THINKING"; c.delegated = false;
    saved.put(c.id, json.writeValueAsString(c));
    var updated = chief.send(c.id, c.projectId, "새 방향으로 계획해", "request-redirect");
    assertThat(updated.state).isEqualTo("THINKING");
    assertThat(updated.requestKey).isEqualTo("request-redirect");
    assertThat(updated.instructions.getFirst().status).isEqualTo("PENDING");
    verifyNoInteractions(workflows);
  }

  @Test void currentProviderInvocationsHaveNoDuplexInputChannel() {
    task.worktree = ".";
    var codex = new CodexAdapter("codex");
    var claude = new ClaudeAdapter("claude");
    assertThat(codex.steeringCapability(codex.command(task, java.nio.file.Path.of("."))))
        .contains("Codex exec", "미지원");
    assertThat(claude.steeringCapability(claude.command(task, java.nio.file.Path.of("."))))
        .contains("Claude --print", "미지원");
  }
}
