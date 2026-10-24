package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.junit.jupiter.api.*;

class DeliveryServiceTest {
  ChiefService chief = mock(ChiefService.class);
  RuntimeService runtime = mock(RuntimeService.class);
  GithubService github = mock(GithubService.class);
  Store store = mock(Store.class);
  DeliveryService service;
  List<DeliveryService.Delivery> saved = new ArrayList<>();

  @BeforeEach
  void setup() {
    service = new DeliveryService(chief, runtime, github, store);
    when(store.all("delivery", DeliveryService.Delivery.class)).thenAnswer(i -> List.copyOf(saved));
    doAnswer(
            i -> {
              DeliveryService.Delivery d = i.getArgument(2);
              if (!saved.contains(d)) saved.add(d);
              return null;
            })
        .when(store)
        .save(eq("delivery"), anyString(), any(), anyString(), anyString(), anyMap());
  }

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void readingStatusNeverApprovesOrPublishes() {
    assertThat(service.get("c").status).isEqualTo("NONE");
    verifyNoInteractions(chief, runtime, github);
  }

  @Test
  void rejectedApprovalCannotQueueDelivery() {
    var c = new ChiefService.Conversation();
    c.id = "c";
    c.state = "WAITING_RESULT";
    when(chief.get("c")).thenReturn(c);
    when(chief.approve("c")).thenThrow(new IllegalArgumentException("독립 리뷰 미통과"));
    assertThatThrownBy(() -> service.approveAndDeliver("c")).hasMessageContaining("리뷰");
    assertThat(saved).isEmpty();
    verifyNoInteractions(github);
  }

  @Test
  void explicitApprovalQueuesOnlyAggregateAndIsIdempotent() {
    var c = new ChiefService.Conversation();
    c.id = "c";
    c.projectId = "p";
    c.runId = "r";
    c.state = "WAITING_RESULT";
    when(chief.get("c")).thenReturn(c);
    when(chief.approve("c")).thenReturn(c);
    var r = new WorkflowDomain.Run();
    r.status = "SUCCEEDED";
    var step = new WorkflowDomain.Step();
    step.attempts.add("t");
    r.steps.put("one", step);
    when(store.get("workflow-run", "r", WorkflowDomain.Run.class)).thenReturn(r);
    var t = new Task();
    t.id = "t";
    t.workflowNodeId = "one";
    when(runtime.task("t")).thenReturn(t);
    var result = service.approveAndDeliver("c");
    assertThat(result.status).isEqualTo("QUEUED");
    assertThat(result.taskId).isEqualTo("t");
    assertThat(service.approveAndDeliver("c")).isSameAs(result);
    verify(chief, times(1)).approve("c");
    verifyNoInteractions(github);
  }
}
