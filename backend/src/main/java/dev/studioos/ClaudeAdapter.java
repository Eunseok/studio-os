package dev.studioos;

import static dev.studioos.Domain.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClaudeAdapter implements AgentAdapter {
  private final String binary;
  private long checked;
  private List<Requirement> cached = List.of();

  public ClaudeAdapter(@Value("${studio.claude:claude}") String binary) {
    this.binary = binary;
  }

  public String type() {
    return "claude";
  }

  public synchronized List<Requirement> inspect() {
    if (System.currentTimeMillis() - checked < 30000) return cached;
    try {
      String help = Processes.quick(Path.of("."), binary, "--help");
      boolean flags =
          help.contains("--restricted")
              && help.contains("--safe-mode")
              && help.contains("--json-schema")
              && help.contains("--effort");
      boolean auth =
          new ObjectMapper()
              .readTree(Processes.quick(Path.of("."), binary, "auth", "status"))
              .path("loggedIn")
              .asBoolean();
      cached =
          List.of(
              new Requirement("Claude CLI", flags, "restricted / safe-mode / JSON 출력 지원"),
              new Requirement("Claude 로그인", auth, "claude auth login으로 인증"));
    } catch (Exception e) {
      cached = List.of(new Requirement("Claude CLI", false, "Claude CLI 설치·로그인·지원 플래그를 확인하세요"));
    }
    checked = System.currentTimeMillis();
    return cached;
  }

  public List<String> command(Task t, Path artifacts) {
    List<String> args =
        new ArrayList<>(
            List.of(
                binary,
                "--print",
                "--restricted",
                "--safe-mode",
                "--permission-mode",
                "dontAsk",
                "--strict-mcp-config",
                "--mcp-config",
                "{\"mcpServers\":{}}",
                "--no-session-persistence",
                "--output-format",
                "json",
                "--model",
                t.model == null ? "sonnet" : t.model,
                "--effort",
                t.reasoning == null ? "medium" : t.reasoning,
                "--tools",
                t.improvementAnalysis ? "Read,Glob,Grep" : "Read,Glob,Grep,Edit,Write",
                "--allowedTools",
                t.improvementAnalysis ? "Read,Glob,Grep" : "Read,Glob,Grep,Edit,Write"));
    if (t.improvementAnalysis) {
      String schema = ImprovementPayload.schema();
      try {
        if (Files.isRegularFile(artifacts.resolve("proposal-schema.json")))
          schema = Files.readString(artifacts.resolve("proposal-schema.json"));
      } catch (Exception e) {
        throw new IllegalArgumentException("출력 스키마를 읽을 수 없습니다", e);
      }
      args.addAll(List.of("--json-schema", schema));
    }
    args.add("--");
    args.add(
        "Role: "
            + t.role
            + "\nProject rules: "
            + t.rules
            + "\n"
            + t.sharedContext
            + "\nTask: "
            + t.prompt
            + "\nApproved skills: "
            + t.skillInstructions
            + "\n"
            + "Work only in the current worktree. Do not commit, install packages or change"
            + " permissions. Runtime runs verification and independent review separately. Do not"
            + " spawn subagents or wait for reviewers. Report honestly.");
    return args;
  }
}
