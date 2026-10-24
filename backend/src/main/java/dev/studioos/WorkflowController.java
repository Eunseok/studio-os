package dev.studioos;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class WorkflowController {
  private final WorkflowService workflows;

  public WorkflowController(WorkflowService workflows) {
    this.workflows = workflows;
  }

  public record Save(
      String name, String projectId, List<WorkflowDomain.Node> nodes, Integer expectedRevision) {}

  public record Start(String requestKey) {}

  public record Retry(String expectedTaskId, String correction) {}

  @GetMapping("/workflows")
  public List<WorkflowDomain.Definition> definitions() {
    return workflows.definitions();
  }

  @PostMapping("/workflows")
  public WorkflowDomain.Definition create(@RequestBody Save r) {
    return workflows.saveDefinition(null, r.name(), r.projectId(), r.nodes(), null);
  }

  @PostMapping("/workflows/{id}")
  public WorkflowDomain.Definition update(@PathVariable String id, @RequestBody Save r) {
    return workflows.saveDefinition(id, r.name(), r.projectId(), r.nodes(), r.expectedRevision());
  }

  @PostMapping("/workflows/{id}/runs")
  public WorkflowDomain.Run start(@PathVariable String id, @RequestBody Start r) throws Exception {
    return workflows.start(id, r.requestKey());
  }

  @GetMapping("/workflow-runs")
  public List<WorkflowDomain.Run> runs() {
    return workflows.runs();
  }

  @GetMapping("/workflow-runs/{id}")
  public WorkflowDomain.Run run(@PathVariable String id) {
    return workflows.run(id);
  }

  @PostMapping("/workflow-runs/{id}/reconcile")
  public WorkflowDomain.Run reconcile(@PathVariable String id) {
    return workflows.reconcile(id);
  }

  @PostMapping("/workflow-runs/{id}/nodes/{nodeId}/retry")
  public WorkflowDomain.Run retry(
      @PathVariable String id, @PathVariable String nodeId, @RequestBody Retry r) {
    return workflows.retry(id, nodeId, r.expectedTaskId(), r.correction());
  }

  @PostMapping("/workflow-runs/{id}/cancel")
  public WorkflowDomain.Run cancel(@PathVariable String id) {
    return workflows.cancel(id);
  }
}
