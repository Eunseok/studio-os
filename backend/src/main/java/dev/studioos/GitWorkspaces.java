package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class GitWorkspaces {
  public Path validate(String value) throws Exception {
    Path p = Path.of(value).toRealPath();
    String top = Processes.quick(p, "git", "rev-parse", "--show-toplevel");
    if (!p.equals(Path.of(top).toRealPath()))
      throw new IllegalArgumentException("Git 저장소의 최상위 경로를 입력하세요");
    Processes.quick(p, "git", "rev-parse", "--verify", "HEAD");
    return p;
  }

  public String head(Project project) throws Exception {
    return Processes.quick(Path.of(project.path()), "git", "rev-parse", "HEAD");
  }

  public void create(Project project, Task task) throws Exception {
    Processes.quick(
        Path.of(project.path()),
        "git",
        "-c",
        "core.hooksPath=/dev/null",
        "worktree",
        "add",
        "-b",
        task.branch,
        task.worktree,
        task.baseCommit);
  }

  public static String patchDigest(Path file) throws Exception {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
        || Files.size(file) > 32L * 1024 * 1024)
      throw new IllegalArgumentException("단계 patch가 없거나 32 MiB 제한을 초과했습니다");
    var md = java.security.MessageDigest.getInstance("SHA-256");
    try (var in = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
    }
    return HexFormat.of().formatHex(md.digest());
  }

  public String applyInputs(Task task, Path artifactRoot, Path output) throws Exception {
    Path cwd = Path.of(task.worktree);
    Path inputs = Files.createDirectories(output.resolve("inputs"));
    for (var input : task.workflowInputs) {
      Path source = artifactRoot.resolve(input.taskId()).resolve("step.patch");
      Path copy = inputs.resolve(input.nodeId() + ".patch");
      // Read a private copy; verify that exact copy before applying, not a mutable source path.
      if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
          || Files.size(source) > 32L * 1024 * 1024)
        throw new IllegalArgumentException("선행 단계 patch가 없거나 너무 큽니다: " + input.nodeId());
      Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
      if (!patchDigest(copy).equals(input.digest()))
        throw new IllegalArgumentException("선행 단계 patch 해시가 다릅니다: " + input.nodeId());
      if (Files.size(copy) > 0)
        try {
          Processes.quick(
              cwd,
              "git",
              "-c",
              "core.hooksPath=/dev/null",
              "apply",
              "--index",
              "--whitespace=nowarn",
              copy.toString());
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "선행 변경 충돌: " + input.nodeId() + " · " + e.getMessage(), e);
        }
    }
    return Processes.quick(cwd, "git", "write-tree");
  }

  public String changes(Task task) throws Exception {
    Path cwd = Path.of(task.worktree);
    if (task.cleaned || !Files.isDirectory(cwd)) return "작업 디렉터리가 정리되었습니다. 저장된 patch를 확인하세요.";
    return Processes.quick(cwd, "git", "status", "--short")
        + "\n\n"
        + Processes.quick(cwd, "git", "diff", task.baseCommit, "--");
  }

  public void artifacts(Task task, Path dir) throws Exception {
    Path cwd = Path.of(task.worktree);
    // Include untracked text/binary files in the patch without making a commit.
    Processes.quick(cwd, "git", "-c", "core.hooksPath=/dev/null", "add", "-N", "--all");
    Path patch = Files.createTempFile(dir, "patch-", ".tmp");
    try {
      Processes.capture(cwd, patch, "git", "diff", "--binary", task.baseCommit, "--");
      Files.move(
          patch,
          dir.resolve("changes.patch"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(patch);
    }
    if (task.workflowRunId != null && task.inputTree != null) {
      Path step = Files.createTempFile(dir, "step-", ".tmp");
      try {
        Processes.capture(cwd, step, "git", "diff", "--binary", task.inputTree, "--");
        Files.move(
            step,
            dir.resolve("step.patch"),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(step);
      }
    }
    Path files = Files.createTempFile(dir, "files-", ".tmp");
    try {
      Processes.capture(cwd, files, "git", "status", "--short");
      Files.move(
          files,
          dir.resolve("files.txt"),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(files);
    }
  }

  public void clean(Project project, Task task) throws Exception {
    // Never force-delete a worktree. Local changes must be exported/handled first.
    Processes.quick(Path.of(project.path()), "git", "worktree", "remove", task.worktree);
  }
}
