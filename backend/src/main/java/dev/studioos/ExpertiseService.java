package dev.studioos;

import static dev.studioos.Domain.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Evidence-backed, scoped qualifications. Installation alone never grants execution rights. */
@Service
public class ExpertiseService {
  public record Policy(String id, List<String> scopes) {}

  public static class Assessment {
    public String id,
        projectId,
        agentId,
        skillId,
        digest,
        scope,
        purpose,
        taskId,
        confirmedAt,
        expiresAt;
    public String status = "EVALUATING", evidence = "";
    public boolean effective;
  }

  private final Store store;
  private final CapabilityService skills;

  public ExpertiseService(Store store, CapabilityService skills) {
    this.store = store;
    this.skills = skills;
  }

  public List<Assessment> all() {
    return store.all("qualification", Assessment.class);
  }

  public Assessment get(String id) {
    return store.get("qualification", id, Assessment.class);
  }

  public Policy policy(String id) {
    return store.all("expertise-policy", Policy.class).stream()
        .filter(p -> p.id().equals(id))
        .findFirst()
        .orElse(new Policy(id, List.of()));
  }

  public synchronized Policy policy(String id, List<String> scopes) {
    store.get("project", id, Project.class);
    if (scopes == null
        || scopes.isEmpty()
        || scopes.size() > 12
        || scopes.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100))
      throw new IllegalArgumentException("필요한 전문 분야를 1~12개 입력하세요");
    Policy p = new Policy(id, scopes.stream().map(String::strip).distinct().toList());
    store.save(
        "expertise-policy",
        "expertise-policy:" + id,
        p,
        "expertise.policy",
        null,
        Map.of("projectId", id));
    return p;
  }

  public void save(Assessment a) {
    store.save(
        "qualification",
        a.id,
        a,
        "expertise.updated",
        a.taskId,
        Map.of("projectId", a.projectId, "status", a.status));
  }

  public boolean assessment(Task t) {
    return t.assessmentId != null
        && all().stream().anyMatch(a -> a.id.equals(t.assessmentId) && t.id.equals(a.taskId));
  }

  public List<String> scopes(Task t) {
    var s = new LinkedHashSet<>(policy(t.projectId).scopes());
    return List.copyOf(s);
  }

  private boolean validSkill(Assessment a) {
    try {
      var s = skills.get(a.skillId);
      return s.digest.equals(a.digest)
          && skills.usable(s.id)
          && s.validatedAt != null
          && s.checks.stream().allMatch(Requirement::available);
    } catch (Exception e) {
      return false;
    }
  }

  public boolean qualified(String project, String agent, String scope, String purpose) {
    return all().stream()
        .anyMatch(
            a ->
                a.projectId.equals(project)
                    && a.agentId.equals(agent)
                    && a.scope.equals(scope)
                    && a.purpose.equals(purpose)
                    && a.status.equals("QUALIFIED")
                    && a.expiresAt != null
                    && Instant.parse(a.expiresAt).isAfter(Instant.now())
                    && validSkill(a));
  }

  public boolean eligible(Task t, Agent a, String purpose) {
    return !scopes(t).isEmpty()
        && scopes(t).stream().allMatch(s -> qualified(t.projectId, a.id(), s, purpose));
  }

  public List<Requirement> requirements(Task t) {
    if (t.improvementAnalysis || "demo".equals(t.adapter) || assessment(t)) return List.of();
    var scopes = scopes(t);
    var result = new ArrayList<Requirement>();
    result.add(
        new Requirement(
            "업무 전문성 범위", !scopes.isEmpty(), "전문가 준비에서 필요한 분야를 지정하세요. 미지정은 실행 허용이 아닙니다."));
    for (String s : scopes)
      result.add(
          new Requirement(
              "전문성: " + s,
              qualified(t.projectId, t.agentId, s, "IMPLEMENT"),
              "담당자의 해당 분야 실무 평가와 자격 확인이 필요합니다."));
    if (t.requireReview)
      result.add(
          new Requirement(
              "독립 리뷰 전문성",
              store.all("agent", Agent.class).stream()
                  .anyMatch(a -> !a.id().equals(t.agentId) && eligible(t, a, "REVIEW")),
              "구현 담당과 다른 검토 자격 보유자가 필요합니다."));
    return result;
  }

  public String instructions(Task t, String agent, String purpose) {
    StringBuilder text = new StringBuilder();
    for (var a : all())
      if (scopes(t).contains(a.scope)
          && qualified(t.projectId, agent, a.scope, purpose)
          && a.agentId.equals(agent)
          && a.purpose.equals(purpose)
          && a.status.equals("QUALIFIED"))
        text.append("\nVerified scope: ")
            .append(a.scope)
            .append("\n")
            .append(skills.get(a.skillId).files.get("SKILL.md"));
    return text.toString();
  }

  public synchronized Assessment confirm(String id, String evidence, int days) {
    var a = get(id);
    var t = store.get("task", a.taskId, Task.class);
    if (!a.status.equals("EVALUATING")
        || t.verification != Verification.PASSED
        || !"PASSED".equals(t.reviewStatus)
        || t.review == null
        || !"PASS".equals(t.review.verdict())
        || !Integer.valueOf(0).equals(t.agentExitCode)
        || !Integer.valueOf(0).equals(t.testExitCode)
        || t.testCommand.isEmpty()
        || t.reviewDigest == null
        || t.reviewDigest.isBlank()
        || t.steeringPending
        || !Set.of(Status.WAITING_APPROVAL, Status.SUCCEEDED).contains(t.status))
      throw new IllegalArgumentException("실제 평가 작업의 테스트와 독립 리뷰 통과가 먼저 필요합니다");
    if (evidence == null
        || evidence.strip().length() < 20
        || evidence.length() > 4000
        || days < 1
        || days > 365)
      throw new IllegalArgumentException("실패 사례와 평가 한계를 포함한 근거(20~4000자), 유효기간(1~365일)이 필요합니다");
    if (!validSkill(a)) throw new IllegalArgumentException("후보 Skill이 변경되었거나 실행 조건이 부족합니다");
    a.status = "QUALIFIED";
    a.evidence = evidence.strip();
    a.confirmedAt = now();
    a.expiresAt = Instant.now().plusSeconds(days * 86400L).toString();
    save(a);
    return a;
  }

  public synchronized Assessment revoke(String id) {
    var a = get(id);
    a.status = "REVOKED";
    save(a);
    return a;
  }
}
