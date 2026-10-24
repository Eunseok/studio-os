package dev.studioos;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class Processes {
  private Processes() {}

  public static String quick(Path cwd, String... args) throws Exception {
    Process p = new ProcessBuilder(args).directory(cwd.toFile()).redirectErrorStream(true).start();
    CompletableFuture<byte[]> bytes =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                try (InputStream input = p.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                  byte[] chunk = new byte[4096];
                  int n;
                  boolean clipped = false;
                  while ((n = input.read(chunk)) != -1) {
                    int keep = Math.min(n, 128 * 1024 - buffer.size());
                    if (keep > 0) buffer.write(chunk, 0, keep);
                    if (keep < n) clipped = true;
                  }
                  if (clipped)
                    buffer.write("\n[출력은 128 KiB까지 표시합니다]".getBytes(StandardCharsets.UTF_8));
                  return buffer.toByteArray();
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    if (!p.waitFor(15, TimeUnit.SECONDS)) {
      kill(p);
      throw new IOException("명령 시간 초과: " + args[0]);
    }
    String out = new String(bytes.get(3, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
    if (p.exitValue() != 0) throw new IOException(out.isBlank() ? "명령 실패: " + args[0] : out);
    return out;
  }

  public static void capture(Path cwd, Path file, String... args) throws Exception {
    Process p =
        new ProcessBuilder(args)
            .directory(cwd.toFile())
            .redirectOutput(file.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!p.waitFor(15, TimeUnit.SECONDS)) {
      kill(p);
      throw new IOException("아티팩트 저장 시간 초과");
    }
    if (p.exitValue() != 0) throw new IOException("아티팩트 저장 실패: " + args[0]);
  }

  public static String python() {
    for (String entry : System.getenv().getOrDefault("PATH", "").split(":")) {
      Path candidate = Path.of(entry, "python3");
      if (Files.isExecutable(candidate)) return candidate.toString();
    }
    return null;
  }

  public static void kill(Process p) {
    kill(p.toHandle());
  }

  public static void kill(ProcessHandle p) {
    List<ProcessHandle> children = p.descendants().toList();
    children.reversed().forEach(ProcessHandle::destroyForcibly);
    p.destroyForcibly();
    // run() starts a new POSIX session. Kill the group even when its leader has exited.
    try {
      new ProcessBuilder("/bin/kill", "-KILL", "--", "-" + p.pid())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(2, TimeUnit.SECONDS);
    } catch (Exception ignored) {
    }
  }

  public static int run(
      List<String> argv, Path cwd, Path log, Duration timeout, Consumer<Process> onStart)
      throws Exception {
    if (python() == null) throw new IllegalStateException("Python 3가 필요합니다 (실행 프로세스 그룹 관리)");
    List<String> grouped =
        new ArrayList<>(
            List.of(
                python(),
                "-c",
                "import os,sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])"));
    grouped.addAll(argv);
    ProcessBuilder pb =
        new ProcessBuilder(grouped).directory(cwd.toFile()).redirectErrorStream(true);
    // Credentials are used by the CLI from its own login. Do not forward unrelated environment
    // secrets.
    var env = pb.environment();
    var old = new HashMap<>(env);
    env.clear();
    for (String key :
        List.of(
            "PATH",
            "HOME",
            "USER",
            "LANG",
            "LC_ALL",
            "TMPDIR",
            "SYSTEMROOT",
            "JAVA_HOME",
            "CODEX_HOME")) if (old.containsKey(key)) env.put(key, old.get(key));
    env.put("GIT_TERMINAL_PROMPT", "0");
    env.put("NO_COLOR", "1");
    Process p = pb.start();
    try {
      // Non-interactive CLIs may consume piped stdin even when a prompt is an argument.
      // No stdin payload is supplied by this runtime, so signal EOF immediately.
      p.getOutputStream().close();
      onStart.accept(p);
    } catch (IOException | RuntimeException e) {
      kill(p);
      throw e;
    }
    var reader =
        CompletableFuture.runAsync(
            () -> {
              try (InputStream input = p.getInputStream();
                  OutputStream output =
                      Files.newOutputStream(
                          log, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                byte[] buffer = new byte[4096];
                long size = Files.size(log);
                int n;
                while ((n = input.read(buffer)) != -1) {
                  if (size + n > 10 * 1024 * 1024) {
                    kill(p);
                    throw new IOException("로그가 10 MiB 제한을 초과했습니다");
                  }
                  output.write(buffer, 0, n);
                  output.flush();
                  size += n;
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
    try {
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        kill(p);
        throw new TimeoutException("실행 시간 제한을 초과했습니다");
      }
      reader.get(5, TimeUnit.SECONDS);
      return p.exitValue();
    } finally {
      kill(p);
    }
  }
}
