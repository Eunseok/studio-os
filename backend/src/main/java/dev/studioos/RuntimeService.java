package dev.studioos;

import static dev.studioos.Domain.*;

import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RuntimeService {
  final Set<String> processing = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final Store store;
  private final ProjectLibrary library;
  private final ModelRouter router;
  private final CapabilityService capabilities;
  private final GitWorkspaces git;
  private final VerificationSandbox sandbox;
  private final Map<String, AgentAdapter> adapters = new LinkedHashMap<>();
  public final Path data;

  public RuntimeService(
      Store store,
      ProjectLibrary library,
      ModelRouter router,
      CapabilityService capabilities,
      GitWorkspaces git,
      VerificationSandbox sandbox,
      List<AgentAdapter> adapters,
      @Value("${studio.data}") String data)
      throws Exception {
    this.store = store;
    this.library = library;
    this.router = router;
    this.capabilities = capabilities;
    this.git = git;
    this.sandbox = sandbox;
    this.data = Path.of(data).toAbsolutePath().normalize();
    Files.createDirectories(this.data.resolve("worktrees"));
    Files.createDirectories(this.data.resolve("artifacts"));
    for (var adapter : adapters) this.adapters.put(adapter.type(), adapter);
  }

  @PostConstruct
  public synchronized void recover() {
    if (agents().isEmpty()) {
      saveAgent("codex-developer", "Codex developer", "Developer", "codex");
      saveAgent("demo-developer", "Demo worker", "Developer", "demo");
    }
    for (Task t : tasks())
      if (Set.of(Status.RUNNING, Status.ANALYZING, Status.VERIFYING).contains(t.status)) {
        // Avoid PID reuse: only stop a surviving process with the recorded start instant.
        if (t.processId != null && t.processStartedAt != null) {
          ProcessHandle.of(t.processId)
              .filter(
                  p ->
                      p.info()
                          .startInstant()
                          .map(i -> i.toString().equals(t.processStartedAt))
                          .orElse(false))
              .ifPresent(Processes::kill);
        }
        t.status = Status.FAILED;
        t.message = "서버가 재시작되어 실행이 중단되었습니다. 보존된 worktree와 로그를 확인한 뒤 새 작업을 생성하세요.";
        t.finishedAt = now();
        save(t, "task.interrupted");
      }
  }

  public List<Project> projects() {
    return store.all("project", Project.class);
  }

  public List<Agent> agents() {
    return store.all("agent", Agent.class);
  }

  public List<Task> tasks() {
    return store.all("task", Task.class).stream()
        .sorted(Comparator.comparing((Task t) -> t.createdAt).reversed())
        .toList();
  }

  public Project project(String id) {
    return store.get("project", id, Project.class);
  }

  public Task task(String id) {
    return store.get("task", id, Task.class);
  }

  public AgentAdapter adapter(String name) {
    var a = adapters.get(name);
    if (a == null) throw new IllegalArgumentException("지원하지 않는 어댑터입니다");
    return a;
  }

  public synchronized Map<String, Object> snapshot() {
    Set<String> hidden = library.archived();
    return Map.of(
        "workflows",
        store.all("workflow", WorkflowDomain.Definition.class).stream()
            .filter(d -> !hidden.contains(d.projectId))
            .toList(),
        "workflowRuns",
        store.all("workflow-run", WorkflowDomain.Run.class).stream()
            .filter(r -> !hidden.contains(r.project.id()))
            .toList(),
        "skills",
        capabilities.summaries().stream()
            .filter(s -> s.get("projectId") == null || !hidden.contains(s.get("projectId")))
            .toList(),
        "projects",
        projects().stream().filter(p -> !hidden.contains(p.id())).toList(),
        "agents",
        agents(),
        "tasks",
        tasks().stream().filter(t -> !hidden.contains(t.projectId)).toList(),
        "eventCursor",
        store.latestEvent(),
        "adapters",
        adapters.values().stream()
            .map(a -> Map.of("type", a.type(), "requirements", a.inspect()))
            .toList(),
        "verificationSandbox",
        sandbox.available());
  }

  public synchronized Project addProject(
      String name, String path, String rules, List<String> command) throws Exception {
    checkText(name, 120, "프로젝트 이름");
    if (rules.length() > 20000) throw new IllegalArgumentException("규칙은 20,000자 이하로 입력하세요");
    validateCommand(command);
    Path real = git.validate(path);
    if (real.startsWith(data) || data.startsWith(real))
      throw new IllegalArgumentException("Studio 데이터 폴더와 분리된 저장소를 등록하세요");
    if (projects().stream().anyMatch(p -> p.path().equals(real.toString())))
      throw new IllegalArgumentException("이미 등록된 저장소입니다");
    var p = new Project(id(), name, real.toString(), rules, List.copyOf(command), now());
    store.save("project", p.id(), p, "project.created", null, Map.of("name", name));
    return p;
  }

  public synchronized Project settings(String id, String rules, List<String> command) {
    if (rules == null || rules.length() > 20000)
      throw new IllegalArgumentException("규칙은 20,000자 이하로 입력하세요");
    validateCommand(command);
    var old = project(id);
    var updated =
        new Project(old.id(), old.name(), old.path(), rules, List.copyOf(command), old.createdAt());
    store.save("project", id, updated, "project.updated", null, Map.of("name", old.name()));
    return updated;
  }

  public synchronized Agent addAgent(String name, String role, String type) {
    checkText(name, 80, "이름");
    checkText(role, 80, "역할");
    adapter(type);
    return saveAgent(id(), name, role, type);
  }

  private Agent saveAgent(String id, String name, String role, String type) {
    var a = new Agent(id, name, role, type);
    store.save("agent", id, a, "agent.registered", null, Map.of("name", name));
    return a;
  }

  public synchronized Task create(
      String projectId, String agentId, String title, String prompt, int timeout) throws Exception {
    return create(projectId, agentId, title, prompt, timeout, List.of());
  }

  public synchronized Task create(
      String projectId,
      String agentId,
      String title,
      String prompt,
      int timeout,
      List<String> requiredSkills)
      throws Exception {
    var p = project(projectId);
    boolean auto = agentId == null || agentId.equals("auto");
    var a = auto ? autoAgent() : store.get("agent", agentId, Agent.class);
    Task created =
        createPlan(
            p,
            a,
            title,
            prompt,
            timeout,
            requiredSkills,
            git.head(p),
            p.testCommand(),
            null,
            null,
            0,
            List.of(),
            false);
    if (auto) prepareAuto(created, true, null);
    return task(created.id);
  }

  public synchronized Task createWorkflowTask(
      WorkflowDomain.Run run,
      WorkflowDomain.Node node,
      int attempt,
      List<WorkflowDomain.Input> inputs,
      String prompt)
      throws Exception {
    Task created =
        createPlan(
            run.project,
            "auto".equals(node.agentId) ? autoAgent() : run.agents.get(node.agentId),
            node.title,
            prompt,
            node.timeoutSeconds,
            node.requiredSkills,
            run.baseCommit,
            node.testCommand,
            run.id,
            node.id,
            attempt,
            inputs,
            node.requireVerification);
    if (node.model != null) {
      router.selected(created, node.model, node.reasoning, node.routingReason, node.routingId);
      save(created, "model.plan.frozen");
    }
    created.requireReview =
        node.requireReview != null
            ? node.requireReview
            : Set.of("codex", "claude").contains(created.adapter);
    if ("auto".equals(node.agentId)) prepareAuto(created, created.requireReview, null);
    else save(created, "review.policy.frozen");
    return task(created.id);
  }

  public synchronized Task createImprovementAnalysis(
      String projectId, String agentId, String prompt) throws Exception {
    var p = project(projectId);
    var a = store.get("agent", agentId, Agent.class);
    Task t =
        createPlan(
            p,
            a,
            "개선 후보 조사",
            prompt,
            300,
            List.of(),
            git.head(p),
            List.of(),
            null,
            null,
            0,
            List.of(),
            false);
    t.improvementAnalysis = true;
    t.requireVerification = true;
    t.role = "Chief of Staff / 개선 조사";
    router.apply(t);
    t.requirements = inspect(t);
    t.status =
        t.requirements.stream().allMatch(Requirement::available)
            ? Status.WAITING_APPROVAL
            : Status.BLOCKED;
    save(t, "improvement.analysis.prepared");
    return t;
  }

  public synchronized Task manualModel(String id, String provider, String model, String reasoning) {
    Task t = task(id);
    if (t.startedAt != null
        || t.approvedAt != null
        || (t.status != Status.WAITING_APPROVAL && t.status != Status.BLOCKED))
      throw new IllegalArgumentException("승인 전 실행 계획에서만 모델을 변경할 수 있습니다");
    if (!Set.of("codex", "claude").contains(provider))
      throw new IllegalArgumentException("지원 실행 도구가 아닙니다");
    if (!router.models(provider).contains(model)
        || !Set.of("low", "medium", "high").contains(Objects.toString(reasoning, "")))
      throw new IllegalArgumentException("지원하지 않는 모델 또는 추론 수준입니다");
    String agentId =
        agents().stream()
            .filter(a -> a.adapter().equals(provider))
            .map(Agent::id)
            .findFirst()
            .orElseGet(() -> addAgent(provider + " specialist", "Specialist", provider).id());
    t.routingPending = false;
    t.routingError = null;
    t.autoRouting = false;
    t.adapter = provider;
    t.agentId = agentId;
    router.selected(t, model, reasoning, "사용자가 실행 전 추천을 재지정했습니다", null);
    t.requirements = inspect(t);
    t.status =
        t.requirements.stream().allMatch(Requirement::available)
            ? Status.WAITING_APPROVAL
            : Status.BLOCKED;
    save(t, "model.manually.selected");
    return t;
  }

  public synchronized void applyModelDecision(String id, ModelPlanner.Decision decision) {
    Task t = task(id);
    if (t.startedAt != null || (t.status != Status.WAITING_APPROVAL && t.status != Status.BLOCKED))
      throw new IllegalArgumentException("실행 전 계획만 변경할 수 있습니다");
    var c = decision.choice();
    if (!t.adapter.equals(c.provider())) throw new IllegalArgumentException("실행 도구가 선택 결과와 다릅니다");
    router.selected(
        t,
        c.model(),
        c.reasoning(),
        "AI 선택 · 확신 "
            + c.confidence()
            + " · "
            + c.reason()
            + " · 근거: "
            + String.join(" / ", c.evidence()),
        decision.id());
    save(t, "model.plan.frozen");
  }

  public synchronized void recordProposals(String id, List<ImprovementPayload.Proposal> proposals) {
    Task t = task(id);
    if (t.status != Status.VERIFYING || !t.improvementAnalysis)
      throw new IllegalArgumentException("제안 검증 단계가 아닙니다");
    t.improvementProposals = List.copyOf(proposals);
    save(t, "improvement.proposals.verified");
  }

  private Task createPlan(
      Project p,
      Agent a,
      String title,
      String prompt,
      int timeout,
      List<String> requiredSkills,
      String baseCommit,
      List<String> testCommand,
      String runId,
      String nodeId,
      int attempt,
      List<WorkflowDomain.Input> inputs,
      boolean requireVerification) {
    checkText(title, 160, "작업 제목");
    checkText(prompt, 24000, "작업 지시");
    if (timeout < 10 || timeout > 3600) throw new IllegalArgumentException("시간 제한은 10~3600초입니다");
    validateCommand(testCommand);
    Task t = new Task();
    t.id = id();
    t.projectId = p.id();
    t.agentId = a.id();
    t.title = title;
    t.prompt = prompt;
    t.role = a.role();
    t.adapter = a.adapter();
    t.rules = p.rules();
    t.sharedContext =
        library.context(p.id())
            + "\n"
            + "Documentation policy: Do not create status reports, plans, summaries or"
            + " documentation files in Git unless the user explicitly requests those files. Shared"
            + " project knowledge lives in Studio OS. Report results in your response.";
    t.sharedDocuments =
        library.documents(p.id()).stream()
            .map(d -> new DocumentRef(d.id(), d.title(), d.revision()))
            .toList();
    router.apply(t);
    t.testCommand = List.copyOf(testCommand);
    t.workflowRunId = runId;
    t.workflowNodeId = nodeId;
    t.workflowAttempt = attempt;
    t.workflowInputs = List.copyOf(inputs);
    t.requireVerification = requireVerification;
    t.timeoutSeconds = timeout;
    t.createdAt = now();
    t.updatedAt = t.createdAt;
    t.baseCommit = baseCommit;
    t.branch = "studio/" + t.id;
    t.worktree = data.resolve("worktrees").resolve(t.id).toString();
    t.requiredSkills = capabilities.names(requiredSkills);
    t.skills = capabilities.resolve(t.projectId, t.requiredSkills);
    t.requirements = inspect(t);
    t.status =
        t.requirements.stream().allMatch(Requirement::available)
            ? Status.WAITING_APPROVAL
            : Status.BLOCKED;
    t.message =
        t.status == Status.BLOCKED ? "부족한 실행 조건을 해결한 뒤 다시 확인하세요" : "격리된 브랜치의 실행 계획을 검토하고 승인하세요";
    save(t, "task.created");
    return t;
  }

  public List<Requirement> inspect(Task t) {
    var list = new ArrayList<>(adapter(t.adapter).inspect());
    if (t.routingPending || t.routingError != null)
      list.add(
          new Requirement(
              "모델 자동 판단", false, t.routingError == null ? "모델·추론 선택 중" : t.routingError));
    if (!t.adapter.equals("demo") || !t.testCommand.isEmpty())
      list.add(
          new Requirement(
              "프로세스 관리자", Processes.python() != null, "Python 3 · 취소와 시간 제한 시 프로세스 그룹 종료"));
    if (!t.testCommand.isEmpty())
      list.add(
          new Requirement(
              "검증 sandbox",
              sandbox.available(),
              "macOS sandbox-exec 또는 Linux bubblewrap · 검증 중 네트워크 차단"));
    if (t.requireVerification)
      list.add(
          new Requirement(
              "필수 단계 검증",
              (!t.testCommand.isEmpty() || t.improvementAnalysis),
              "테스트 통과와 결과 승인이 있어야 다음 단계로 진행합니다"));
    list.addAll(capabilities.requirements(t));
    return list;
  }

  public synchronized Agent autoAgent() {
    return agents().stream()
        .filter(a -> a.adapter().equals("codex"))
        .findFirst()
        .orElseGet(() -> addAgent("Auto specialist", "Specialist", "codex"));
  }

  private void prepareAuto(Task t, boolean review, String previous) {
    t.autoRouting = true;
    t.routingPending = true;
    t.routingError = null;
    t.requireReview = review;
    t.previousAttemptId = previous;
    t.model = null;
    t.reasoning = null;
    t.routingReason = "AI가 작업·실행 도구·추론 수준을 판단 중입니다";
    t.routingId = null;
    t.status = Status.BLOCKED;
    t.message = t.routingReason;
    t.requirements = inspect(t);
    save(t, "model.planning");
  }

  public synchronized void routingComplete(
      String id, ModelPlanner.Decision decision, String error) {
    Task t = task(id);
    if (!t.routingPending || t.startedAt != null || terminal(t.status)) return;
    t.routingPending = false;
    t.routingError = error;
    if (error == null) {
      var c = decision.choice();
      t.adapter = c.provider();
      t.agentId =
          agents().stream()
              .filter(a -> a.adapter().equals(t.adapter))
              .map(Agent::id)
              .findFirst()
              .orElseGet(() -> addAgent(t.adapter + " specialist", "Specialist", t.adapter).id());
      router.selected(
          t,
          c.model(),
          c.reasoning(),
          c.reason() + " · " + String.join(" / ", c.evidence()),
          decision.id());
    }
    t.requirements = inspect(t);
    t.status =
        t.requirements.stream().allMatch(Requirement::available)
            ? Status.WAITING_APPROVAL
            : Status.BLOCKED;
    t.message =
        error == null ? "모델 선택 완료 · 리뷰 정책과 실행 계획을 승인하세요" : "모델 선택 실패 · 재검사 또는 수동 선택: " + error;
    save(t, "model.planned");
  }

  public synchronized void retryPolicy(String id, Task previous) {
    Task t = task(id);
    t.previousAttemptId = previous.id;
    t.attempt = previous.attempt + 1;
    t.requireReview = previous.requireReview;
    boolean adapt =
        Set.of("codex", "claude").contains(previous.adapter)
            && previous.failure != null
            && previous.failure.adapt();
    if (adapt) prepareAuto(t, t.requireReview, previous.id);
    else {
      t.routingPending = false;
      t.autoRouting = previous.autoRouting;
      t.adapter = previous.adapter;
      t.agentId = previous.agentId;
      t.model = previous.model;
      t.reasoning = previous.reasoning;
      t.routingId = previous.routingId;
      t.routingReason =
          "원인별 재시도 · 환경/인증/한도/불명확한 실패는 모델을 승격하지 않습니다. "
              + (previous.failure == null ? "" : previous.failure.reason());
      t.requirements = inspect(t);
      t.status =
          t.requirements.stream().allMatch(Requirement::available)
              ? Status.WAITING_APPROVAL
              : Status.BLOCKED;
      save(t, "retry.policy.frozen");
    }
  }

  public synchronized Task retryTask(String id, String correction) throws Exception {
    Task old = task(id);
    if (old.workflowRunId != null || old.improvementAnalysis)
      throw new IllegalArgumentException("연결된 Workflow/개선함에서 재시도하세요");
    if (old.retryTaskId != null) return task(old.retryTaskId);
    if (!Set.of(Status.FAILED, Status.CANCELLED).contains(old.status))
      throw new IllegalArgumentException("실패·취소된 작업만 재시도할 수 있습니다");
    if (old.attempt >= 3) throw new IllegalArgumentException("최대 3회 시도에 도달했습니다");
    String extra = Objects.toString(correction, "");
    if (extra.length() > 2000) throw new IllegalArgumentException("보완 지시는 2000자 이하입니다");
    Project p = project(old.projectId);
    Project frozen =
        new Project(p.id(), p.name(), p.path(), old.rules, old.testCommand, p.createdAt());
    Task t =
        createPlan(
            frozen,
            store.get("agent", old.agentId, Agent.class),
            old.title,
            old.prompt
                + "\n이전 실패 근거를 읽고 원인을 수정하세요: "
                + artifacts(old.id)
                + "\n분류: "
                + old.failure
                + "\n재시도 보완: "
                + extra,
            old.timeoutSeconds,
            old.requiredSkills,
            old.baseCommit,
            old.testCommand,
            null,
            null,
            0,
            List.of(),
            old.requireVerification);
    retryPolicy(t.id, old);
    old.retryTaskId = t.id;
    save(old, "task.retry.linked");
    return task(t.id);
  }

  public synchronized void reviewProgress(
      String id, ModelPlanner.Decision d, ReviewService.Result result, String digest) {
    Task t = task(id);
    if (terminal(t.status) || t.steeringPending) return;
    if (t.status != Status.VERIFYING) throw new IllegalArgumentException("검증 단계에서만 리뷰할 수 있습니다");
    t.reviewStatus =
        result == null
            ? "RUNNING"
            : result.verdict().equals("PASS") ? "PASSED" : "CHANGES_REQUIRED";
    t.review = result;
    t.reviewDigest = digest;
    t.reviewerProvider = d.choice().provider();
    t.reviewerModel = d.choice().model();
    t.reviewerReasoning = d.choice().reasoning();
    t.reviewRoutingId = d.id();
    t.message = result == null ? "독립 읽기 전용 리뷰 실행 중" : "독립 리뷰: " + t.reviewStatus;
    save(t, "review.progress");
  }

  public synchronized Task recheck(String id) {
    Task t = task(id);
    require(t, Status.BLOCKED);
    if (t.autoRouting && t.routingError != null) {
      prepareAuto(t, t.requireReview, t.previousAttemptId);
      return task(id);
    }
    t.skills = capabilities.resolve(t.projectId, t.requiredSkills);
    t.approvedAt = null;
    t.requirements = inspect(t);
    if (t.requirements.stream().allMatch(Requirement::available)) {
      t.status = Status.WAITING_APPROVAL;
      t.message = "실행 조건을 충족했습니다. 계획을 검토하고 승인하세요";
    }
    save(t, "capability.checked");
    return t;
  }

  public synchronized Task approve(String id) {
    Task t = task(id);
    if (t.steeringPending) throw new IllegalArgumentException("추가 지시의 적용 승인이 필요합니다");
    require(t, Status.WAITING_APPROVAL);
    if (t.agentExitCode == null) {
      t.requirements = inspect(t);
      if (t.requirements.stream().anyMatch(r -> !r.available())) {
        t.status = Status.BLOCKED;
        t.message = "승인 전 실행 조건이 변경되었습니다. Academy에서 해결하고 재검사하세요";
        save(t, "task.blocked");
        return t;
      }
      t.status = Status.QUEUED;
      t.approvedAt = now();
      t.message = "승인됨 · 실행 대기 중";
    } else {
      if (t.requireVerification && t.verification != Verification.PASSED)
        throw new IllegalArgumentException("필수 검증이 통과되어야 결과를 승인할 수 있습니다");
      if (t.requireReview) {
        if (!"PASSED".equals(t.reviewStatus)
            || t.review == null
            || !"PASS".equals(t.review.verdict()))
          throw new IllegalArgumentException("독립 리뷰를 통과해야 결과를 승인할 수 있습니다");
        try {
          git.artifacts(t, artifacts(t.id));
          if (!Objects.equals(
              t.reviewDigest, GitWorkspaces.patchDigest(artifacts(t.id).resolve("changes.patch"))))
            throw new IllegalArgumentException("리뷰 이후 변경이 발생했습니다. 새 검증 작업이 필요합니다");
        } catch (Exception e) {
          throw new IllegalArgumentException("리뷰 결과 무결성 확인 실패: " + e.getMessage());
        }
      }
      t.status = Status.SUCCEEDED;
      t.finishedAt = now();
      t.message =
          t.verification == Verification.PASSED
              ? "테스트와 사용자 검토를 통과했습니다"
              : "사용자가 결과를 승인했습니다. 자동 테스트는 미확인입니다";
    }
    save(t, t.status == Status.QUEUED ? "approval.granted" : "task.succeeded");
    return t;
  }

  public synchronized Task cancel(String id) {
    Task t = task(id);
    if (terminal(t.status)) throw new IllegalArgumentException("이미 종료된 작업입니다");
    t.status = Status.CANCELLED;
    t.message = "사용자가 취소했습니다. 코드와 로그는 보존됩니다";
    t.finishedAt = now();
    save(t, "task.cancelled");
    return t;
  }

  public synchronized Task update(
      String id,
      Status next,
      String message,
      Integer exit,
      Integer test,
      Verification verification) {
    Task t = task(id);
    if (terminal(t.status) || t.steeringPending) return t;
    boolean allowed =
        switch (t.status) {
          case QUEUED -> next == Status.ANALYZING || next == Status.FAILED;
          case ANALYZING ->
              next == Status.RUNNING || next == Status.BLOCKED || next == Status.FAILED;
          case RUNNING -> next == Status.VERIFYING || next == Status.FAILED;
          case VERIFYING -> next == Status.WAITING_APPROVAL || next == Status.FAILED;
          default -> false;
        };
    if (!allowed) throw new IllegalStateException("잘못된 상태 전이: " + t.status + " -> " + next);
    if (next == Status.BLOCKED) {
      t.requirements = inspect(t);
      t.approvedAt = null;
    }
    t.status = next;
    t.message = message;
    if (next == Status.ANALYZING) t.startedAt = now();
    if (exit != null) t.agentExitCode = exit;
    if (test != null) t.testExitCode = test;
    if (verification != null) t.verification = verification;
    if (terminal(next)) t.finishedAt = now();
    if (next == Status.FAILED) {
      // Persist the terminal state before optional diagnostics; an analysis failure must not leave
      // RUNNING.
      save(t, "task.failed");
      try {
        t.failure = FailurePolicy.assess(t, data.resolve("artifacts").resolve(t.id));
      } catch (RuntimeException ex) {
        t.failure = new FailurePolicy.Assessment("UNKNOWN", false, "실패 원인 분석 중 오류 · 변경은 보존됨");
      }
    }
    save(t, "task." + next.name().toLowerCase(Locale.ROOT));
    return t;
  }

  public synchronized void processStarted(String id, Process process) {
    Task t = task(id);
    if (terminal(t.status) || t.steeringPending) {
      Processes.kill(process);
      return;
    }
    t.processId = process.pid();
    t.processStartedAt = process.info().startInstant().map(Object::toString).orElse(null);
    save(t, "process.started");
  }

  public synchronized Task applyToProject(String id) throws Exception {
    Task t = task(id);
    if (t.appliedCommit != null) return t;
    if (t.status != Status.SUCCEEDED
        || t.verification != Verification.PASSED
        || !"PASSED".equals(t.reviewStatus))
      throw new IllegalArgumentException("검증·독립 리뷰·결과 승인이 완료되어야 프로젝트에 반영할 수 있습니다");
    if (t.workflowRunId != null) {
      var run = store.get("workflow-run", t.workflowRunId, WorkflowDomain.Run.class);
      Set<String> included = new HashSet<>();
      included.add(t.workflowNodeId);
      t.workflowInputs.forEach(i -> included.add(i.nodeId()));
      if (!run.status.equals("SUCCEEDED") || included.size() != run.steps.size())
        throw new IllegalArgumentException("모든 단계가 통합·검증·승인된 마지막 결과만 반영할 수 있습니다");
    }
    git.artifacts(t, artifacts(id));
    Path patch = artifacts(id).resolve("changes.patch");
    if (!Objects.equals(t.reviewDigest, GitWorkspaces.patchDigest(patch)))
      throw new IllegalArgumentException("리뷰 이후 변경된 결과는 반영할 수 없습니다");
    Project p = project(t.projectId);
    Path root = Path.of(p.path());
    if (!git.head(p).equals(t.baseCommit)
        || !Processes.quick(root, "git", "status", "--porcelain").isBlank())
      throw new IllegalArgumentException("프로젝트가 작업 기준 이후 변경됐습니다. 기존 변경을 덮어쓰지 않습니다");
    if (Files.size(patch) == 0) throw new IllegalArgumentException("반영할 코드 변경이 없습니다");
    Processes.quick(
        root,
        "git",
        "-c",
        "core.hooksPath=/dev/null",
        "apply",
        "--check",
        "--index",
        patch.toString());
    Processes.quick(
        root, "git", "-c", "core.hooksPath=/dev/null", "apply", "--index", patch.toString());
    Processes.quick(
        root,
        "git",
        "-c",
        "core.hooksPath=/dev/null",
        "-c",
        "commit.gpgsign=false",
        "-c",
        "user.name=Studio OS",
        "-c",
        "user.email=studio@localhost",
        "commit",
        "-m",
        "Studio: " + t.title);
    t.appliedCommit = git.head(p);
    save(t, "project.change.applied");
    return t;
  }

  public synchronized Task cleanup(String id) throws Exception {
    Task t = task(id);
    if (!terminal(t.status)) throw new IllegalArgumentException("종료된 작업만 정리할 수 있습니다");
    if (t.cleaned) return t;
    if (Files.isDirectory(Path.of(t.worktree))) git.clean(project(t.projectId), t);
    t.cleaned = true;
    save(t, "workspace.cleaned");
    return t;
  }

  public synchronized void verificationPassed(String id, int exit) {
    Task t = task(id);
    if (t.status != Status.VERIFYING || t.steeringPending) return;
    t.verification = Verification.PASSED;
    t.testExitCode = exit;
    save(t, "verification.passed");
  }

  public synchronized void recordInputTree(String id, String tree) {
    Task t = task(id);
    if (terminal(t.status)) return;
    t.inputTree = tree;
    save(t, "workflow.inputs.prepared");
  }

  public String prepareSkills(Task t, Path dir) throws Exception {
    return capabilities.materialize(t, dir);
  }

  public Path artifacts(String id) {
    task(id);
    return data.resolve("artifacts").resolve(id);
  }

  public synchronized void recordExecution(String id, ExecutionSettings execution) {
    Task t = task(id);
    t.execution = execution;
    save(t, "execution.settings");
  }

  public synchronized void workspacePrepared(String id) {
    Task t = task(id);
    t.workspacePrepared = true;
    save(t, "workspace.prepared");
  }

  public synchronized Task recoverInterrupted(String id) throws Exception {
    if (processing.contains(id))
      throw new IllegalArgumentException("기존 실행 기록을 정리 중입니다. 잠시 후 복구하세요");
    Task t = task(id);
    if (t.status == Status.QUEUED && t.recoveryCount > 0) return t;
    if (!Set.of(Status.FAILED, Status.CANCELLED).contains(t.status)
        || t.cleaned
        || t.improvementAnalysis) throw new IllegalArgumentException("중단된 구현 작업만 복구할 수 있습니다");
    if (t.processId != null
        && ProcessHandle.of(t.processId)
            .filter(ProcessHandle::isAlive)
            .filter(
                p ->
                    p.info()
                        .startInstant()
                        .map(i -> i.toString().equals(t.processStartedAt))
                        .orElse(true))
            .isPresent()) throw new IllegalArgumentException("기존 프로세스 종료를 먼저 확인해야 합니다");
    Path workspace = Path.of(t.worktree).toRealPath();
    if (!workspace.getParent().equals(data.resolve("worktrees").toRealPath())
        || !workspace.getFileName().toString().startsWith(t.id)
        || !Processes.quick(workspace, "git", "rev-parse", "HEAD").equals(t.baseCommit)
        || !Processes.quick(workspace, "git", "branch", "--show-current").equals(t.branch))
      throw new IllegalArgumentException("보존된 작업 공간의 기준이 변경되었습니다. 자동으로 덮어쓰지 않습니다");
    git.artifacts(t, artifacts(id));
    String latest = git.head(project(t.projectId));
    if (!latest.equals(t.baseCommit)) {
      // Migrate a preserved patch onto the project's current baseline in a NEW worktree.
      // The old directory/branch remains available even if applying the patch fails.
      Path candidate = data.resolve("worktrees").resolve(t.id + "-recovery-" + UUID.randomUUID());
      String branch = "studio/recovery-" + UUID.randomUUID();
      Processes.quick(
          Path.of(project(t.projectId).path()),
          "git",
          "-c",
          "core.hooksPath=/dev/null",
          "worktree",
          "add",
          "-b",
          branch,
          candidate.toString(),
          latest);
      if (t.workflowRunId != null) {
        var run = store.get("workflow-run", t.workflowRunId, WorkflowDomain.Run.class);
        Set<String> included = new HashSet<>();
        included.add(t.workflowNodeId);
        t.workflowInputs.forEach(i -> included.add(i.nodeId()));
        if (!included.containsAll(run.steps.keySet()))
          throw new IllegalArgumentException("기준이 달라진 중간 단계는 계획을 다시 확인해야 합니다");
      }
      String inputTree = latest;
      Path patch = artifacts(id).resolve("changes.patch");
      try {
        Processes.quick(candidate, "git", "apply", "--3way", "--index", patch.toString());
      } catch (Exception conflict) {
        if (Processes.quick(candidate, "git", "diff", "--name-only", "--diff-filter=U").isBlank())
          throw conflict;
        t.prompt +=
            "\n"
                + "최신 프로젝트 기준으로 이전 변경을 가져오는 중 충돌이 발견됐습니다. 충돌 표식을 해결하되 최신 기준의 사용자 승인 기능과 이전 작업의 의도를"
                + " 모두 보존하세요. 파일을 원래 버전으로 일괄 되돌리지 마세요.";
      }
      Processes.quick(candidate, "git", "reset", "--mixed", latest);
      t.worktree = candidate.toString();
      t.branch = branch;
      t.baseCommit = latest;
      t.inputTree = inputTree;
    }
    t.workspacePrepared = true;
    t.recoveryCount++;
    t.steeringRevision++;
    t.steeringPending = false;
    t.steeringStopped = false;
    t.steeringStopFailed = false;
    t.processId = null;
    t.processStartedAt = null;
    t.finishedAt = null;
    t.agentExitCode = null;
    t.testExitCode = null;
    t.verification = Verification.NOT_RUN;
    t.reviewStatus = "NOT_RUN";
    t.review = null;
    t.reviewDigest = null;
    t.failure = null;
    t.timeoutSeconds = Math.min(3600, Math.max(1200, t.timeoutSeconds * 2));
    t.prompt +=
        "\n\n"
            + "중단 복구: 이전 변경은 같은 작업 공간에 모두 보존되어 있습니다. git diff와 기존 파일을 먼저 읽고 남은 구현만 마무리하세요. 새 계획이나"
            + " 중복 구현을 만들지 마세요. 기존 테스트를 실행하고 수정 결과와 남은 제한을 보고하세요. 작업 완료 후 별도 검증과 독립 리뷰를 다시 실행합니다.";
    t.status = Status.QUEUED;
    t.message = "변경 내용 보존 완료 · 남은 구현을 이어서 진행합니다";
    save(t, "task.recovered");
    return t;
  }

  public synchronized void holdForSteering(String id, boolean stopped) {
    Task t = task(id);
    if (terminal(t.status) || t.improvementAnalysis)
      throw new IllegalArgumentException("진행 중인 구현 작업에만 추가 지시를 적용할 수 있습니다");
    if (t.steeringPending) return;
    t.steeringPending = true;
    t.steeringStopped = stopped;
    t.verification = Verification.NOT_RUN;
    t.testExitCode = null;
    t.reviewStatus = "NOT_RUN";
    t.review = null;
    t.reviewDigest = null;
    t.message = "추가 지시 접수 · 기존 호출 종료와 적용 승인 대기";
    save(t, "steering.received");
  }

  public synchronized void steeringStopped(String id) {
    Task t = task(id);
    if (!t.steeringPending || t.steeringStopFailed || terminal(t.status)) return;
    t.steeringStopped = true;
    t.processId = null;
    t.processStartedAt = null;
    t.message = "기존 호출 종료 확인 · 변경 보존 · 추가 지시 적용 승인 대기";
    save(t, "steering.stopped");
  }

  public synchronized void steeringStopFailed(String id) {
    Task t = task(id);
    if (!t.steeringPending) return;
    t.steeringStopFailed = true;
    t.message = "기존 실행 종료를 확인하지 못했습니다. 재개를 차단했습니다. 프로세스·환경을 확인해 주세요";
    save(t, "steering.stop.failed");
  }

  public synchronized Task resumeSteering(String id, String instruction) {
    Task t = task(id);
    if (!t.steeringPending || !t.steeringStopped || t.steeringStopFailed || terminal(t.status))
      throw new IllegalArgumentException("기존 호출이 완전히 종료된 뒤 재개할 수 있습니다");
    if (instruction == null
        || instruction.isBlank()
        || t.prompt.length() + instruction.length() > 22000)
      throw new IllegalArgumentException("누적 추가 지시가 너무 깁니다. 범위를 줄여 주세요");
    t.prompt +=
        "\n\n사용자가 적용 승인한 추가 지시 (기존 지시와 충돌하면 우선):\n"
            + instruction
            + "\n같은 worktree에 이전 변경이 보존되어 있습니다. 파일과 기존 변경을 먼저 확인하고 계속하세요.";
    t.steeringRevision++;
    t.steeringPending = false;
    t.steeringStopped = false;
    t.agentExitCode = null;
    t.finishedAt = null;
    t.status = t.routingPending ? Status.BLOCKED : Status.QUEUED;
    t.message = "추가 지시 반영 대기 · 같은 worktree에서 재개 후 테스트·독립 리뷰 재실행";
    save(t, "steering.resumed");
    return t;
  }

  public synchronized void steeringInvocation(String id, String capability) {
    Task t = task(id);
    if (t.steeringPending || terminal(t.status)) return;
    t.steeringCapability = capability;
    t.steeringAppliedRevision = t.steeringRevision;
    save(t, t.steeringRevision > 0 ? "steering.applied" : "execution.capability");
  }

  private void save(Task t, String event) {
    t.updatedAt = now();
    store.save("task", t.id, t, event, t.id, Map.of("status", t.status, "message", t.message));
  }

  public static boolean terminal(Status s) {
    return Set.of(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED).contains(s);
  }

  private static void require(Task t, Status expected) {
    if (t.status != expected)
      throw new IllegalArgumentException("현재 상태에서는 이 작업을 수행할 수 없습니다: " + t.status);
  }

  private static void checkText(String text, int max, String name) {
    if (text == null || text.isBlank() || text.length() > max)
      throw new IllegalArgumentException(name + "을(를) 1~" + max + "자로 입력하세요");
  }

  public static void validateCommand(List<String> command) {
    if (command == null
        || command.size() > 40
        || command.stream().anyMatch(s -> s == null || s.length() > 4000 || s.indexOf('\0') >= 0))
      throw new IllegalArgumentException("테스트 명령은 40개 이하 인자의 JSON 배열로 입력하세요");
  }
}
