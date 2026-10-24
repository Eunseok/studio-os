package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ExpertiseIntegrationTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("expertise-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("studio.data", DATA::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:expertise-test;DB_CLOSE_DELAY=-1");
  }

  @Autowired RuntimeService runtime;
  @Autowired ExpertiseService expertise;
  @Autowired ExpertiseController controller;
  @Autowired CapabilityService skills;
  @Autowired Store store;
  @TempDir Path temp;

  Project project() throws Exception {
    Path p = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
    Processes.quick(p, "git", "init");
    Files.writeString(p.resolve("README.md"), "fixture");
    Processes.quick(p, "git", "add", ".");
    Processes.quick(
        p,
        "git",
        "-c",
        "user.name=Fixture",
        "-c",
        "user.email=test@example.invalid",
        "commit",
        "-m",
        "fixture");
    return runtime.addProject("Fixture", p.toString(), "", List.of("true"));
  }

  CapabilityService.Skill skill(String project) throws Exception {
    var s =
        skills.register(
            project,
            null,
            "---\n"
                + "name: expert-fixture\n"
                + "description: Qualification fixture\n"
                + "---\n"
                + "Use actual evidence.\n");
    return skills.validate(s.id);
  }

  ExpertiseService.Assessment grant(
      Project p, String agent, String purpose, CapabilityService.Skill s) {
    var a = new ExpertiseService.Assessment();
    a.id = id();
    a.projectId = p.id();
    a.agentId = agent;
    a.scope = "backend";
    a.purpose = purpose;
    a.skillId = s.id;
    a.digest = s.digest;
    a.status = "QUALIFIED";
    a.expiresAt = Instant.now().plusSeconds(3600).toString();
    expertise.save(a);
    return a;
  }

  @Test
  void installationAndTitlesDoNotGrantExecutionAndMissingScopeFailsClosed() throws Exception {
    var p = project();
    var a = runtime.addAgent("Senior backend expert", "Specialist", "codex");
    var s = skill(p.id());
    skills.activate(s.id);
    var t = runtime.create(p.id(), a.id(), "task", "implement", 30, List.of());
    assertThat(expertise.requirements(t))
        .anyMatch(r -> !r.available() && r.name().equals("업무 전문성 범위"));
    expertise.policy(p.id(), List.of("backend"));
    assertThat(expertise.requirements(t))
        .anyMatch(r -> !r.available() && r.name().equals("전문성: backend"));
    assertThatThrownBy(() -> runtime.approve(t.id)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void distinctReviewerExpirationRevocationAndNewScopeAreEnforced() throws Exception {
    var p = project();
    var s = skill(p.id());
    var a = runtime.addAgent("Developer", "Developer", "codex");
    var reviewer = runtime.addAgent("Reviewer", "Reviewer", "claude");
    expertise.policy(p.id(), List.of("backend"));
    var t = runtime.create(p.id(), a.id(), "task", "implement", 30, List.of());
    var g = grant(p, a.id(), "IMPLEMENT", s);
    grant(p, a.id(), "REVIEW", s);
    assertThat(expertise.requirements(t))
        .anyMatch(r -> r.name().equals("독립 리뷰 전문성") && !r.available());
    grant(p, reviewer.id(), "REVIEW", s);
    assertThat(expertise.requirements(t)).allMatch(Requirement::available);
    g.expiresAt = Instant.now().minusSeconds(1).toString();
    expertise.save(g);
    assertThat(expertise.requirements(t)).anyMatch(r -> !r.available());
    g.expiresAt = Instant.now().plusSeconds(3600).toString();
    expertise.save(g);
    expertise.revoke(g.id);
    assertThat(expertise.requirements(t)).anyMatch(r -> !r.available());
    grant(p, a.id(), "IMPLEMENT", s);
    expertise.policy(p.id(), List.of("backend", "unity"));
    assertThat(expertise.requirements(t))
        .anyMatch(r -> r.name().equals("전문성: unity") && !r.available());
  }

  @Test
  void assessmentIsIsolatedCannotBeAppliedOrConfirmedBeforeEvidence() throws Exception {
    var p = project();
    var s = skill(p.id());
    var a = runtime.addAgent("Candidate", "Unverified", "codex");
    controller.create(
        p.id(),
        new ExpertiseController.Candidate(
            a.id(),
            s.id,
            "backend",
            "IMPLEMENT",
            "Implement a small isolated function and test failure cases.",
            "set -eu\ntest -f result.txt\n",
            "# Seeded defect packet\n"));
    var assessment =
        expertise.all().stream().filter(x -> x.projectId.equals(p.id())).findFirst().orElseThrow();
    var t = runtime.task(assessment.taskId);
    assertThat(t.projectId).isNotEqualTo(p.id());
    assertThat(Path.of(runtime.project(t.projectId).path()).getFileName().toString())
        .startsWith("studio-assessment-");
    assertThat(Files.readString(Path.of(runtime.project(t.projectId).path()).resolve("README.md")))
        .isEqualTo("# Seeded defect packet\n");
    assertThat(expertise.assessment(t)).isTrue();
    assertThat(expertise.requirements(t)).isEmpty();
    assertThatThrownBy(() -> runtime.applyToProject(t.id)).hasMessageContaining("평가용");
    assertThatThrownBy(
            () ->
                expertise.confirm(
                    assessment.id, "Tests and failure cases were checked by a human evaluator", 30))
        .hasMessageContaining("통과");
    var forged = new Task();
    forged.id = "another-task";
    forged.assessmentId = assessment.id;
    assertThat(expertise.assessment(forged)).isFalse();
    assertThat(Files.readString(Path.of(p.path()).resolve("README.md"))).isEqualTo("fixture");
  }

  @Test
  void automaticRecheckSelectsQualifiedAgentWithoutGrantingGenericWorkerRights() throws Exception {
    var p = project();
    var s = skill(p.id());
    expertise.policy(p.id(), List.of("backend"));
    var a = runtime.addAgent("Qualified", "Backend", "codex");
    var r = runtime.addAgent("Review", "Review", "claude");
    var t = runtime.create(p.id(), "codex-developer", "task", "implement", 30, List.of());
    t.autoRouting = true;
    t.routingPending = false;
    store.save("task", t.id, t, "fixture", t.id, Map.of());
    grant(p, a.id(), "IMPLEMENT", s);
    grant(p, r.id(), "REVIEW", s);
    var checked = runtime.recheck(t.id);
    assertThat(checked.agentId).isEqualTo(a.id());
    assertThat(expertise.requirements(checked)).allMatch(Requirement::available);
    assertThat(expertise.qualified(p.id(), "codex-developer", "backend", "IMPLEMENT")).isFalse();
  }
}
