package dev.studioos;

import java.time.Instant;
import java.util.*;

public final class Domain {
  private Domain() {}

  public enum Status {
    QUEUED,
    ANALYZING,
    BLOCKED,
    RUNNING,
    WAITING_APPROVAL,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED
  }

  public enum Verification {
    NOT_RUN,
    PASSED,
    FAILED
  }

  public record Project(
      String id,
      String name,
      String path,
      String rules,
      List<String> testCommand,
      String createdAt) {}

  public record Agent(String id, String name, String role, String adapter) {}

  public record Requirement(String name, boolean available, String detail) {}

  public record Event(
      long id, String type, String taskId, String createdAt, Map<String, Object> data) {}

  public record DocumentRef(String id, String title, int revision) {}

  public static class Task {
    public List<DocumentRef> sharedDocuments = List.of();
    public String sharedContext = "";
    public String id,
        projectId,
        agentId,
        title,
        prompt,
        createdAt,
        updatedAt,
        startedAt,
        finishedAt;
    public Status status;
    public Verification verification = Verification.NOT_RUN;
    public String adapter, role, rules, baseCommit, worktree, branch, message, approvedAt;
    public List<String> requiredSkills = List.of();
    public List<CapabilityService.Binding> skills = List.of();
    @com.fasterxml.jackson.annotation.JsonIgnore public String skillInstructions = "";
    public List<String> testCommand = List.of();
    public List<Requirement> requirements = List.of();
    public int timeoutSeconds = 600;
    public Integer agentExitCode, testExitCode;
    public Long processId;
    public String processStartedAt;
    public String workflowRunId, workflowNodeId, inputTree;
    public String assessmentId, reviewerAgentId;
    public int workflowAttempt;
    public boolean requireVerification;
    public List<WorkflowDomain.Input> workflowInputs = List.of();
    public boolean cleaned;
    public boolean improvementAnalysis;
    public boolean autoRouting, routingPending, requireReview;
    public String routingError, previousAttemptId, retryTaskId, appliedCommit;
    public int attempt = 1;
    public FailurePolicy.Assessment failure;
    public ReviewService.Result review;
    public String reviewStatus = "NOT_RUN",
        reviewDigest,
        reviewerProvider,
        reviewerModel,
        reviewerReasoning,
        reviewRoutingId;
    public String model, reasoning, routingReason, routingId;
    public ExecutionSettings execution;
    public int recoveryCount;
    public boolean workspacePrepared, steeringPending, steeringStopped, steeringStopFailed;
    public int steeringRevision, steeringAppliedRevision;
    public String steeringCapability;
    public List<ImprovementPayload.Proposal> improvementProposals = List.of();
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return Instant.now().toString();
  }
}
