package dev.studioos;

import static dev.studioos.Domain.*;
import static dev.studioos.WorkflowDomain.*;

import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WorkflowService {
  private final Store store;
  private final RuntimeService runtime;
  private final ExecutionWorker worker;
  private final GitWorkspaces git;
  private final CapabilityService capabilities;
  private final TransactionTemplate tx;

  public WorkflowService(
      Store store,
      RuntimeService runtime,
      ExecutionWorker worker,
      GitWorkspaces git,
      CapabilityService capabilities,
      TransactionTemplate tx) {
    this.store = store;
    this.runtime = runtime;
    this.worker = worker;
    this.git = git;
    this.capabilities = capabilities;
    this.tx = tx;
  }

  public List<Definition> definitions() {
    return store.all("workflow", Definition.class);
  }

  public List<Run> runs() {
    return store.all("workflow-run", Run.class).stream()
        .sorted(Comparator.comparing((Run r) -> r.createdAt).reversed())
        .toList();
  }

  public Definition definition(String id) {
    return store.get("workflow", id, Definition.class);
  }

  public Run run(String id) {
    return store.get("workflow-run", id, Run.class);
  }

  public synchronized Definition saveDefinition(
      String id, String name, String projectId, List<Node> nodes, Integer expectedRevision) {
    text(name, 120, "Workflow 이름");
    Project project = runtime.project(projectId);
    List<Node> copy =
        store.decode(store.encode(new DefinitionHolder(nodes)), DefinitionHolder.class).nodes();
    validate(copy, project);
    Definition d = id == null ? new Definition() : definition(id);
    if (id != null && (expectedRevision == null || expectedRevision != d.revision))
      throw new IllegalArgumentException("정의가 변경됐습니다. 새로 불러온 뒤 편집하세요");
    if (id == null) {
      d.id = id();
      d.createdAt = now();
    }
    d.name = name;
    d.projectId = projectId;
    d.nodes = copy;
    d.revision++;
    d.updatedAt = now();
    store.save(
        "workflow",
        d.id,
        d,
        "workflow.saved",
        null,
        Map.of("workflowId", d.id, "name", name, "revision", d.revision));
    return d;
  }

  private record DefinitionHolder(List<Node> nodes) {}

  void validate(List<Node> nodes, Project project) {
    if (nodes == null || nodes.isEmpty() || nodes.size() > 20)
      throw new IllegalArgumentException("Workflow는 1~20개 단계가 필요합니다");
    Set<String> ids = new HashSet<>();
    for (Node n : nodes) {
      if (n == null || n.id == null || !n.id.matches("[a-z][a-z0-9-]{0,47}") || !ids.add(n.id))
        throw new IllegalArgumentException("단계 ID는 중복 없는 영문 소문자·숫자·하이픈(48자 이하)이어야 합니다");
      text(n.title, 120, "단계 제목");
      text(n.prompt, 20000, "단계 지시");
      if (n.agentId == null) n.agentId = "auto";
      if (!n.agentId.equals("auto")) store.get("agent", n.agentId, Agent.class);
      if (n.timeoutSeconds < 10
          || n.timeoutSeconds > 3600
          || n.maxAttempts < 1
          || n.maxAttempts > 5) throw new IllegalArgumentException("시간은 10~3600초, 최대 시도는 1~5회입니다");
      n.requiredSkills = capabilities.names(n.requiredSkills);
      if (n.dependsOn == null
          || n.dependsOn.size() > 20
          || new HashSet<>(n.dependsOn).size() != n.dependsOn.size())
        throw new IllegalArgumentException("중복 없는 의존 단계 배열이 필요합니다");
      var command = n.testCommand == null ? project.testCommand() : n.testCommand;
      RuntimeService.validateCommand(command);
      if (n.requireVerification && command.isEmpty())
        throw new IllegalArgumentException(n.id + ": 필수 검증에는 테스트 명령이 필요합니다");
    }
    for (Node n : nodes)
      for (String dep : n.dependsOn)
        if (!ids.contains(dep) || n.id.equals(dep))
          throw new IllegalArgumentException(n.id + ": 알 수 없는 의존 단계 또는 자기 참조");
    ordered(nodes); // Reject cycles, including disconnected cycles.
  }

  public static List<Node> ordered(List<Node> nodes) {
    List<Node> result = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    while (result.size() < nodes.size()) {
      int before = result.size();
      for (Node n : nodes)
        if (!seen.contains(n.id) && seen.containsAll(n.dependsOn)) {
          seen.add(n.id);
          result.add(n);
        }
      if (before == result.size()) throw new IllegalArgumentException("순환 의존성이 있습니다. 단계 연결을 수정하세요");
    }
    return result;
  }

  public synchronized Run start(String definitionId, String requestKey) throws Exception {
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,80}"))
      throw new IllegalArgumentException("8~80자 요청 키가 필요합니다");
    for (Run old : runs())
      if (requestKey.equals(old.requestKey)) {
        if (!old.definition.id.equals(definitionId))
          throw new IllegalArgumentException("다른 Workflow에 사용한 요청 키입니다");
        return old;
      }
    Definition d = definition(definitionId);
    Project p = runtime.project(d.projectId);
    validate(d.nodes, p);
    Run run = new Run();
    run.id = id();
    run.requestKey = requestKey;
    run.definition = d;
    run.project = p;
    run.baseCommit = git.head(p);
    run.createdAt = now();
    run.updatedAt = run.createdAt;
    for (Node n : d.nodes) {
      if (n.testCommand == null) n.testCommand = List.copyOf(p.testCommand());
      if (!n.agentId.equals("auto"))
        run.agents.put(n.agentId, store.get("agent", n.agentId, Agent.class));
      Step step = new Step();
      step.nodeId = n.id;
      run.steps.put(n.id, step);
    }
    // A task and its owning run/attempt are committed together. No orphan tasks on failure.
    return atomic(
        () -> {
          advance(run);
          save(run, "workflow.started");
          return run;
        });
  }

  @Scheduled(fixedDelay = 700)
  public synchronized void tick() {
    for (Run run : runs()) {
      if (Set.of("SUCCEEDED", "CANCELLED").contains(run.status)) continue;
      try {
        reconcile(run.id);
      } catch (Exception e) {
        // Surface orchestration failures without losing persisted task ownership.
        Run failed = run(run.id);
        String message = "Workflow 조정 실패: " + Objects.toString(e.getMessage(), "알 수 없는 오류");
        if (!message.equals(failed.message)) {
          failed.status = "FAILED";
          failed.message = message;
          save(failed, "workflow.error");
        }
      }
    }
  }

  public synchronized Run reconcile(String id) {
    return atomic(
        () -> {
          Run run = run(id);
          if (Set.of("SUCCEEDED", "CANCELLED").contains(run.status)) return run;
          String before = store.encode(run);
          advance(run);
          if (!store.encode(run).equals(before)) save(run, "workflow.progress");
          return run;
        });
  }

  private void advance(Run run) {
    for (Node node : ordered(run.definition.nodes)) {
      Step step = run.steps.get(node.id);
      if (!step.attempts.isEmpty()) {
        Task task = runtime.task(step.attempts.getLast());
        if (!step.status.equals("SUCCEEDED")) {
          step.status = task.status.name();
          step.message = task.message;
          if (task.status == Status.SUCCEEDED) {
            if (node.requireVerification && task.verification != Verification.PASSED) {
              step.status = "FAILED";
              step.message = "필수 검증이 통과되지 않아 다음 단계로 진행하지 않습니다";
            } else
              try {
                step.outputDigest =
                    GitWorkspaces.patchDigest(runtime.artifacts(task.id).resolve("step.patch"));
                step.message = "검증·결과 승인 완료 · 다음 단계에 변경 사항 전달";
              } catch (Exception e) {
                step.status = "FAILED";
                step.message = "단계 결과물 오류: " + e.getMessage();
              }
          }
        }
      } else if (node.dependsOn.stream()
          .allMatch(dep -> run.steps.get(dep).status.equals("SUCCEEDED"))) {
        createAttempt(run, node, step, "");
      } else {
        step.status = "WAITING_DEPENDENCIES";
        step.message =
            node.dependsOn.stream()
                    .anyMatch(
                        dep -> Set.of("FAILED", "CANCELLED").contains(run.steps.get(dep).status))
                ? "선행 단계가 실패·취소되었습니다. 해당 단계를 재시도하세요"
                : "선행 단계의 검증과 결과 승인을 기다립니다";
      }
    }
    deriveStatus(run);
  }

  private void createAttempt(Run run, Node node, Step step, String correction) {
    // Transitive closure, in topological order: shared ancestors are applied exactly once.
    Set<String> ancestors = new HashSet<>();
    collect(node.id, run.definition.nodes, ancestors);
    List<Input> inputs = new ArrayList<>();
    for (Node predecessor : ordered(run.definition.nodes))
      if (ancestors.contains(predecessor.id)) {
        Step parent = run.steps.get(predecessor.id);
        if (!parent.status.equals("SUCCEEDED") || parent.outputDigest == null)
          throw new IllegalStateException("선행 결과가 아직 승인되지 않았습니다");
        inputs.add(new Input(parent.attempts.getLast(), predecessor.id, parent.outputDigest));
      }
    String prompt = node.prompt;
    if (!step.attempts.isEmpty()) {
      String previous = step.attempts.getLast();
      prompt +=
          "\n\n이전 시도: "
              + previous
              + "\n이전 로그: "
              + runtime.artifacts(previous)
              + "\n새 worktree에서 재시도합니다. 이전 실패 원인을 검토하세요.";
    }
    if (!correction.isBlank()) prompt += "\n재시도 보완 지시:\n" + correction;
    try {
      Task task = runtime.createWorkflowTask(run, node, step.attempts.size() + 1, inputs, prompt);
      if (!step.attempts.isEmpty())
        runtime.retryPolicy(task.id, runtime.task(step.attempts.getLast()));
      task = runtime.task(task.id);
      step.attempts.add(task.id);
      step.outputDigest = null;
      step.status = task.status.name();
      step.message = task.message;
    } catch (Exception e) {
      throw new IllegalStateException("단계 준비 실패: " + node.id + ": " + e.getMessage(), e);
    }
  }

  private static void collect(String id, List<Node> nodes, Set<String> result) {
    Node node = nodes.stream().filter(n -> n.id.equals(id)).findFirst().orElseThrow();
    for (String dep : node.dependsOn) if (result.add(dep)) collect(dep, nodes, result);
  }

  private void deriveStatus(Run run) {
    var states = run.steps.values().stream().map(s -> s.status).toList();
    if (states.stream().allMatch(s -> s.equals("SUCCEEDED"))) {
      run.status = "SUCCEEDED";
      run.message = "모든 단계의 검증과 결과 승인이 완료됐습니다";
      if (run.finishedAt == null) run.finishedAt = now();
    } else if (states.stream()
        .anyMatch(s -> Set.of("RUNNING", "QUEUED", "ANALYZING", "VERIFYING").contains(s))) {
      run.status = "RUNNING";
      run.message = "승인된 단계를 Runtime에서 실행 중입니다";
    } else if (states.contains("WAITING_APPROVAL")) {
      run.status = "WAITING_APPROVAL";
      run.message = "단계의 실행 계획 또는 결과 승인이 필요합니다";
    } else if (states.contains("BLOCKED")) {
      run.status = "BLOCKED";
      run.message = "Academy에서 부족한 실행 조건을 해결하고 재검사하세요";
    } else {
      run.status = "FAILED";
      run.message = "실패·취소된 단계를 확인하고 허용 횟수 내에서 재시도하세요";
    }
  }

  public synchronized Run retry(
      String id, String nodeId, String expectedTaskId, String correction) {
    text(correction == null ? "" : correction, 2000, "재시도 지시", true);
    return atomic(
        () -> {
          Run run = reconcile(id);
          if (Set.of("SUCCEEDED", "CANCELLED").contains(run.status))
            throw new IllegalArgumentException("종료된 Workflow는 재시도할 수 없습니다");
          Node node =
              run.definition.nodes.stream()
                  .filter(n -> n.id.equals(nodeId))
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("단계를 찾을 수 없습니다"));
          Step step = run.steps.get(nodeId);
          if (!Set.of("FAILED", "CANCELLED").contains(step.status))
            throw new IllegalArgumentException("실패·취소된 단계만 재시도할 수 있습니다");
          if (step.attempts.isEmpty() || !Objects.equals(expectedTaskId, step.attempts.getLast()))
            throw new IllegalArgumentException("시도가 변경됐습니다. 최신 상태를 확인하세요");
          if (step.attempts.size() >= node.maxAttempts)
            throw new IllegalArgumentException("최대 시도 횟수에 도달했습니다. 정의를 수정해 새 실행을 만드세요");
          createAttempt(run, node, step, Objects.toString(correction, ""));
          run.finishedAt = null;
          deriveStatus(run);
          save(run, "workflow.retried");
          return run;
        });
  }

  public synchronized Run cancel(String id) {
    List<String> stop = new ArrayList<>();
    Run result =
        atomic(
            () -> {
              Run run = run(id);
              if (Set.of("SUCCEEDED", "CANCELLED").contains(run.status))
                throw new IllegalArgumentException("이미 종료된 Workflow입니다");
              run.status = "CANCELLED";
              run.message = "Workflow 취소됨 · 시도와 결과물은 보존됩니다";
              run.finishedAt = now();
              for (Step step : run.steps.values()) {
                if (!step.attempts.isEmpty()) {
                  String taskId = step.attempts.getLast();
                  Task t = runtime.task(taskId);
                  if (!RuntimeService.terminal(t.status)) {
                    runtime.cancel(taskId);
                    stop.add(taskId);
                  }
                  step.status = runtime.task(taskId).status.name();
                } else step.status = "CANCELLED";
              }
              save(run, "workflow.cancelled");
              return run;
            });
    stop.forEach(worker::stop);
    return result;
  }

  private <T> T atomic(java.util.function.Supplier<T> action) {
    // Hold Runtime's monitor through commit: task approvals/cancellation cannot see partial
    // attempts.
    synchronized (runtime) {
      return tx.execute(status -> action.get());
    }
  }

  private void save(Run run, String event) {
    run.updatedAt = now();
    store.save(
        "workflow-run",
        run.id,
        run,
        event,
        null,
        Map.of(
            "runId",
            run.id,
            "workflowId",
            run.definition.id,
            "status",
            run.status,
            "message",
            run.message,
            "name",
            run.definition.name));
  }

  private static void text(String text, int max, String field) {
    text(text, max, field, false);
  }

  private static void text(String text, int max, String field, boolean empty) {
    if (text == null || (!empty && text.isBlank()) || text.length() > max)
      throw new IllegalArgumentException(field + ": " + max + "자 이하로 입력하세요");
  }
}
