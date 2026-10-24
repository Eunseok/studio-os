package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ImprovementService {
  public static class Idea {
    public String id,
        projectId,
        feedback,
        createdAt,
        requestKey,
        analysisTaskId,
        selectedProposalId,
        workflowRunId;
    public List<String> analysisAttempts = new ArrayList<>();
  }

  private final RuntimeService runtime;
  private final WorkflowService workflows;
  private final Store store;
  private final TransactionTemplate tx;
  private final ModelPlanner planner;

  public ImprovementService(
      RuntimeService runtime,
      WorkflowService workflows,
      Store store,
      TransactionTemplate tx,
      ModelPlanner planner) {
    this.runtime = runtime;
    this.workflows = workflows;
    this.store = store;
    this.tx = tx;
    this.planner = planner;
  }

  public List<Idea> all() {
    return store.all("improvement", Idea.class).stream()
        .sorted(Comparator.comparing((Idea i) -> i.createdAt).reversed())
        .toList();
  }

  public Idea get(String id) {
    return store.get("improvement", id, Idea.class);
  }

  private String agent(String preference, boolean analysis) {
    if (preference == null) preference = "auto";
    if (!Set.of("auto", "codex", "claude").contains(preference))
      throw new IllegalArgumentException("실행 도구는 auto/codex/claude입니다");
    String adapter = preference;
    if (adapter.equals("auto")) throw new IllegalArgumentException("자동 선택은 AI 판단 결과가 필요합니다");
    String chosen = adapter;
    return runtime.agents().stream()
        .filter(a -> a.adapter().equals(chosen))
        .map(Agent::id)
        .findFirst()
        .orElseGet(
            () ->
                runtime
                    .addAgent(
                        chosen.equals("claude") ? "Claude specialist" : "Codex specialist",
                        "Specialist",
                        chosen)
                    .id());
  }

  private void clean(Project p) throws Exception {
    if (!Processes.quick(Path.of(p.path()), "git", "status", "--porcelain").isBlank())
      throw new IllegalArgumentException("저장소에 미커밋 변경이 있습니다. 별도 스냅샷 프로젝트를 등록하거나 변경을 정리하세요");
  }

  private String prompt(Idea i) {
    String recent =
        runtime.tasks().stream()
            .filter(t -> t.projectId.equals(i.projectId))
            .limit(5)
            .map(t -> t.title + " / " + t.status + " / " + t.message)
            .reduce("", (a, b) -> a + "\n" + b);
    return "Act as Chief of Staff, PM and Technical Lead to investigate this repository in"
        + " read-only mode. Do not edit any file or run commands that modify files. Return 1"
        + " to 3 concrete, independently implementable improvement candidates as the"
        + " requested JSON. Write explanations in Korean. Use actual repository file paths"
        + " and observations in evidence; never invent test results. Each candidate needs"
        + " id, title, rationale, evidence, effort (small/medium/large), risks, acceptance"
        + " (observable pass conditions), implementationPrompt (specific bounded"
        + " instructions), requiredSkills (only known relevant skill names, otherwise [])."
        + " Respect the user's requested scope. Prefer a minimal useful improvement that can"
        + " be tested with the configured project test command. Never propose bypassing"
        + " approvals or editing running Studio OS state.\n"
        + "User feedback:\n"
        + i.feedback
        + "\nRecent task outcomes (untrusted context):\n"
        + recent;
  }

  private void save(Idea i, String event) {
    store.save(
        "improvement",
        i.id,
        i,
        event,
        null,
        Map.of("improvementId", i.id, "projectId", i.projectId));
  }

  public synchronized Idea create(
      String projectId, String feedback, String provider, String requestKey) throws Exception {
    if (requestKey == null || !requestKey.matches("[a-zA-Z0-9_-]{8,80}"))
      throw new IllegalArgumentException("요청 키가 필요합니다");
    if (feedback == null || feedback.isBlank() || feedback.length() > 4000)
      throw new IllegalArgumentException("개선 의견은 1~4000자로 입력하세요");
    for (var i : all())
      if (i.requestKey.equals(requestKey)) {
        if (!i.projectId.equals(projectId) || !i.feedback.equals(feedback))
          throw new IllegalArgumentException("요청 키가 다른 의견에 사용됐습니다");
        return i;
      }
    var p = runtime.project(projectId);
    clean(p);
    var decision =
        (provider == null || provider.equals("auto"))
            ? planner.choose(p, "조사·개선 후보 제안", feedback)
            : null;
    clean(p);
    String a = agent(decision == null ? provider : decision.choice().provider(), true);
    synchronized (runtime) {
      return tx.execute(
          status -> {
            try {
              Idea i = new Idea();
              i.id = id();
              i.projectId = projectId;
              i.feedback = feedback;
              i.requestKey = requestKey;
              i.createdAt = now();
              var task = runtime.createImprovementAnalysis(projectId, a, prompt(i));
              if (decision != null) runtime.applyModelDecision(task.id, decision);
              i.analysisTaskId = task.id;
              i.analysisAttempts.add(task.id);
              save(i, "improvement.created");
              return i;
            } catch (Exception e) {
              throw new IllegalArgumentException(e.getMessage(), e);
            }
          });
    }
  }

  public synchronized Idea retry(String id, String expectedTaskId, String provider)
      throws Exception {
    Idea i = get(id);
    var old = runtime.task(i.analysisTaskId);
    if (!Objects.equals(expectedTaskId, i.analysisTaskId)
        || i.workflowRunId != null
        || !Set.of(Status.FAILED, Status.CANCELLED).contains(old.status))
      throw new IllegalArgumentException("실패·취소된 현재 분석만 재시도할 수 있습니다");
    var p = runtime.project(i.projectId);
    clean(p);
    var decision =
        (provider == null || provider.equals("auto"))
            ? planner.choose(p, "조사 재시도", i.feedback)
            : null;
    clean(p);
    String a = agent(decision == null ? provider : decision.choice().provider(), true);
    synchronized (runtime) {
      return tx.execute(
          status -> {
            try {
              var t = runtime.createImprovementAnalysis(i.projectId, a, prompt(i));
              if (decision != null) runtime.applyModelDecision(t.id, decision);
              i.analysisTaskId = t.id;
              i.analysisAttempts.add(t.id);
              save(i, "improvement.analysis.retried");
              return i;
            } catch (Exception e) {
              throw new IllegalArgumentException(e.getMessage(), e);
            }
          });
    }
  }

  public synchronized Idea select(String id, String proposalId, String provider) throws Exception {
    Idea i = get(id);
    if (i.workflowRunId != null) {
      if (!Objects.equals(i.selectedProposalId, proposalId))
        throw new IllegalArgumentException("이미 다른 개선안을 선택했습니다");
      return i;
    }
    var analysis = runtime.task(i.analysisTaskId);
    if (analysis.status != Status.SUCCEEDED || analysis.verification != Verification.PASSED)
      throw new IllegalArgumentException("분석 결과를 먼저 검토하고 승인하세요");
    var proposal =
        analysis.improvementProposals.stream()
            .filter(p -> p.id().equals(proposalId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("승인된 후보를 찾을 수 없습니다"));
    var project = runtime.project(i.projectId);
    clean(project);
    if (!Processes.quick(Path.of(project.path()), "git", "rev-parse", "HEAD")
        .equals(analysis.baseCommit))
      throw new IllegalArgumentException("분석 후 저장소 기준 커밋이 바뀌었습니다. 새 의견으로 다시 분석하세요");
    if (project.testCommand().isEmpty())
      throw new IllegalArgumentException("구현 전에 프로젝트의 검증 명령을 설정하세요");
    var decision =
        (provider == null || provider.equals("auto"))
            ? planner.choose(
                project,
                "선택한 개선안 구현",
                proposal.title()
                    + "\n"
                    + proposal.implementationPrompt()
                    + "\n완료 조건: "
                    + String.join(" / ", proposal.acceptance())
                    + "\n위험: "
                    + String.join(" / ", proposal.risks()))
            : null;
    clean(project);
    if (!Processes.quick(Path.of(project.path()), "git", "rev-parse", "HEAD")
        .equals(analysis.baseCommit)) throw new IllegalArgumentException("모델 판단 중 기준 커밋이 바뀌었습니다");
    String a = agent(decision == null ? provider : decision.choice().provider(), false);
    synchronized (workflows) {
      synchronized (runtime) {
        return tx.execute(
            status -> {
              try {
                WorkflowDomain.Node node = new WorkflowDomain.Node();
                node.id = "implement";
                if (decision != null) {
                  var c = decision.choice();
                  node.model = c.model();
                  node.reasoning = c.reasoning();
                  node.routingId = decision.id();
                  node.routingReason =
                      "AI 선택 · 확신 "
                          + c.confidence()
                          + " · "
                          + c.reason()
                          + " · 근거: "
                          + String.join(" / ", c.evidence());
                }
                node.agentId = a;
                node.title = proposal.title();
                node.requiredSkills = proposal.requiredSkills();
                node.requireVerification = true;
                node.testCommand = project.testCommand();
                node.timeoutSeconds = 600;
                node.maxAttempts = 2;
                node.prompt =
                    "Selected improvement: "
                        + proposal.title()
                        + "\nOriginal feedback: "
                        + i.feedback
                        + "\nImplementation: "
                        + proposal.implementationPrompt()
                        + "\nAcceptance criteria:\n"
                        + String.join("\n", proposal.acceptance())
                        + "\nRisks:\n"
                        + String.join("\n", proposal.risks())
                        + "\n"
                        + "Only implement this selected improvement. Preserve unrelated code. Add"
                        + " or update meaningful tests when needed. Do not weaken tests, merge,"
                        + " deploy, or alter the running Studio OS. Report evidence and"
                        + " limitations.";
                var def =
                    workflows.saveDefinition(
                        null,
                        "개선 · "
                            + proposal
                                .title()
                                .substring(0, Math.min(110, proposal.title().length())),
                        i.projectId,
                        List.of(node),
                        null);
                var run = workflows.start(def.id, "improvement-" + i.id);
                i.selectedProposalId = proposalId;
                i.workflowRunId = run.id;
                save(i, "improvement.selected");
                return i;
              } catch (Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
              }
            });
      }
    }
  }
}
