package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/expertise")
public class ExpertiseController {
  private final ExpertiseService expertise;
  private final RuntimeService runtime;
  private final CapabilityService skills;
  private final ProjectLibrary library;
  private final GitWorkspaces git;

  public ExpertiseController(
      ExpertiseService e,
      RuntimeService r,
      CapabilityService s,
      ProjectLibrary l,
      GitWorkspaces g) {
    expertise = e;
    runtime = r;
    skills = s;
    library = l;
    git = g;
  }

  public record Scopes(List<String> scopes) {}

  public record Candidate(
      String agentId,
      String skillId,
      String scope,
      String purpose,
      String exercise,
      String verificationScript) {}

  public record Confirm(String evidence, int days) {}

  @GetMapping
  public Object list(@PathVariable String projectId) {
    runtime.project(projectId);
    return Map.of(
        "policy",
        expertise.policy(projectId),
        "assessments",
        expertise.all().stream()
            .filter(a -> a.projectId.equals(projectId))
            .peek(
                a -> a.effective = expertise.qualified(a.projectId, a.agentId, a.scope, a.purpose))
            .toList(),
        "tasks",
        expertise.all().stream()
            .filter(a -> a.projectId.equals(projectId) && a.taskId != null)
            .map(a -> runtime.task(a.taskId))
            .toList());
  }

  @PostMapping("/policy")
  public Object policy(@PathVariable String projectId, @RequestBody Scopes r) {
    return expertise.policy(projectId, r.scopes());
  }

  @PostMapping("/assessments")
  public synchronized Object create(@PathVariable String projectId, @RequestBody Candidate r)
      throws Exception {
    runtime.project(projectId);
    var agent =
        runtime.agents().stream()
            .filter(
                a -> a.id().equals(r.agentId()) && Set.of("codex", "claude").contains(a.adapter()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("실제 실행 도구를 가진 평가 후보가 필요합니다"));
    var skill = skills.get(r.skillId());
    if (skill.projectId != null && !skill.projectId.equals(projectId))
      throw new IllegalArgumentException("다른 프로젝트의 후보입니다");
    if (r.scope() == null
        || r.scope().isBlank()
        || r.scope().length() > 100
        || !Set.of("IMPLEMENT", "REVIEW").contains(Objects.toString(r.purpose(), ""))
        || r.exercise() == null
        || r.exercise().length() < 20
        || r.exercise().length() > 12000
        || r.verificationScript() == null
        || r.verificationScript().isBlank()
        || r.verificationScript().length() > 12000)
      throw new IllegalArgumentException("전문 분야, 평가 목적, 격리 과제와 실패 사례를 검사할 검증 스크립트가 필요합니다");
    skill = skills.validate(skill.id);
    if (skill.checks.stream().anyMatch(c -> !c.available()))
      throw new IllegalArgumentException("후보의 형식·도구 조건부터 해결하세요");
    var a = new ExpertiseService.Assessment();
    a.id = id();
    a.projectId = projectId;
    a.agentId = agent.id();
    a.skillId = skill.id;
    a.digest = skill.digest;
    a.scope = r.scope().strip();
    a.purpose = r.purpose();
    Path root = Files.createTempDirectory("studio-assessment-" + a.id + "-").toRealPath();
    Files.writeString(
        root.resolve("README.md"),
        "# Isolated qualification exercise\nNo production files are included.\n");
    Files.writeString(root.resolve("verify.sh"), r.verificationScript());
    Processes.quick(root, "git", "init");
    Processes.quick(root, "git", "add", ".");
    Processes.quick(
        root,
        "git",
        "-c",
        "user.name=Studio assessment",
        "-c",
        "user.email=assessment@studio.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-m",
        "Isolated assessment baseline");
    var p =
        runtime.addProject(
            "평가 · " + a.scope,
            root.toString(),
            "평가용 격리 저장소입니다. 제품 파일 접근 금지. verify.sh 수정 금지. 설치를 실무 자격으로 주장하지 마세요.",
            List.of("bash", "verify.sh"));
    library.archive(p.id(), true);
    var task =
        runtime.assessmentTask(
            a,
            p.id(),
            "실무 자격 평가입니다. 아직 제품 작업 자격이 없습니다. 평가 범위: "
                + a.scope
                + " / "
                + a.purpose
                + "\n과제: "
                + r.exercise()
                + "\n후보 Skill (검증 대상 지침):\n"
                + skill.files.get("SKILL.md"));
    return Map.of("assessment", a, "task", task);
  }

  private ExpertiseService.Assessment own(String project, String id) {
    var a = expertise.get(id);
    if (!a.projectId.equals(project)) throw new IllegalArgumentException("다른 프로젝트의 평가입니다");
    return a;
  }

  @PostMapping("/assessments/{id}/confirm")
  public Object confirm(
      @PathVariable String projectId, @PathVariable String id, @RequestBody Confirm r)
      throws Exception {
    var a = own(projectId, id);
    var t = runtime.task(a.taskId);
    git.artifacts(t, runtime.artifacts(t.id));
    if (!Objects.equals(
        t.reviewDigest,
        GitWorkspaces.patchDigest(runtime.artifacts(t.id).resolve("changes.patch"))))
      throw new IllegalArgumentException("리뷰 이후 평가 결과가 변경되었습니다");
    var result = expertise.confirm(id, r.evidence(), r.days());
    for (var blocked : runtime.tasks())
      if (blocked.projectId.equals(projectId)
          && blocked.status == Status.BLOCKED
          && !blocked.routingPending) runtime.recheck(blocked.id);
    return result;
  }

  @PostMapping("/assessments/{id}/revoke")
  public Object revoke(@PathVariable String projectId, @PathVariable String id) {
    own(projectId, id);
    return expertise.revoke(id);
  }
}
