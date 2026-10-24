package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ProjectLibrary {
  public record Visibility(String id, boolean archived) {}

  public record Document(
      String id,
      String projectId,
      String title,
      String body,
      int revision,
      String author,
      String updatedAt) {}

  private final Store store;

  public ProjectLibrary(Store store) {
    this.store = store;
  }

  public Set<String> archived() {
    return store.all("project-visibility", Visibility.class).stream()
        .filter(Visibility::archived)
        .map(Visibility::id)
        .collect(java.util.stream.Collectors.toSet());
  }

  public synchronized Visibility archive(String id, boolean archived) {
    store.get("project", id, Project.class);
    if (archived
        && store.all("task", Task.class).stream()
            .anyMatch(
                t ->
                    t.projectId.equals(id)
                        && Set.of(Status.RUNNING, Status.QUEUED, Status.ANALYZING, Status.VERIFYING)
                            .contains(t.status)))
      throw new IllegalArgumentException("실행 중인 작업을 먼저 중지하세요");
    Visibility v = new Visibility(id, archived);
    store.save(
        "project-visibility",
        "visibility:" + id,
        v,
        "project.visibility",
        null,
        Map.of("projectId", id, "archived", archived));
    return v;
  }

  public List<Document> documents(String project) {
    return store.all("shared-document", Document.class).stream()
        .filter(d -> d.projectId().equals(project))
        .sorted(Comparator.comparing(Document::title))
        .toList();
  }

  public synchronized Document save(
      String project, String id, String title, String body, int expected, String author) {
    store.get("project", project, Project.class);
    if (!Set.of("brief", "decisions", "handoff").contains(id)
        || title == null
        || title.isBlank()
        || title.length() > 120
        || body == null
        || body.length() > 16000)
      throw new IllegalArgumentException("문서 종류·제목·내용을 확인하세요 (최대 16000자)");
    String key = project + ":" + id;
    Document old =
        documents(project).stream().filter(d -> d.id().equals(key)).findFirst().orElse(null);
    if ((old == null ? 0 : old.revision()) != expected)
      throw new IllegalArgumentException("문서가 변경됐습니다. 최신 내용을 확인하세요");
    Document d = new Document(key, project, title, body, expected + 1, author, now());
    store.save(
        "document-revision",
        key + ":" + d.revision(),
        d,
        "document.revised",
        null,
        Map.of("projectId", project));
    store.save(
        "shared-document",
        key,
        d,
        "document.updated",
        null,
        Map.of("projectId", project, "title", title));
    return d;
  }

  public String context(String project) {
    StringBuilder text =
        new StringBuilder("\nStudio shared documents (approved context; exact revisions):\n");
    for (Document d : documents(project))
      text.append("\n")
          .append(d.title())
          .append(" [")
          .append(d.id())
          .append(" revision ")
          .append(d.revision())
          .append("]\n")
          .append(d.body())
          .append("\n");
    return text.toString();
  }

  public List<Map<String, Object>> inventory(String id) throws Exception {
    Path root = Path.of(store.get("project", id, Project.class).path()).toRealPath();
    var result = new ArrayList<Map<String, Object>>();
    Files.walkFileTree(
        root,
        EnumSet.noneOf(FileVisitOption.class),
        8,
        new SimpleFileVisitor<>() {
          public FileVisitResult preVisitDirectory(
              Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
            return !dir.equals(root)
                    && (dir.getFileName().toString().startsWith(".")
                        || Set.of(
                                "node_modules",
                                "target",
                                "data",
                                "test-results",
                                "playwright-report")
                            .contains(dir.getFileName().toString()))
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          public FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
            if (result.size() >= 200) return FileVisitResult.TERMINATE;
            if (attrs.isRegularFile()
                && file.getFileName().toString().matches("(?i).+\\.(md|mdx|txt)"))
              result.add(Map.of("path", root.relativize(file).toString(), "bytes", attrs.size()));
            return FileVisitResult.CONTINUE;
          }
        });
    return result;
  }
}
