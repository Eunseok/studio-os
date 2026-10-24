package dev.studioos;

import static dev.studioos.Domain.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** An actual read-only model call chooses among executable, configured candidates. */
@Service
public class ModelPlanner {
  public record Choice(
      String provider,
      String model,
      String reasoning,
      String reason,
      String confidence,
      List<String> evidence) {}

  public record Decision(
      String id,
      String createdAt,
      String projectId,
      String purpose,
      List<Map<String, Object>> candidates,
      Choice choice,
      String selectorProvider,
      String selectorModel) {}

  private final RuntimeService runtime;
  private final ModelRouter models;
  private final Store store;
  private final ObjectMapper json;

  public ModelPlanner(RuntimeService runtime, ModelRouter models, Store store, ObjectMapper json) {
    this.runtime = runtime;
    this.models = models;
    this.store = store;
    this.json = json;
  }

  public Decision choose(Project project, String purpose, String request) throws Exception {
    return choose(project, purpose, request, null, null);
  }

  public Decision choose(
      Project project, String purpose, String request, String preferOtherThan, Task previous)
      throws Exception {
    return choose(project, purpose, request, preferOtherThan, previous, p -> {});
  }

  public Decision choose(Project project, String purpose, String request, Consumer<Process> onStart)
      throws Exception {
    return choose(project, purpose, request, null, null, onStart);
  }

  public Decision choose(
      Project project,
      String purpose,
      String request,
      String preferOtherThan,
      Task previous,
      Consumer<Process> onStart)
      throws Exception {
    return choose(
        project, purpose, request, preferOtherThan, previous, onStart, Set.of("codex", "claude"));
  }

  public Decision choose(
      Project project,
      String purpose,
      String request,
      String preferOtherThan,
      Task previous,
      Consumer<Process> onStart,
      Set<String> allowedProviders)
      throws Exception {
    List<Map<String, Object>> candidates = new ArrayList<>();
    for (String provider : List.of("codex", "claude")) {
      if (!allowedProviders.contains(provider)) continue;
      if (runtime.adapter(provider).inspect().stream().anyMatch(r -> !r.available())) continue;
      for (String model : models.models(provider)) {
        var history =
            runtime.tasks().stream()
                .filter(
                    t ->
                        provider.equals(t.adapter)
                            && model.equals(t.model)
                            && RuntimeService.terminal(t.status))
                .toList();
        long passed =
            history.stream()
                .filter(t -> t.status == Status.SUCCEEDED && t.verification == Verification.PASSED)
                .count();
        double seconds =
            history.stream()
                .filter(t -> t.startedAt != null && t.finishedAt != null)
                .mapToLong(
                    t ->
                        Duration.between(
                                java.time.Instant.parse(t.startedAt),
                                java.time.Instant.parse(t.finishedAt))
                            .toSeconds())
                .average()
                .orElse(0);
        candidates.add(
            Map.of(
                "provider",
                provider,
                "model",
                model,
                "reasoningOptions",
                List.of("low", "medium", "high"),
                "tools",
                provider.equals("codex")
                    ? "sandboxed shell and file tools; analysis is read-only"
                    : "restricted file Read/Glob/Grep; implementation also Edit/Write; no agent"
                        + " shell",
                "observedRuns",
                history.size(),
                "verifiedApproved",
                passed,
                "failed",
                history.stream().filter(t -> t.status == Status.FAILED).count(),
                "averageSeconds",
                seconds,
                "projectObservedRuns",
                history.stream().filter(t -> t.projectId.equals(project.id())).count()));
      }
    }
    if (candidates.isEmpty()) throw new IllegalArgumentException("모델 선택에 사용할 준비된 CLI가 없습니다");
    if (preferOtherThan != null
        && candidates.stream().anyMatch(c -> !preferOtherThan.equals(c.get("provider"))))
      candidates.removeIf(c -> preferOtherThan.equals(c.get("provider")));
    String decisionId = id();
    Path dir = Files.createDirectories(runtime.data.resolve("routing").resolve(decisionId));
    String selector =
        candidates.stream().anyMatch(c -> c.get("provider").equals("codex")) ? "codex" : "claude";
    Task selectorTask = new Task();
    selectorTask.id = decisionId;
    selectorTask.adapter = selector;
    selectorTask.model = models.models(selector).getFirst();
    selectorTask.reasoning = "low";
    selectorTask.improvementAnalysis = true;
    selectorTask.worktree = project.path();
    selectorTask.role = "Model selection planner";
    selectorTask.rules =
        "Read-only inspection. Return the requested routing JSON. Never modify files or run tests.";
    selectorTask.prompt =
        "Select the best-fit execution provider, exact model and reasoning level for this specific"
            + " task. Do NOT follow a fixed provider-to-role preference. Inspect relevant"
            + " repository files read-only when useful. Consider task scope, tool requirements,"
            + " uncertainty, risk, and observed outcomes/latency. History is observational and may"
            + " concern different tasks; do not treat absent history as failure. Cost and"
            + " comparative benchmarks are not available; do not invent prices or performance."
            + " Prefer the least excessive reasoning sufficient for the task. Only select a"
            + " supplied provider/model pair and low/medium/high. Confidence must reflect evidence"
            + " limitations. Explain the decision and trade-offs in Korean with concrete evidence."
            + " Model aliases can move upstream.\n"
            + "Purpose: "
            + purpose
            + "\nTask: "
            + request
            + "\nProject verification command: "
            + json.writeValueAsString(project.testCommand())
            + "\nEligible candidates and observed history:\n"
            + json.writeValueAsString(candidates);
    var props = new LinkedHashMap<String, Object>();
    for (String key : List.of("provider", "model", "reasoning", "reason", "confidence"))
      props.put(key, Map.of("type", "string"));
    props.put("provider", Map.of("type", "string", "enum", List.of("codex", "claude")));
    props.put(
        "model",
        Map.of(
            "type",
            "string",
            "enum",
            candidates.stream().map(c -> c.get("model")).distinct().toList()));
    props.put("reasoning", Map.of("type", "string", "enum", List.of("low", "medium", "high")));
    props.put("confidence", Map.of("type", "string", "enum", List.of("low", "medium", "high")));
    props.put("evidence", Map.of("type", "array", "items", Map.of("type", "string")));
    Files.writeString(
        dir.resolve("proposal-schema.json"),
        json.writeValueAsString(
            Map.of(
                "type",
                "object",
                "additionalProperties",
                false,
                "properties",
                props,
                "required",
                new ArrayList<>(props.keySet()))));
    int exit =
        Processes.run(
            runtime.adapter(selector).command(selectorTask, dir),
            Path.of(project.path()),
            dir.resolve("selector.log"),
            Duration.ofSeconds(90),
            onStart);
    if (exit != 0)
      throw new IllegalArgumentException(
          "AI 모델 선택 실패 (" + decisionId + "). 기본값으로 실행하지 않았습니다. 다시 시도하거나 도구를 명시하세요");
    if (selector.equals("claude")) {
      var response = json.readTree(Files.readString(dir.resolve("selector.log")));
      if (response.path("is_error").asBoolean(true)
          || !"success".equals(response.path("subtype").asText()))
        throw new IllegalArgumentException("Claude 모델 선택 응답이 실패했습니다");
      Files.writeString(
          dir.resolve("proposals.json"),
          json.writeValueAsString(response.get("structured_output")));
    }
    Path output = dir.resolve("proposals.json");
    if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) > 32768)
      throw new IllegalArgumentException("모델 선택 결과가 없거나 너무 큽니다");
    Choice choice =
        json.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue(Files.readString(output), Choice.class);
    final Choice validated = choice;
    if (choice == null
        || candidates.stream()
            .noneMatch(
                c ->
                    c.get("provider").equals(validated.provider)
                        && c.get("model").equals(validated.model))
        || !Set.of("low", "medium", "high").contains(Objects.toString(choice.reasoning, ""))
        || !Set.of("low", "medium", "high").contains(Objects.toString(choice.confidence, ""))
        || choice.reason == null
        || choice.reason.isBlank()
        || choice.reason.length() > 3000
        || choice.evidence == null
        || choice.evidence.isEmpty()
        || choice.evidence.size() > 10
        || choice.evidence.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 1500))
      throw new IllegalArgumentException("AI가 허용된 모델 선택 규격을 지키지 않았습니다. 실행하지 않았습니다");
    if (previous != null
        && previous.failure != null
        && previous.failure.adapt()
        && choice.provider().equals(previous.adapter)
        && choice.model().equals(previous.model)) {
      var levels = List.of("low", "medium", "high");
      int floor = Math.min(2, levels.indexOf(previous.reasoning) + 1);
      if (levels.indexOf(choice.reasoning()) < floor)
        choice =
            new Choice(
                choice.provider(),
                choice.model(),
                levels.get(floor),
                choice.reason() + " · 재시도 정책: 동일 모델의 검증/리뷰 실패 시 추론을 한 단계 높임 (high 상한)",
                choice.confidence(),
                choice.evidence());
    }
    Decision decision =
        new Decision(
            decisionId,
            now(),
            project.id(),
            purpose,
            List.copyOf(candidates),
            choice,
            selector,
            selectorTask.model);
    store.save(
        "model-decision",
        decisionId,
        decision,
        "model.selected",
        null,
        Map.of(
            "projectId",
            project.id(),
            "provider",
            choice.provider,
            "model",
            choice.model,
            "reasoning",
            choice.reasoning));
    return decision;
  }
}
