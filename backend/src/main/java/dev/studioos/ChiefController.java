package dev.studioos;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chief")
public class ChiefController {
  private final ChiefService chief;

  public ChiefController(ChiefService chief) {
    this.chief = chief;
  }

  public record Send(String conversationId, String projectId, String message, String requestKey) {}

  @GetMapping
  public Object list() {
    return chief.all();
  }

  @PostMapping
  public Object send(@RequestBody Send r) throws Exception {
    return chief.send(r.conversationId(), r.projectId(), r.message(), r.requestKey());
  }

  @PostMapping("/{id}/start")
  public Object start(@PathVariable String id) throws Exception {
    return chief.start(id);
  }

  @PostMapping("/{id}/approve")
  public Object approve(@PathVariable String id) {
    return chief.approve(id);
  }

  @PostMapping("/{id}/stop")
  public Object stop(@PathVariable String id) {
    return chief.stop(id);
  }
}
