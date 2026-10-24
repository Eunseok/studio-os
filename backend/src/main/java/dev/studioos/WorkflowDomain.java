package dev.studioos;

import java.util.*;

/** Definitions are reusable plans; every run owns a frozen revision and independent attempts. */
public final class WorkflowDomain {
  private WorkflowDomain() {}

  public static class Node {
    public String id, title, agentId, prompt;
    public Boolean requireReview;
    public String model, reasoning, routingReason, routingId;
    public List<String> dependsOn = List.of();
    public List<String> requiredSkills = List.of();
    public List<String> requiredExpertise = List.of();

    /** null inherits the project's command when a run is created. */
    public List<String> testCommand;

    public boolean requireVerification = true;
    public int timeoutSeconds = 600;
    public int maxAttempts = 2;
  }

  public static class Definition {
    public String id, name, projectId, createdAt, updatedAt;
    public int revision;
    public List<Node> nodes = List.of();
  }

  public static class Step {
    public String nodeId;
    public String status = "WAITING_DEPENDENCIES";
    public String message = "선행 단계의 검증과 결과 승인을 기다립니다";
    public List<String> attempts = new ArrayList<>();
    public String outputDigest;
  }

  public static class Run {
    public String id, requestKey, baseCommit, createdAt, updatedAt, finishedAt;
    public String status = "RUNNING";
    public String message = "단계별 실행 계획을 검토하세요";
    public Definition definition;
    public boolean steeringPaused;
    public Domain.Project project;
    public Map<String, Domain.Agent> agents = new LinkedHashMap<>();
    public Map<String, Step> steps = new LinkedHashMap<>();
  }

  public record Input(String taskId, String nodeId, String digest) {}
}
