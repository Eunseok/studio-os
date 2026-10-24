package dev.studioos;

import static dev.studioos.Domain.*;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** No network/model call holds Runtime's monitor or a Workflow transaction. */
@Component
public class TaskPlanningWorker {
  private final RuntimeService runtime;
  private final ModelPlanner planner;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public TaskPlanningWorker(RuntimeService runtime, ModelPlanner planner) {
    this.runtime = runtime;
    this.planner = planner;
  }

  @Scheduled(fixedDelay = 700)
  public void tick() {
    if (!busy.compareAndSet(false, true)) return;
    var next =
        runtime.tasks().stream()
            .filter(t -> t.routingPending && !RuntimeService.terminal(t.status))
            .min(Comparator.comparing(t -> t.createdAt));
    if (next.isEmpty()) {
      busy.set(false);
      return;
    }
    executor.submit(
        () -> {
          try {
            plan(next.get().id);
          } finally {
            busy.set(false);
          }
        });
  }

  public void plan(String id) {
    Task t = runtime.task(id);
    if (!t.routingPending || RuntimeService.terminal(t.status)) return;
    try {
      Task previous = t.previousAttemptId == null ? null : runtime.task(t.previousAttemptId);
      String request =
          t.title
              + "\n"
              + t.prompt
              + "\nFrozen base commit: "
              + t.baseCommit
              + "\nLogical role: "
              + t.role
              + "\nRequired skills: "
              + t.requiredSkills
              + "\nApproved predecessor patches (read only): "
              + t.workflowInputs.stream()
                  .map(i -> runtime.artifacts(i.taskId()).resolve("step.patch").toString())
                  .toList();
      if (previous != null)
        request +=
            "\nPrevious failure assessment: "
                + previous.failure
                + "\nPrevious model: "
                + previous.adapter
                + "/"
                + previous.model
                + "/"
                + previous.reasoning
                + "\nRead failure evidence and review from "
                + runtime.artifacts(previous.id)
                + ". Do not assume every test failure is a reasoning failure. Select an alternative"
                + " model or more reasoning only if evidence supports it.";
      var p = runtime.project(t.projectId);
      p = new Project(p.id(), p.name(), p.path(), t.rules, t.testCommand, p.createdAt());
      var d =
          planner.choose(
              p,
              previous == null ? "task implementation" : "failure-aware retry",
              request,
              null,
              previous);
      runtime.routingComplete(id, d, null);
    } catch (Exception e) {
      runtime.routingComplete(id, null, Objects.toString(e.getMessage(), "선택 실패"));
    }
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
