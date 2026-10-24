package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RuntimeIntegrationTest {
  // These suites exercise execution/workflow behavior; expertise gates have their own integration suite.
  @org.springframework.test.context.bean.override.mockito.MockitoBean ExpertiseService expertise;
  @org.junit.jupiter.api.BeforeEach void eligibleFixture() { org.mockito.Mockito.when(expertise.eligible(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(true); }

  static final Path DATA;

  static {
    try {
      DATA = Files.createTempDirectory("studio-runtime-test-");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void config(DynamicPropertyRegistry r) {
    r.add("studio.data", DATA::toString);
    r.add("spring.datasource.url", () -> "jdbc:h2:mem:runtime-test;DB_CLOSE_DELAY=-1");
  }

  @Autowired RuntimeService runtime;
  @Autowired ExecutionWorker worker;
  @Autowired Store store;
  @Autowired MockMvc mvc;
  @Autowired VerificationSandbox sandbox;
  @TempDir Path temp;

  @AfterEach
  void waitForExecutionCleanup() throws Exception {
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while(!runtime.processing.isEmpty() && System.nanoTime()<deadline) Thread.sleep(50);
    assertThat(runtime.processing).isEmpty();
  }

  @TestConfiguration
  static class Fixtures {
    @Bean
    AgentAdapter fixture() {
      return new AgentAdapter() {
        public String type() {
          return "fixture";
        }

        public List<Requirement> inspect() {
          return List.of(new Requirement("test process", true, "integration fixture"));
        }

        public List<String> command(Task t, Path artifacts) {
          return List.of("/bin/sh", "-c", t.prompt);
        }
      };
    }

    @Bean
    AgentAdapter missing() {
      return new AgentAdapter() {
        public String type() {
          return "missing";
        }

        public List<Requirement> inspect() {
          return List.of(new Requirement("missing CLI", false, "install it"));
        }

        public List<String> command(Task t, Path artifacts) {
          throw new AssertionError("must not execute");
        }
      };
    }
  }

  Project project(List<String> command) throws Exception {
    Path repo = Files.createDirectories(temp.resolve(UUID.randomUUID().toString()));
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
    return runtime.addProject("Fixture", repo.toString(), "Keep tests", command);
  }

  Task create(Project p, String adapter, String prompt, int timeout) throws Exception {
    Agent a = runtime.addAgent("Test worker", "QA", adapter);
    return runtime.create(p.id(), a.id(), "Integration task", prompt, timeout);
  }

  Task await(String id, Status status) throws Exception {
    long until = System.currentTimeMillis() + 20000;
    do {
      Task t = runtime.task(id);
      if (t.status == status) return t;
      if (t.status == Status.FAILED && status != Status.FAILED) throw new AssertionError(t.message);
      Thread.sleep(100);
    } while (System.currentTimeMillis() < until);
    throw new AssertionError(
        "Expected "
            + status
            + ", got "
            + runtime.task(id).status
            + ": "
            + runtime.task(id).message);
  }

  @Test
  void recoveryContinuesExistingWorkspaceAndArchivesFailedInvocation() throws Exception {
    Project p=project(List.of("/bin/sh","-c","test -f completed && test -f partial"));
    Task task=create(p,"fixture","if [ -f partial ]; then echo done > completed; else echo keep > partial; exit 7; fi; exit 0",30);
    runtime.approve(task.id);await(task.id,Status.FAILED);
    waitForExecutionCleanup();
    assertThat(Files.readString(Path.of(task.worktree).resolve("partial"))).contains("keep");
    runtime.recoverInterrupted(task.id);
    Task result=await(task.id,Status.WAITING_APPROVAL);
    assertThat(result.recoveryCount).isEqualTo(1);
    assertThat(result.verification).isEqualTo(Verification.PASSED);
    assertThat(Files.readString(Path.of(task.worktree).resolve("partial"))).contains("keep");
    assertThat(Files.isDirectory(runtime.artifacts(task.id).resolve("invocations/before-1"))).isTrue();
    assertThat(result.status).isNotEqualTo(Status.SUCCEEDED);
    runtime.cancel(task.id);
  }

  @Test
  void liveOrApprovedTaskCannotBeRecovered() throws Exception {
    Task task=create(project(List.of()),"demo","demo",30);
    assertThatThrownBy(()->runtime.recoverInterrupted(task.id)).hasMessageContaining("중단된");
    runtime.cancel(task.id);
  }

  @Test
  void demoRequiresTwoApprovalsAndCreatesRealPatch() throws Exception {
    Project p = project(List.of());
    Task task = create(p, "demo", "demo", 30);
    assertThat(task.status).isEqualTo(Status.WAITING_APPROVAL);
    assertThat(Files.exists(Path.of(task.worktree))).isFalse();
    runtime.approve(task.id);
    Task result = await(task.id, Status.WAITING_APPROVAL);
    assertThat(result.agentExitCode).isZero();
    assertThat(result.verification).isEqualTo(Verification.NOT_RUN);
    assertThat(Files.readString(runtime.artifacts(task.id).resolve("changes.patch")))
        .contains("STUDIO_DEMO.md");
    assertThat(Files.exists(Path.of(p.path()).resolve("STUDIO_DEMO.md"))).isFalse();
    assertThat(runtime.approve(task.id).status).isEqualTo(Status.SUCCEEDED);
    assertThatThrownBy(() -> runtime.approve(task.id)).isInstanceOf(IllegalArgumentException.class);
    // Dirty worktrees must never be force removed.
    assertThatThrownBy(() -> runtime.cleanup(task.id)).isInstanceOf(Exception.class);
  }

  @Test
  void passedTestIsRecordedBeforeFinalApproval() throws Exception {
    Assumptions.assumeTrue(sandbox.available());
    Project p = project(List.of("/bin/sh", "-c", "test -f STUDIO_DEMO.md"));
    Task task = create(p, "demo", "demo", 30);
    runtime.approve(task.id);
    Task result = await(task.id, Status.WAITING_APPROVAL);
    assertThat(result.verification).isEqualTo(Verification.PASSED);
    assertThat(result.testExitCode).isZero();
    runtime.cancel(task.id);
  }

  @Test
  void failedTestNeverSucceeds() throws Exception {
    Assumptions.assumeTrue(sandbox.available());
    Task t = create(project(List.of("/bin/sh", "-c", "exit 17")), "demo", "demo", 30);
    runtime.approve(t.id);
    Task result = await(t.id, Status.FAILED);
    assertThat(result.testExitCode).isEqualTo(17);
    assertThat(result.verification).isEqualTo(Verification.FAILED);
    assertThatThrownBy(() -> runtime.approve(t.id)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nonzeroAgentExitFails() throws Exception {
    Task t = create(project(List.of()), "fixture", "echo failure; exit 9", 30);
    runtime.approve(t.id);
    assertThat(await(t.id, Status.FAILED).agentExitCode).isEqualTo(9);
  }

  @Test
  void timeoutKillsProcessAndRecordsFailure() throws Exception {
    Task t = create(project(List.of()), "fixture", "sleep 30", 10);
    runtime.approve(t.id);
    assertThat(await(t.id, Status.FAILED).message).contains("시간");
  }

  @Test
  void cancelStopsARealProcessAndRemainsCancelled() throws Exception {
    Task t = create(project(List.of()), "fixture", "echo started; sleep 30", 30);
    runtime.approve(t.id);
    await(t.id, Status.RUNNING);
    runtime.cancel(t.id);
    worker.stop(t.id);
    Thread.sleep(700);
    assertThat(runtime.task(t.id).status).isEqualTo(Status.CANCELLED);
  }

  @Test
  void missingCapabilityBlocksExecution() throws Exception {
    Task t = create(project(List.of()), "missing", "do not execute", 30);
    assertThat(t.status).isEqualTo(Status.BLOCKED);
    assertThat(runtime.recheck(t.id).status).isEqualTo(Status.BLOCKED);
    assertThatThrownBy(() -> runtime.approve(t.id)).isInstanceOf(IllegalArgumentException.class);
    runtime.cancel(t.id);
  }

  @Test
  void taskFreezesProjectSettings() throws Exception {
    Project p = project(List.of("echo", "original"));
    Task t = create(p, "demo", "demo", 30);
    runtime.settings(p.id(), "Changed", List.of("echo", "changed"));
    assertThat(runtime.task(t.id).rules).isEqualTo("Keep tests");
    assertThat(runtime.task(t.id).testCommand).containsExactly("echo", "original");
    runtime.cancel(t.id);
  }

  @Test
  void restartMarksInterruptedTasksAsFailed() throws Exception {
    Task t = create(project(List.of()), "demo", "demo", 30);
    Process surviving =
        new ProcessBuilder(
                Processes.python(),
                "-c",
                "import os; os.setsid(); os.execlp('sleep', 'sleep', '30')")
            .start();
    try {
      synchronized (runtime) {
        runtime.approve(t.id);
        runtime.update(t.id, Status.ANALYZING, "prepare", null, null, null);
        runtime.processStarted(t.id, surviving);
        runtime.recover();
      }
      assertThat(runtime.task(t.id).status).isEqualTo(Status.FAILED);
      assertThat(surviving.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    } finally {
      Processes.kill(surviving);
    }
  }

  @Test
  void mutationGuardRejectsUntrustedOriginsAndMissingHeader() throws Exception {
    mvc.perform(post("/api/tasks/any/cancel").contentType("application/json").content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/tasks/any/cancel")
                .header("X-Studio-Client", "1")
                .header("Origin", "https://evil.example")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/health").header("Host", "evil.example"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/health")).andExpect(status().isOk());
  }

  @Test
  void sandboxDeniesWritingOutsideWorkspace() throws Exception {
    Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/sandbox-exec")));
    Path workspace = Files.createDirectory(temp.resolve("sandbox"));
    // Home is outside both the workspace and the allowed temporary directory.
    Path forbidden =
        Path.of(System.getProperty("user.home"), ".studio-sandbox-test-" + UUID.randomUUID());
    var argv =
        sandbox.command(workspace, List.of("/bin/sh", "-c", "echo denied > \"" + forbidden + "\""));
    Process proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
    assertThat(proc.waitFor()).isNotZero();
    assertThat(Files.exists(forbidden)).isFalse();
  }

  @Test
  void timeoutKillsBackgroundChildInProcessGroup() throws Exception {
    Assumptions.assumeTrue(Processes.python() != null);
    Path pidFile = temp.resolve("child.pid");
    try {
      Processes.run(
          List.of("/bin/sh", "-c", "sleep 30 & echo $! > \"" + pidFile + "\"; wait"),
          temp,
          temp.resolve("group.log"),
          java.time.Duration.ofMillis(400),
          p -> {});
    } catch (java.util.concurrent.TimeoutException expected) {
    }
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    Thread.sleep(300);
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
  }

  @Test
  void nonInteractiveProcessReceivesStdinEof() throws Exception {
    Path log = temp.resolve("stdin.log");
    int exit =
        Processes.run(
            List.of("/bin/sh", "-c", "cat >/dev/null; echo stdin-closed"),
            temp,
            log,
            java.time.Duration.ofSeconds(3),
            p -> {});
    assertThat(exit).isZero();
    assertThat(Files.readString(log)).contains("stdin-closed");
  }

  @Test
  void codexCommandEnforcesSandbox() {
    Task t = new Task();
    t.role = "Developer";
    t.rules = "";
    t.prompt = "test";
    t.worktree = "/tmp/test";
    List<String> argv = new CodexAdapter("codex").command(t, temp);
    assertThat(argv)
        .contains(
            "never",
            "--no-daemon",
            "workspace-write",
            "--ignore-user-config",
            "sandbox_workspace_write.network_access=false");
    assertThat(argv).doesNotContain("--dangerously-bypass-approvals-and-sandbox");
  }
}
