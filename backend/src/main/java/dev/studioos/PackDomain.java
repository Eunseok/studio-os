package dev.studioos;

import java.util.*;

/** v1 extension boundary: declarative Skill files and Workflow templates, never install hooks. */
public final class PackDomain {
  public static class Manifest {
    public String schema, name, version, description, author, license;
    public List<SkillEntry> skills = List.of();
    public List<Template> workflows = List.of();
  }
  public static class SkillEntry {
    public String name;
    public Map<String,String> files = new TreeMap<>();
  }
  public static class Template {
    public String name;
    public List<WorkflowDomain.Node> nodes = List.of();
  }
  public static class Pack {
    public String id, digest, source, createdAt;
    public Manifest manifest;
    public List<Domain.Requirement> checks = List.of();
  }
  public static class Installation {
    public String id, packId, digest, projectId, agentId, createdAt;
    public List<String> skillIds = new ArrayList<>();
    public List<String> workflowIds = new ArrayList<>();
  }
  public static class Catalog {
    public String id, name, url, updatedAt;
    public List<Entry> entries = List.of();
  }
  public static class Entry {
    public String name, version, description, url, sha256;
  }
  public static class Index {
    public String schema;
    public List<Entry> entries;
  }
}
