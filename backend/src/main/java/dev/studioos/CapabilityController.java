package dev.studioos;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/skills")
public class CapabilityController {
  private final CapabilityService skills;

  public CapabilityController(CapabilityService skills) {
    this.skills = skills;
  }

  public record Register(String projectId, String path, String content) {}

  public record Scan(String projectId, boolean includeGlobal) {}

  @GetMapping
  public List<Map<String, Object>> all() {
    return skills.summaries();
  }

  @GetMapping("/{id}")
  public CapabilityService.Skill get(@PathVariable String id) {
    return skills.get(id);
  }

  @PostMapping
  public CapabilityService.Skill register(@RequestBody Register r) throws Exception {
    return skills.register(r.projectId(), r.path(), r.content());
  }

  @PostMapping("/scan")
  public Map<String, Object> scan(@RequestBody Scan r) throws Exception {
    var result = skills.scan(r.projectId(), r.includeGlobal());
    return Map.of(
        "skills",
        result.skills().stream()
            .map(s -> Map.of("id", s.id, "name", s.name, "status", s.status))
            .toList(),
        "issues",
        result.issues());
  }

  @PostMapping("/{id}/validate")
  public CapabilityService.Skill validate(@PathVariable String id) {
    return skills.validate(id);
  }

  @PostMapping("/{id}/activate")
  public CapabilityService.Skill activate(@PathVariable String id) {
    return skills.activate(id);
  }

  @PostMapping("/{id}/disable")
  public CapabilityService.Skill disable(@PathVariable String id) {
    return skills.disable(id);
  }
}
