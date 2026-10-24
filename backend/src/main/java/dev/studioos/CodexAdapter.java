package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CodexAdapter implements AgentAdapter {
  private final String binary;

  public CodexAdapter(@Value("${studio.codex}") String binary) {
    this.binary = binary;
  }

  public String type() {
    return "codex";
  }

  @Override
  public String steeringCapability(List<String> command) {
    return command.contains("exec")
        ? "Codex exec · 단일 프롬프트와 닫힌 stdin, 도중 전달 미지원 · 같은 worktree에서 중단·재개"
        : AgentAdapter.super.steeringCapability(command);
  }

  public List<Requirement> inspect() {
    try {
      String version = Processes.quick(Path.of("."), binary, "--version");
      String help = Processes.quick(Path.of("."), binary, "exec", "--help");
      String globalHelp = Processes.quick(Path.of("."), binary, "--help");
      boolean flags =
          globalHelp.contains("--no-daemon")
              && help.contains("--ignore-rules")
              && help.contains("--ignore-user-config")
              && help.contains("--json")
              && help.contains("--ephemeral");
      return List.of(
          new Requirement(
              "Codex CLI", flags, version + (flags ? " · CLI 로그인 필요" : " · 지원되지 않는 CLI: 업데이트 필요")));
    } catch (Exception e) {
      return List.of(new Requirement("Codex CLI", false, "codex를 설치하고 codex login을 실행하세요"));
    }
  }

  public List<String> command(Task task, Path artifacts) {
    String prompt =
        "Role: "
            + task.role
            + "\nProject rules:\n"
            + task.rules
            + "\n"
            + task.sharedContext
            + "\nTask:\n"
            + task.prompt
            + "\nApproved capability instructions:\n"
            + task.skillInstructions
            + (task.workflowRunId == null
                ? ""
                : "\nWorkflow step "
                    + task.workflowNodeId
                    + ", attempt "
                    + task.workflowAttempt
                    + ". Approved predecessor changes are already applied in this worktree."
                    + " Preserve them and implement only this step.\n")
            + "\n"
            + "Work only in this worktree. Do not commit, merge, push, install packages, or change"
            + " permissions. Runtime executes project verification and independent review"
            + " separately after you finish. Do not spawn subagents or wait for reviewers. Do not"
            + " ask for the verification command. Report changes and any checks honestly.";
    List<String> args =
        new ArrayList<>(
            List.of(
                binary,
                "--no-daemon",
                "-a",
                "never",
                "exec",
                "--ignore-user-config",
                "--ignore-rules",
                "--ephemeral",
                "--sandbox",
                task.improvementAnalysis ? "read-only" : "workspace-write",
                "-c",
                "sandbox_workspace_write.network_access=false",
                "-c",
                "shell_environment_policy.inherit=core",
                "--json",
                "--color",
                "never",
                "-C",
                task.worktree));
    if (task.model != null)
      args.addAll(
          List.of(
              "--model", task.model, "-c", "model_reasoning_effort=\"" + task.reasoning + "\""));
    if (task.improvementAnalysis)
      args.addAll(
          List.of(
              "--output-schema",
              artifacts.resolve("proposal-schema.json").toString(),
              "--output-last-message",
              artifacts.resolve("proposals.json").toString()));
    args.add(prompt);
    return args;
  }
}
