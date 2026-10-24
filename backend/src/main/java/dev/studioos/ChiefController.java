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

  @GetMapping("/{id}/hiring")
  public Object hiring(@PathVariable String id) {
    var c = chief.get(id);
    return c.hiringId == null || chief.hiring == null
        ? java.util.Map.of("state", "NONE")
        : chief.hiring.get(c.hiringId);
  }

  @PostMapping
  public Object send(@RequestBody Send r) throws Exception {
    return chief.send(r.conversationId(), r.projectId(), r.message(), r.requestKey());
  }

  public record Answer(String questionId, String answer) {}

  @PostMapping("/{id}/answer")
  public Object answer(@PathVariable String id, @RequestBody Answer r) {
    return chief.answer(id, r.questionId(), r.answer());
  }

  @PostMapping("/{id}/start")
  public Object start(@PathVariable String id) throws Exception {
    return chief.start(id);
  }

  @PostMapping("/{id}/approve")
  public Object approve(@PathVariable String id) {
    return chief.approve(id);
  }

  @PostMapping("/{id}/replan")
  public Object replan(@PathVariable String id) {
    return chief.replan(id);
  }

  @PostMapping("/{id}/stop")
  public Object stop(@PathVariable String id, @RequestBody(required = false) Stop r) {
    return chief.stop(id, r == null ? null : r.requestKey());
  }

  @PostMapping("/{id}/recover")
  public Object recover(@PathVariable String id) throws Exception {
    return chief.recoverInterrupted(id);
  }

  @PostMapping("/recover-run/{runId}")
  public Object recoverRun(@PathVariable String runId) throws Exception {
    return chief.recoverRun(runId);
  }

  public record Stop(String requestKey) {}

  @PostMapping("/{id}/instructions/approve")
  public Object approveInstruction(@PathVariable String id, @RequestBody Stop r) {
    return chief.approveInstruction(id, r.requestKey());
  }

  public record Retry(String expectedRequestKey, String requestKey) {}

  @PostMapping("/{id}/retry")
  public Object retry(@PathVariable String id, @RequestBody Retry r) {
    return chief.retry(id, r.expectedRequestKey(), r.requestKey());
  }
}
