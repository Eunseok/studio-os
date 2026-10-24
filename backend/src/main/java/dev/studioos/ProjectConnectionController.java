package dev.studioos;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectConnectionController {
  private final RuntimeService runtime;
  private final GitWorkspaces git;
  private final ObjectMapper json;

  public ProjectConnectionController(RuntimeService runtime, GitWorkspaces git, ObjectMapper json) {
    this.runtime = runtime;
    this.git = git;
    this.json = json;
  }

  public record Connect(String path) {}

  @PostMapping("/connect")
  public Domain.Project connect(@RequestBody Connect body) throws Exception {
    Path root = git.validate(body.path());
    List<String> command = List.of();
    Path pkg = root.resolve("package.json");
    if (Files.isRegularFile(pkg, LinkOption.NOFOLLOW_LINKS) && Files.size(pkg) < 262144) {
      String test = json.readTree(Files.readString(pkg)).path("scripts").path("test").asText();
      if (!test.isBlank() && !test.contains("no test specified")) command = List.of("npm", "test");
    } else if (Files.isRegularFile(root.resolve("pom.xml")))
      command = List.of(Files.isExecutable(root.resolve("mvnw")) ? "./mvnw" : "mvn", "test");
    else if (Files.isRegularFile(root.resolve("pyproject.toml"))
        && Files.isDirectory(root.resolve("tests"))) command = List.of("python3", "-m", "pytest");
    return runtime.addProject(
        root.getFileName().toString(),
        root.toString(),
        "Preserve unrelated changes. Do not create reports or documentation files unless explicitly"
            + " requested.",
        command);
  }
}
