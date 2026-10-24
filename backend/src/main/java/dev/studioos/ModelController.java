package dev.studioos;

import static dev.studioos.Domain.*;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
public class ModelController {
  private final ModelRouter models;
  private final RuntimeService runtime;
  private final Store store;

  public ModelController(ModelRouter models, RuntimeService runtime, Store store) {
    this.models = models;
    this.runtime = runtime;
    this.store = store;
  }

  @GetMapping
  public List<Map<String, Object>> registry() {
    List<Map<String, Object>> result = new ArrayList<>();
    for (String provider : List.of("codex", "claude"))
      for (String model : models.models(provider))
        result.add(
            Map.of(
                "provider",
                provider,
                "model",
                model,
                "reasoningOptions",
                List.of("low", "medium", "high"),
                "cliReady",
                runtime.adapter(provider).inspect().stream().allMatch(Requirement::available),
                "modelAccess",
                "실행 시 확인",
                "usageRemaining",
                "unknown",
                "price",
                "unknown"));
    return result;
  }

  @GetMapping("/decisions/{id}")
  public ModelPlanner.Decision decision(@PathVariable String id) {
    return store.get("model-decision", id, ModelPlanner.Decision.class);
  }

  public record Manual(String provider, String model, String reasoning) {}

  @PostMapping("/tasks/{id}")
  public Task manual(@PathVariable String id, @RequestBody Manual r) {
    return runtime.manualModel(id, r.provider(), r.model(), r.reasoning());
  }
}
