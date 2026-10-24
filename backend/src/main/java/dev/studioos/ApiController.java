package dev.studioos;

import static dev.studioos.Domain.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final RuntimeService runtime;
  private final ExecutionWorker worker;
  private final Store store;
  private final GitWorkspaces git;

  public ApiController(RuntimeService r, ExecutionWorker w, Store s, GitWorkspaces git) {
    runtime = r;
    worker = w;
    store = s;
    this.git = git;
  }

  public record NewProject(
      @NotBlank String name,
      @NotBlank String path,
      @NotNull String rules,
      @NotNull List<String> testCommand) {}

  public record ProjectSettings(@NotNull String rules, @NotNull List<String> testCommand) {}

  public record NewAgent(@NotBlank String name, @NotBlank String role, @NotBlank String adapter) {}

  public record NewTask(
      @NotBlank String projectId,
      String agentId,
      @NotBlank String title,
      @NotBlank String prompt,
      @Min(10) @Max(3600) int timeoutSeconds,
      List<String> requiredSkills) {}

  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of("status", "ok", "version", "0.1.0");
  }

  @GetMapping("/snapshot")
  public Map<String, Object> snapshot() {
    return runtime.snapshot();
  }

  @PostMapping("/projects")
  public Project project(@Valid @RequestBody NewProject r) throws Exception {
    return runtime.addProject(r.name(), r.path(), r.rules(), r.testCommand());
  }

  @PostMapping("/projects/{id}/settings")
  public Project settings(@PathVariable String id, @Valid @RequestBody ProjectSettings r) {
    return runtime.settings(id, r.rules(), r.testCommand());
  }

  @PostMapping("/agents")
  public Agent agent(@Valid @RequestBody NewAgent r) {
    return runtime.addAgent(r.name(), r.role(), r.adapter());
  }

  @PostMapping("/tasks")
  public Task task(@Valid @RequestBody NewTask r) throws Exception {
    return runtime.create(
        r.projectId(), r.agentId(), r.title(), r.prompt(), r.timeoutSeconds(), r.requiredSkills());
  }

  @GetMapping("/tasks/{id}")
  public Task get(@PathVariable String id) {
    return runtime.task(id);
  }

  public record RetryTask(String correction) {}

  @PostMapping("/tasks/{id}/retry")
  public Task retry(@PathVariable String id, @RequestBody RetryTask body) throws Exception {
    return runtime.retryTask(id, body.correction());
  }

  @PostMapping("/tasks/{id}/approve")
  public Task approve(@PathVariable String id) {
    return runtime.approve(id);
  }

  @PostMapping("/tasks/{id}/cancel")
  public Task cancel(@PathVariable String id) {
    var t = runtime.cancel(id);
    worker.stop(id);
    return t;
  }

  @PostMapping("/tasks/{id}/recheck")
  public Task recheck(@PathVariable String id) {
    return runtime.recheck(id);
  }

  @PostMapping("/tasks/{id}/apply")
  public Task apply(@PathVariable String id) throws Exception {
    return runtime.applyToProject(id);
  }

  @PostMapping("/tasks/{id}/cleanup")
  public Task cleanup(@PathVariable String id) throws Exception {
    return runtime.cleanup(id);
  }

  @GetMapping("/tasks/{id}/changes")
  public Map<String, String> changes(@PathVariable String id) throws Exception {
    Task t = runtime.task(id);
    if (t.worktree == null || !Files.isDirectory(Path.of(t.worktree)))
      return Map.of("diff", "아직 worktree가 생성되지 않았습니다");
    return Map.of("diff", git.changes(t));
  }

  @GetMapping("/tasks/{id}/logs")
  public Map<String, String> logs(@PathVariable String id) throws Exception {
    Path dir = runtime.artifacts(id);
    Map<String, String> result = new LinkedHashMap<>();
    for (String name : List.of("agent.jsonl", "tests.log", "worker.log", "review.log")) {
      Path p = dir.resolve(name);
      if (Files.exists(p))
        try (var f = new java.io.RandomAccessFile(p.toFile(), "r")) {
          f.seek(Math.max(0, f.length() - 65536));
          byte[] bytes = new byte[(int) (f.length() - f.getFilePointer())];
          f.readFully(bytes);
          result.put(name, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    return result;
  }

  @GetMapping("/tasks/{id}/artifacts/{name}")
  public ResponseEntity<byte[]> artifact(@PathVariable String id, @PathVariable String name)
      throws Exception {
    if (!Set.of(
            "agent.jsonl",
            "tests.log",
            "worker.log",
            "changes.patch",
            "files.txt",
            "execution.json",
            "skills.json",
            "step.patch",
            "workflow.json",
            "proposals.json",
            "review.json",
            "review.log")
        .contains(name)) return ResponseEntity.notFound().build();
    Path file = runtime.artifacts(id).resolve(name);
    if (!Files.isRegularFile(file)) return ResponseEntity.notFound().build();
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(Files.readAllBytes(file));
  }

  @GetMapping("/events")
  public List<Event> events(@RequestParam(defaultValue = "0") long after) {
    return store.events(after);
  }

  @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @RequestParam(defaultValue = "0") long after,
      @RequestHeader(value = "Last-Event-ID", required = false) String last) {
    var emitter = new SseEmitter(30000L);
    long cursor = last != null ? Long.parseLong(last) : after;
    Thread.startVirtualThread(
        () -> {
          try {
            long current = cursor;
            for (int i = 0; i < 25; i++) {
              for (Event e : store.events(current)) {
                emitter.send(SseEmitter.event().id(Long.toString(e.id())).name("studio").data(e));
                current = e.id();
              }
              emitter.send(SseEmitter.event().comment("heartbeat"));
              Thread.sleep(1000);
            }
            emitter.complete();
          } catch (Exception e) {
            emitter.complete();
          }
        });
    return emitter;
  }
}
