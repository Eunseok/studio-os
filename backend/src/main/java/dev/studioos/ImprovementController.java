package dev.studioos;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/improvements")
public class ImprovementController {
  private final ImprovementService service;

  public ImprovementController(ImprovementService service) {
    this.service = service;
  }

  public record Create(String projectId, String feedback, String provider, String requestKey) {}

  public record Select(String proposalId, String provider) {}

  public record Retry(String expectedTaskId, String provider) {}

  @GetMapping
  public List<ImprovementService.Idea> all() {
    return service.all();
  }

  @PostMapping
  public ImprovementService.Idea create(@RequestBody Create r) throws Exception {
    return service.create(r.projectId(), r.feedback(), r.provider(), r.requestKey());
  }

  @PostMapping("/{id}/select")
  public ImprovementService.Idea select(@PathVariable String id, @RequestBody Select r)
      throws Exception {
    return service.select(id, r.proposalId(), r.provider());
  }

  @PostMapping("/{id}/retry")
  public ImprovementService.Idea retry(@PathVariable String id, @RequestBody Retry r)
      throws Exception {
    return service.retry(id, r.expectedTaskId(), r.provider());
  }
}
