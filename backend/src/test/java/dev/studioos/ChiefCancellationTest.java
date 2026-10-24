package dev.studioos;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ChiefCancellationTest {
  @TempDir Path temp;
  private final ObjectMapper json = new ObjectMapper();
  private final Store store = mock(Store.class);
  private final WorkflowService workflows = mock(WorkflowService.class);
  private final ModelPlanner models = mock(ModelPlanner.class);
  private final ProjectLibrary library = mock(ProjectLibrary.class);
  private final AgentAdapter adapter = mock(AgentAdapter.class);
  private final Map<String, String> saved = new ConcurrentHashMap<>();
  private ChiefService chief;
  private Domain.Project project;
  private final ModelPlanner.Decision decision = new ModelPlanner.Decision(
      "decision", "time", "project", "chief", List.of(),
      new ModelPlanner.Choice("codex", "model", "low", "reason", "high", List.of("evidence")),
      "codex", "model");

  @BeforeEach
  void setup() throws Exception {
    when(library.archived()).thenReturn(Set.of());
    when(library.context(anyString())).thenReturn("");
    when(store.get(eq("conversation"), anyString(), eq(ChiefService.Conversation.class)))
        .thenAnswer(i -> json.readValue(saved.get(i.getArgument(1)), ChiefService.Conversation.class));
    when(store.all("conversation", ChiefService.Conversation.class)).thenAnswer(i -> {
      List<ChiefService.Conversation> result = new ArrayList<>();
      for (String value : saved.values()) result.add(json.readValue(value, ChiefService.Conversation.class));
      return result;
    });
    doAnswer(i -> {
      saved.put(i.getArgument(1), json.writeValueAsString(i.getArgument(2)));
      return null;
    }).when(store).save(eq("conversation"), anyString(), any(), anyString(), isNull(), anyMap());
    project = new Domain.Project("project", "Project", temp.toString(), "", List.of("test"), "time");
    when(adapter.type()).thenReturn("codex");
    var runtime = new RuntimeService(store, library, null, null, null, null,
        List.of(adapter), temp.resolve("data").toString());
    when(store.get("project", project.id(), Domain.Project.class)).thenReturn(project);
    chief = new ChiefService(runtime, workflows, models, library, store, json);
    when(models.choose(any(), anyString(), anyString(), any(Consumer.class))).thenReturn(decision);
    clearInvocations(adapter);
  }

  @AfterEach
  void close() {
    if (chief != null) chief.close();
  }

  @Test
  void retryAndDelayedTransportReplayRemainIdempotentAcrossPersistence() throws Exception {
    var first = chief.send(null, project.id(), "원래 요청", "request-first");
    chief.stop(first.id, first.requestKey);
    var retried = chief.retry(first.id, first.requestKey, "request-retry");
    assertThat(retried.messages.stream().filter(m -> m.role().equals("user"))).hasSize(1);
    assertThat(chief.retry(first.id, first.requestKey, "request-retry").requestKey).isEqualTo("request-retry");
    assertThat(chief.send(null, project.id(), "원래 요청", "request-first").id).isEqualTo(first.id);
    assertThat(chief.send(first.id, project.id(), "원래 요청", "request-first").requestKey).isEqualTo("request-retry");
    assertThat(chief.stop(first.id, first.requestKey).state).isEqualTo("THINKING");
    assertThatThrownBy(() -> chief.retry(first.id, first.requestKey, "another-retry"))
        .hasMessageContaining("마지막 총괄 응답");
    assertThat(saved).hasSize(1);
    verifyNoInteractions(workflows);
  }

  @Test
  void revisionIncludesFullPreviousStepsAndNeverStartsWorkflow() throws Exception {
    var c = chief.send(null, project.id(), "계획 제안", "request-first");
    c.state = "PLAN_READY";
    c.plan = new ChiefService.Plan("제안", List.of(), List.of(
        new ChiefService.PlannedStep("첫 단계", "유지할 상세 지시"),
        new ChiefService.PlannedStep("둘째 단계", "삭제할 상세 지시")));
    saved.put(c.id, json.writeValueAsString(c));
    var revision = chief.send(c.id, project.id(), "둘째 단계를 삭제하고 첫 단계를 수정해", "request-edit");
    assertThat(revision.state).isEqualTo("THINKING");
    assertThat(revision.plan).isNull();
    assertThat(revision.previousPlan).isEqualTo(c.plan);
    when(adapter.command(any(), any())).thenAnswer(i -> {
      Domain.Task task = i.getArgument(0);
      assertThat(task.prompt).contains("유지할 상세 지시", "삭제할 상세 지시", "둘째 단계를 삭제");
      Path dir = i.getArgument(1);
      Files.writeString(dir.resolve("proposals.json"),
          "{\"response\":\"수정한 계획\",\"questions\":[],\"steps\":[{\"title\":\"수정한 첫 단계\",\"instruction\":\"수정 지시\"}]}");
      return List.of("/usr/bin/true");
    });
    chief.plan(c.id, revision.requestKey);
    assertThat(chief.get(c.id).plan.steps()).extracting(ChiefService.PlannedStep::title)
        .containsExactly("수정한 첫 단계");
    assertThat(chief.get(c.id).state).isEqualTo("PLAN_READY");
    verifyNoInteractions(workflows);
  }

  @Test
  void stopKillsResponseProcessAndOldFailureCannotOverwriteRetry() throws Exception {
    Path pidFile = temp.resolve("response.pid");
    when(adapter.command(any(), any())).thenReturn(List.of("python3", "-c",
        "import os,time,pathlib; pathlib.Path('response.pid').write_text(str(os.getpid())); time.sleep(30)"));
    var c = chief.send(null, project.id(), "요청", "request-first");
    chief.tick();
    await(() -> Files.exists(pidFile));
    long pid = Long.parseLong(Files.readString(pidFile));
    chief.stop(c.id, c.requestKey);
    var retry = chief.retry(c.id, c.requestKey, "request-retry");
    await(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    // Queue a barrier on the same executor: any old failure must have finished before it runs.
    barrier();
    assertThat(chief.get(c.id).state).isEqualTo("THINKING");
    assertThat(chief.get(c.id).requestKey).isEqualTo(retry.requestKey);
    assertThat(chief.get(c.id).error).isNull();
    assertThat(chief.get(c.id).messages).noneMatch(m -> m.text().contains("응답 실패"));
  }

  @Test
  void stopAlsoKillsModelSelection() throws Exception {
    var started = new CompletableFuture<Process>();
    when(models.choose(any(), anyString(), anyString(), any(Consumer.class))).thenAnswer(i -> {
      Process process = new ProcessBuilder("/bin/sleep", "30").start();
      try {
        Consumer<Process> onStart = i.getArgument(3);
        onStart.accept(process);
        started.complete(process);
        process.waitFor();
        throw new IllegalStateException("늦은 모델 선택 오류");
      } finally {
        Processes.kill(process);
      }
    });
    var c = chief.send(null, project.id(), "요청", "request-first");
    chief.tick();
    Process process = started.get(5, TimeUnit.SECONDS);
    chief.stop(c.id, c.requestKey);
    assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    barrier();
    assertThat(chief.get(c.id).state).isEqualTo("STOPPED");
    assertThat(chief.get(c.id).error).isNull();
    verifyNoInteractions(adapter);
  }

  @Test
  void aProcessStartingAfterStopAndRetryIsImmediatelyKilled() throws Exception {
    var selecting = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var spawned = new CompletableFuture<Process>();
    when(models.choose(any(), anyString(), anyString(), any(Consumer.class))).thenAnswer(i -> {
      selecting.countDown();
      if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
      Process process = new ProcessBuilder("/bin/sleep", "30").start();
      spawned.complete(process);
      try {
        Consumer<Process> onStart = i.getArgument(3);
        onStart.accept(process);
        return decision;
      } finally {
        Processes.kill(process);
      }
    });
    var c = chief.send(null, project.id(), "요청", "request-first");
    chief.tick();
    assertThat(selecting.await(5, TimeUnit.SECONDS)).isTrue();
    chief.stop(c.id, c.requestKey);
    chief.retry(c.id, c.requestKey, "request-retry");
    resume.countDown();
    assertThat(spawned.get(5, TimeUnit.SECONDS).waitFor(5, TimeUnit.SECONDS)).isTrue();
    barrier();
    assertThat(chief.get(c.id).state).isEqualTo("THINKING");
    assertThat(chief.get(c.id).requestKey).isEqualTo("request-retry");
    assertThat(chief.get(c.id).error).isNull();
    verifyNoInteractions(adapter);
  }

  @Test
  void lateSuccessfulResponseCannotReplaceTheNewRequest() throws Exception {
    var c = chief.send(null, project.id(), "요청", "request-first");
    when(adapter.command(any(), any())).thenAnswer(i -> {
      Path dir = i.getArgument(1);
      Files.writeString(dir.resolve("proposals.json"),
          "{\"response\":\"취소된 늦은 응답\",\"questions\":[],\"steps\":[]}");
      return List.of("unused");
    });
    try (var processes = mockStatic(Processes.class)) {
      processes.when(() -> Processes.run(anyList(), any(), any(), any(Duration.class), any()))
          .thenAnswer(i -> {
            chief.stop(c.id, c.requestKey);
            chief.retry(c.id, c.requestKey, "request-retry");
            return 0;
          });
      chief.plan(c.id, c.requestKey);
    }
    assertThat(chief.get(c.id).state).isEqualTo("THINKING");
    assertThat(chief.get(c.id).requestKey).isEqualTo("request-retry");
    assertThat(chief.get(c.id).plan).isNull();
    assertThat(chief.get(c.id).messages).noneMatch(m -> m.text().equals("취소된 늦은 응답"));
  }

  private void barrier() throws Exception {
    var field = ChiefService.class.getDeclaredField("executor");
    field.setAccessible(true);
    ((ExecutorService) field.get(chief)).submit(() -> {}).get(5, TimeUnit.SECONDS);
  }

  private static void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.call()) {
      if (System.nanoTime() > deadline) fail("총괄 프로세스 상태 변경 시간 초과");
      Thread.sleep(20);
    }
  }
}
