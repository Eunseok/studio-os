package dev.studioos;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{id}/github")
public class GithubController {
  private final GithubService service;

  public GithubController(GithubService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> board(@PathVariable String id) {
    return service.board(id);
  }

  @PostMapping
  public GithubService.Binding connect(
      @PathVariable String id, @RequestBody GithubService.Connect body) throws Exception {
    return service.connect(id, body);
  }

  @PostMapping("/sync")
  public Map<String, String> sync() {
    service.requestSync();
    return Map.of("message", "GitHub 동기화 중");
  }

  public record Action(String workId, String head) {}

  @PostMapping("/pr")
  public GithubService.Work pr(@PathVariable String id, @RequestBody Action action)
      throws Exception {
    return service.publish(id, action.workId());
  }

  @PostMapping("/merge")
  public GithubService.Work merge(@PathVariable String id, @RequestBody Action action)
      throws Exception {
    return service.merge(id, action.workId(), action.head());
  }

  @PostMapping("/pull")
  public Map<String, String> pull(@PathVariable String id) throws Exception {
    return service.pull(id);
  }
}
