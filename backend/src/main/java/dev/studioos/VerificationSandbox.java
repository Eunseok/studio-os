package dev.studioos;

import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Verification never falls back to an unrestricted host process. */
@Component
public class VerificationSandbox {
  public boolean available() {
    return Files.isExecutable(Path.of("/usr/bin/sandbox-exec")) || findBwrap() != null;
  }

  private String findBwrap() {
    for (String p : System.getenv().getOrDefault("PATH", "").split(":"))
      if (Files.isExecutable(Path.of(p, "bwrap"))) return Path.of(p, "bwrap").toString();
    return null;
  }

  public List<String> command(Path cwd, List<String> argv) throws Exception {
    List<String> result = new ArrayList<>();
    if (Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
      String path = cwd.toRealPath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
      String tmp =
          Path.of(System.getProperty("java.io.tmpdir"))
              .toRealPath()
              .toString()
              .replace("\"", "\\\"");
      String profile =
          "(version 1)(deny default)(allow process*)(allow sysctl-read)(allow file-read*)(allow"
              + " mach-lookup)(allow file-write* (subpath \""
              + path
              + "\") (subpath \""
              + tmp
              + "\") (literal \"/dev/null\"))";
      result.addAll(List.of("/usr/bin/sandbox-exec", "-p", profile));
    } else if (findBwrap() != null)
      result.addAll(
          List.of(
              findBwrap(),
              "--die-with-parent",
              "--unshare-net",
              "--ro-bind",
              "/",
              "/",
              "--bind",
              cwd.toString(),
              cwd.toString(),
              "--dev",
              "/dev",
              "--proc",
              "/proc",
              "--tmpfs",
              "/tmp",
              "--chdir",
              cwd.toString()));
    else
      throw new IllegalStateException(
          "검증용 OS sandbox가 없습니다. macOS sandbox-exec 또는 Linux bubblewrap이 필요합니다.");
    result.addAll(argv);
    return result;
  }
}
