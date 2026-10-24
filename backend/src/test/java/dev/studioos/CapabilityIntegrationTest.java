package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CapabilityIntegrationTest {
  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("studio-academy-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("studio.data", DATA::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:academy-test;DB_CLOSE_DELAY=-1");
  }

  @Autowired CapabilityService skills;
  @Autowired RuntimeService runtime;
  @Autowired Store store;
  @TempDir Path temp;

  String content(String name, String body) {
    return "---\nname: "
        + name
        + "\ndescription: Test skill\nversion: 1.0.0\nrequired-tools: [git]\n---\n"
        + body
        + "\n";
  }

  Project project() throws Exception {
    Path repo = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
    Processes.quick(repo, "git", "init");
    Files.writeString(repo.resolve("README.md"), "# Fixture\n");
    Processes.quick(repo, "git", "add", "README.md");
    Processes.quick(
        repo,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.invalid",
        "-c",
        "core.hooksPath=/dev/null",
        "commit",
        "-m",
        "fixture");
    return runtime.addProject("Academy fixture", repo.toString(), "Keep original files", List.of());
  }

  CapabilityService.Skill active(String scope, String name) throws Exception {
    var s = skills.register(scope, null, content(name, "Review the README."));
    skills.validate(s.id);
    return skills.activate(s.id);
  }

  @Test
  void missingSkillResumesSameTaskWithTwoApprovalsAndFrozenArtifacts() throws Exception {
    var p = project();
    var task =
        runtime.create(p.id(), "demo-developer", "Skill gate", "demo", 30, List.of("readme-guide"));
    assertThat(task.status).isEqualTo(Status.BLOCKED);
    assertThat(task.requirements)
        .anyMatch(r -> r.name().equals("Skill: readme-guide") && !r.available());
    var skill = skills.register(p.id(), null, content("readme-guide", "Review README."));
    assertThat(runtime.recheck(task.id).status).isEqualTo(Status.BLOCKED);
    assertThatThrownBy(() -> skills.activate(skill.id))
        .isInstanceOf(IllegalArgumentException.class);
    skills.validate(skill.id);
    assertThat(runtime.recheck(task.id).status).isEqualTo(Status.BLOCKED);
    skills.activate(skill.id);
    assertThat(runtime.task(task.id).status).isEqualTo(Status.BLOCKED);
    var ready = runtime.recheck(task.id);
    assertThat(ready.status).isEqualTo(Status.WAITING_APPROVAL);
    assertThat(ready.skills.getFirst().digest()).isEqualTo(skill.digest);
    assertThat(runtime.approve(task.id).status).isEqualTo(Status.QUEUED);
    long until = System.currentTimeMillis() + 15000;
    while ((runtime.task(task.id).agentExitCode == null
            || runtime.task(task.id).status != Status.WAITING_APPROVAL)
        && System.currentTimeMillis() < until) Thread.sleep(100);
    var result = runtime.task(task.id);
    assertThat(result.status).isEqualTo(Status.WAITING_APPROVAL);
    assertThat(result.agentExitCode).isZero();
    assertThat(Files.readString(runtime.artifacts(task.id).resolve("skills/readme-guide/SKILL.md")))
        .isEqualTo(skill.files.get("SKILL.md"));
    assertThat(Files.readString(runtime.artifacts(task.id).resolve("skills.json")))
        .contains(skill.digest);
    assertThat(runtime.approve(task.id).status).isEqualTo(Status.SUCCEEDED);
    assertThat(Processes.quick(Path.of(p.path()), "git", "status", "--porcelain")).isEmpty();
    assertThat(store.events(0)).anyMatch(e -> e.type().equals("skill.activated"));
  }

  @Test
  void scanIsReadOnlyDeduplicatedAndKeepsSourceSnapshot() throws Exception {
    var p = project();
    Path root = Files.createDirectories(Path.of(p.path()).resolve(".agents/skills/readme-guide"));
    Files.createDirectories(root.resolve("references"));
    Files.writeString(root.resolve("references/example.md"), "Example instructions");
    String text = content("readme-guide", "Read [example](references/example.md).");
    Files.writeString(root.resolve("SKILL.md"), text);
    var scan = skills.scan(p.id(), false);
    assertThat(scan.issues()).isEmpty();
    assertThat(scan.skills()).hasSize(1);
    var first = scan.skills().getFirst();
    assertThat(first.status).isEqualTo("DISCOVERED");
    assertThat(skills.scan(p.id(), false).skills().getFirst().id).isEqualTo(first.id);
    skills.validate(first.id);
    skills.activate(first.id);
    Files.writeString(root.resolve("SKILL.md"), text + "Changed after registration.\n");
    assertThat(skills.get(first.id).files.get("SKILL.md")).isEqualTo(text);
    var second = skills.scan(p.id(), false).skills().getFirst();
    assertThat(second.id).isNotEqualTo(first.id);
    assertThat(skills.get(first.id).status).isEqualTo("ACTIVE");
    assertThat(Files.readString(root.resolve("references/example.md")))
        .isEqualTo("Example instructions");
  }

  @Test
  void rejectsMalformedReferencesDependenciesAndLinks() throws Exception {
    for (String text :
        List.of(
            "not a skill",
            content("bad-name", "Read [escape](../outside.md)."),
            content("bad-name", "Read `scripts/missing.py`."),
            content("bad-name", "Do work").replace("[git]", "[studio-missing-cli-948372]"),
            content("bad-name", "Do work")
                .replace("name: bad-name", "name: bad-name\nname: duplicate"))) {
      var s = skills.register(null, null, text);
      var validated = skills.validate(s.id);
      assertThat(validated.checks).anyMatch(r -> !r.available());
      assertThatThrownBy(() -> skills.activate(s.id)).isInstanceOf(IllegalArgumentException.class);
    }
    Path root = Files.createDirectory(temp.resolve("symlink-skill"));
    Files.writeString(root.resolve("SKILL.md"), content("link-skill", "Do work"));
    Files.createSymbolicLink(root.resolve("outside.md"), temp);
    assertThatThrownBy(() -> skills.register(null, root.toString(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("심볼릭");
  }

  @Test
  void replacingActiveRevisionBlocksOldPlanUntilExplicitRecheck() throws Exception {
    var p = project();
    var first = active(p.id(), "versioned-skill");
    var task =
        runtime.create(p.id(), "demo-developer", "Version gate", "demo", 30, List.of(first.name));
    var second =
        skills.register(
            p.id(), null, content(first.name, "Different instructions").replace("1.0.0", "2.0.0"));
    skills.validate(second.id);
    skills.activate(second.id);
    assertThat(skills.get(first.id).status).isEqualTo("DISABLED");
    assertThat(runtime.approve(task.id).status).isEqualTo(Status.BLOCKED);
    assertThat(runtime.task(task.id).skills.getFirst().id()).isEqualTo(first.id);
    var updated = runtime.recheck(task.id);
    assertThat(updated.status).isEqualTo(Status.WAITING_APPROVAL);
    assertThat(updated.skills.getFirst().id()).isEqualTo(second.id);
    assertThat(updated.approvedAt).isNull();
    runtime.cancel(task.id);
  }

  @Test
  void projectScopeShadowsGlobalAndCannotLeakAcrossProjects() throws Exception {
    var p = project();
    var other = project();
    var local = active(p.id(), "scope-skill");
    assertThat(skills.resolve(other.id(), List.of(local.name))).isEmpty();
    var global = active(null, "scope-skill");
    assertThat(skills.resolve(p.id(), List.of(local.name)).getFirst().id()).isEqualTo(local.id);
    assertThat(skills.resolve(other.id(), List.of(local.name)).getFirst().id())
        .isEqualTo(global.id);
    skills.disable(local.id);
    assertThat(skills.resolve(p.id(), List.of(local.name))).isEmpty();
  }

  @Test
  void codexReceivesApprovedPackagePathAndPersistedBinding() throws Exception {
    var p = project();
    var skill = active(p.id(), "prompt-skill");
    var task =
        runtime.create(
            p.id(), "demo-developer", "Prompt gate", "Update README", 30, List.of(skill.name));
    Path out = Files.createDirectory(temp.resolve("artifacts"));
    task.skillInstructions = runtime.prepareSkills(task, out);
    var command = new CodexAdapter("codex").command(task, out);
    assertThat(command.getLast())
        .contains(out.resolve("skills/prompt-skill").toString(), "Read SKILL.md", skill.digest);
    assertThat(store.get("task", task.id, Task.class).skills.getFirst().digest())
        .isEqualTo(skill.digest);
    runtime.cancel(task.id);
  }
}
