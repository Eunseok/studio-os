package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DemoAdapter implements AgentAdapter {
  public String type() {
    return "demo";
  }

  public List<Requirement> inspect() {
    return List.of(new Requirement("내장 데모", true, "AI 호출 없이 고정된 샘플 파일을 생성합니다"));
  }

  public List<String> command(Task task, Path artifacts) {
    return List.of();
  }

  public void simulate(Task task, Path artifacts) throws Exception {
    Files.writeString(
        artifacts.resolve("agent.jsonl"),
        "{\"type\":\"demo.started\",\"message\":\"Deterministic fixture, no AI used\"}\n");
    Thread.sleep(600);
    Files.writeString(
        Path.of(task.worktree).resolve("STUDIO_DEMO.md"),
        "# Studio OS demo\n\n"
            + "This file was created by the deterministic demo adapter. No AI model was called.\n");
    Files.writeString(
        artifacts.resolve("agent.jsonl"),
        "{\"type\":\"demo.completed\"}\n",
        StandardOpenOption.APPEND);
  }
}
