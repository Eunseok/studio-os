package dev.studioos;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LibraryController {
  private final ProjectLibrary library;
  private final Store store;

  public LibraryController(ProjectLibrary library, Store store) {
    this.library = library;
    this.store = store;
  }

  public record Archive(boolean archived) {}

  public record Edit(String title, String body, int expectedRevision) {}

  @GetMapping("/projects/all")
  public Object all() {
    return Map.of(
        "projects", store.all("project", Domain.Project.class), "archived", library.archived());
  }

  @PostMapping("/projects/{id}/archive")
  public Object archive(@PathVariable String id, @RequestBody Archive r) {
    return library.archive(id, r.archived());
  }

  @GetMapping("/projects/{id}/documents")
  public Object docs(@PathVariable String id) {
    return library.documents(id);
  }

  @PostMapping("/projects/{id}/documents/{key}")
  public Object edit(@PathVariable String id, @PathVariable String key, @RequestBody Edit r) {
    return library.save(id, key, r.title(), r.body(), r.expectedRevision(), "사용자");
  }

  @GetMapping("/projects/{id}/document-history")
  public Object history(@PathVariable String id) {
    return store.all("document-revision", ProjectLibrary.Document.class).stream()
        .filter(d -> d.projectId().equals(id))
        .toList();
  }

  @GetMapping("/projects/{id}/repository-documents")
  public Object inventory(@PathVariable String id) throws Exception {
    return library.inventory(id);
  }
}
