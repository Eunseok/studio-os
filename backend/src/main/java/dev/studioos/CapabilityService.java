package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Local, immutable skill packages. Validation never executes package code. */
@Service
public class CapabilityService {
  public static class Skill {
    public String id, projectId, name, description, version, source, digest, createdAt, validatedAt;
    public String status = "DISCOVERED";
    public Map<String, String> files = new TreeMap<>();
    public List<String> requiredTools = List.of();
    public List<Requirement> checks = List.of();
  }

  public record Binding(String id, String name, String version, String digest) {}

  public record ScanResult(List<Skill> skills, List<String> issues) {}

  private final Store store;
  private final TransactionTemplate tx;
  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  public CapabilityService(Store store, TransactionTemplate tx) {
    this.store = store;
    this.tx = tx;
  }

  public List<Skill> all() {
    return store.all("capability", Skill.class);
  }

  public Skill get(String id) {
    return store.get("capability", id, Skill.class);
  }

  public List<Map<String, Object>> summaries() {
    return all().stream()
        .map(
            s -> {
              Map<String, Object> m = new LinkedHashMap<>();
              m.put("id", s.id);
              m.put("projectId", s.projectId);
              m.put("name", s.name);
              m.put("description", s.description);
              m.put("version", s.version);
              m.put("source", s.source);
              m.put("digest", s.digest);
              m.put("createdAt", s.createdAt);
              m.put("status", s.status);
              m.put("checks", s.checks);
              m.put("validatedAt", s.validatedAt);
              m.put("requiredTools", s.requiredTools);
              return m;
            })
        .toList();
  }

  private void scope(String projectId) {
    if (projectId != null) store.get("project", projectId, Project.class);
  }

  public synchronized Skill register(String projectId, String path, String content)
      throws Exception {
    scope(projectId);
    if ((path == null || path.isBlank()) == (content == null || content.isBlank()))
      throw new IllegalArgumentException("로컬 SKILL.md 경로 또는 내용을 하나만 입력하세요");
    Skill s = new Skill();
    s.id = id();
    s.projectId = projectId;
    s.createdAt = now();
    if (path != null && !path.isBlank()) {
      Path entry = Path.of(path).toAbsolutePath().normalize();
      if (Files.isDirectory(entry)) entry = entry.resolve("SKILL.md");
      if (!entry.getFileName().toString().equals("SKILL.md")
          || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("일반 파일 SKILL.md 경로가 필요합니다");
      Path root = entry.getParent();
      // Resolve parent aliases, but never follow links inside the package.
      root = root.toRealPath();
      s.source = root.resolve("SKILL.md").toString();
      long total = 0;
      try (var walk = Files.walk(root, 8)) {
        var iterator = walk.iterator();
        while (iterator.hasNext()) {
          Path file = iterator.next();
          if (Files.isSymbolicLink(file))
            throw new IllegalArgumentException("패키지 내부 심볼릭 링크는 지원하지 않습니다: " + file.getFileName());
          if (Files.isDirectory(file)) {
            if (root.relativize(file).getNameCount() >= 8)
              throw new IllegalArgumentException("Skill 폴더 깊이는 7단계 이하로 제한됩니다");
            continue;
          }
          if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("일반 파일만 등록할 수 있습니다");
          long size = Files.size(file);
          total += size;
          if (size > 131072 || total > 1048576 || s.files.size() >= 100)
            throw new IllegalArgumentException("Skill 제한: 파일 128 KiB, 전체 1 MiB, 100개");
          String text;
          try {
            text = Files.readString(file, StandardCharsets.UTF_8);
          } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("UTF-8 텍스트만 지원합니다: " + root.relativize(file));
          }
          if (text.indexOf('\0') >= 0) throw new IllegalArgumentException("텍스트 Skill 패키지만 지원합니다");
          s.files.put(root.relativize(file).toString().replace('\\', '/'), text);
        }
      }
    } else {
      if (content.getBytes(StandardCharsets.UTF_8).length > 131072)
        throw new IllegalArgumentException("SKILL.md는 128 KiB 이하로 입력하세요");
      s.source = "inline";
      s.files.put("SKILL.md", content);
    }
    s.digest = digest(s.files);
    for (Skill old : all())
      if (Objects.equals(old.projectId, projectId)
          && old.source.equals(s.source)
          && old.digest.equals(s.digest)) return old;
    inspect(s);
    save(s, "skill.registered");
    return s;
  }

  /** Preview an in-memory package without writing or executing its content. */
  public Skill previewFiles(Map<String, String> files) {
    if (files == null || files.isEmpty() || files.size() > 100 || !files.containsKey("SKILL.md"))
      throw new IllegalArgumentException("Skill에는 SKILL.md와 최대 100개 파일이 필요합니다");
    long total = 0;
    for (var entry : files.entrySet()) {
      String name = entry.getKey(), content = entry.getValue();
      if (name == null || name.length() > 200 || !name.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
          || Arrays.stream(name.split("/")).anyMatch(p -> p.equals(".") || p.equals(".."))
          || name.split("/").length > 7 || content == null || content.indexOf('\0') >= 0)
        throw new IllegalArgumentException("안전한 상대 경로와 UTF-8 텍스트가 필요합니다");
      long bytes = content.getBytes(StandardCharsets.UTF_8).length;
      total += bytes;
      if (bytes > 131072 || total > 1048576) throw new IllegalArgumentException("Skill 파일 크기 제한 초과");
    }
    Skill s = new Skill();
    s.files = new TreeMap<>(files);
    s.digest = digest(s.files);
    inspect(s);
    return s;
  }

  public synchronized Skill registerFiles(String projectId, String source, Map<String, String> files) {
    scope(projectId);
    Skill s = previewFiles(files);
    for (Skill old : all())
      if (Objects.equals(old.projectId, projectId) && old.source.equals(source) && old.digest.equals(s.digest)) return old;
    s.id = id(); s.projectId = projectId; s.source = source; s.createdAt = now();
    save(s, "skill.registered");
    return s;
  }

  public synchronized ScanResult scan(String projectId, boolean includeGlobal) throws Exception {
    scope(projectId);
    List<Path> roots = new ArrayList<>();
    if (projectId != null) {
      Path p = Path.of(store.get("project", projectId, Project.class).path());
      for (String dir : List.of(".agents/skills", ".codex/skills", ".claude/skills", "skills"))
        roots.add(p.resolve(dir));
    }
    List<Skill> found = new ArrayList<>();
    List<String> issues = new ArrayList<>();
    scanRoots(roots, projectId, found, issues);
    if (includeGlobal) {
      Path home = Path.of(System.getProperty("user.home"));
      scanRoots(
          List.of(
              home.resolve(".agents/skills"),
              home.resolve(".codex/skills"),
              home.resolve(".claude/skills")),
          null,
          found,
          issues);
    }
    return new ScanResult(found, issues);
  }

  private void scanRoots(List<Path> roots, String scope, List<Skill> found, List<String> issues)
      throws Exception {
    for (Path root : roots) {
      if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
      // Discovery is bounded and does not follow symlink directories.
      try (var walk = Files.walk(root, 5)) {
        for (Path file :
            walk.filter(p -> p.getFileName().toString().equals("SKILL.md")).limit(501).toList()) {
          if (found.size() + issues.size() >= 500) {
            issues.add("스캔 한도 500개에 도달했습니다. 개별 경로로 등록하세요.");
            return;
          }
          try {
            found.add(register(scope, file.toString(), null));
          } catch (Exception e) {
            issues.add(file + ": " + Objects.toString(e.getMessage(), "읽기 실패"));
          }
        }
      } catch (java.io.IOException e) {
        issues.add(root + ": " + e.getMessage());
      }
    }
  }

  public synchronized Skill validate(String id) {
    Skill s = get(id);
    inspect(s);
    s.validatedAt = now();
    if (!s.status.equals("ACTIVE")) s.status = "REVIEWING";
    else if (!valid(s)) s.status = "DISABLED";
    save(s, "skill.validated");
    return s;
  }

  public synchronized Skill activate(String id) {
    return tx.execute(
        status -> {
          Skill s = get(id);
          if (s.validatedAt == null) throw new IllegalArgumentException("먼저 검증을 실행하세요");
          inspect(s);
          if (!valid(s)) throw new IllegalArgumentException("검증 실패 항목을 해결한 새 버전을 등록하세요");
          for (Skill old : all())
            if (!old.id.equals(id)
                && old.status.equals("ACTIVE")
                && old.name.equals(s.name)
                && Objects.equals(old.projectId, s.projectId)) {
              old.status = "DISABLED";
              save(old, "skill.disabled");
            }
          s.status = "ACTIVE";
          save(s, "skill.activated");
          return s;
        });
  }

  public synchronized Skill disable(String id) {
    Skill s = get(id);
    s.status = "DISABLED";
    save(s, "skill.disabled");
    return s;
  }

  private void save(Skill s, String event) {
    store.save("capability", s.id, s, event, null, Map.of("name", s.name, "status", s.status));
  }

  private static boolean valid(Skill s) {
    return s.checks.stream().allMatch(Requirement::available);
  }

  private void inspect(Skill s) {
    List<Requirement> checks = new ArrayList<>();
    s.name = "invalid-skill";
    s.description = "";
    s.version = "unversioned";
    s.requiredTools = List.of();
    String content = s.files.getOrDefault("SKILL.md", "").replace("\r\n", "\n");
    try {
      if (!content.startsWith("---\n"))
        throw new IllegalArgumentException("YAML frontmatter가 필요합니다");
      int end = content.indexOf("\n---\n", 4);
      if (end < 0) throw new IllegalArgumentException("frontmatter 종료 구분자가 필요합니다");
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setCodePointLimit(131072);
      Object loaded = new Yaml(new SafeConstructor(options)).load(content.substring(4, end));
      if (!(loaded instanceof Map<?, ?> meta))
        throw new IllegalArgumentException("frontmatter는 객체여야 합니다");
      Object name = meta.get("name"), description = meta.get("description");
      if (!(name instanceof String n) || n.length() > 64 || !NAME.matcher(n).matches())
        throw new IllegalArgumentException("name: 64자 이하 영문 소문자·숫자·하이픈");
      s.name = n;
      if (!(description instanceof String d) || d.isBlank() || d.length() > 1024)
        throw new IllegalArgumentException("description: 1~1024자 문자열");
      s.description = d;
      if (meta.containsKey("version")) {
        s.version = String.valueOf(meta.get("version"));
        if (s.version.length() > 80) throw new IllegalArgumentException("version은 80자 이하입니다");
      }
      if (content.substring(end + 5).isBlank())
        throw new IllegalArgumentException("Skill 본문 지시가 필요합니다");
      if (meta.containsKey("required-tools")) {
        if (!(meta.get("required-tools") instanceof List<?> tools) || tools.size() > 20)
          throw new IllegalArgumentException("required-tools는 CLI 이름 배열입니다");
        List<String> names = new ArrayList<>();
        for (Object tool : tools) {
          if (!(tool instanceof String ntool) || !ntool.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))
            throw new IllegalArgumentException("required-tools에는 CLI 이름만 허용됩니다");
          names.add(ntool);
        }
        s.requiredTools = List.copyOf(names);
      }
      checks.add(new Requirement("Skill 형식", true, "이름·설명·본문·의존성 형식 확인"));
    } catch (Exception e) {
      checks.add(new Requirement("Skill 형식", false, Objects.toString(e.getMessage(), "잘못된 YAML")));
    }
    boolean integrity = Objects.equals(s.digest, digest(s.files));
    checks.add(new Requirement("내용 무결성", integrity, "SHA-256 · 등록된 파일 스냅샷"));
    for (var entry : s.files.entrySet()) {
      if (!entry.getKey().endsWith(".md")) continue;
      var matcher =
          Pattern.compile(
                  "\\[[^\\]]*\\]\\(([^\\s)]+)(?:[^)]*)\\)|`((?:scripts|references|assets)/[^`]+)`")
              .matcher(entry.getValue());
      while (matcher.find()) {
        String ref = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (ref.startsWith("#") || ref.matches("[a-zA-Z][a-zA-Z0-9+.-]*:.*")) continue;
        ref = ref.split("[#?]", 2)[0];
        if (ref.isEmpty()) continue;
        Path parent = Path.of(entry.getKey()).getParent();
        Path target = (parent == null ? Path.of(ref) : parent.resolve(ref)).normalize();
        String key = target.toString().replace('\\', '/');
        boolean exists = !target.isAbsolute() && !key.startsWith("..") && s.files.containsKey(key);
        checks.add(
            new Requirement("참조: " + ref, exists, exists ? "패키지에 포함됨" : "파일이 없거나 패키지 밖을 참조합니다"));
      }
    }
    for (String tool : s.requiredTools)
      checks.add(new Requirement("CLI: " + tool, executable(tool), "PATH에서 실행 파일 확인 · 실행하지 않음"));
    s.checks = List.copyOf(checks);
  }

  private boolean executable(String tool) {
    for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
      if (dir.isBlank()) continue;
      Path path = Path.of(dir).resolve(tool);
      if (Files.isRegularFile(path) && Files.isExecutable(path)) return true;
    }
    return false;
  }

  private static String digest(Map<String, String> files) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (var e : new TreeMap<>(files).entrySet()) {
        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public List<String> names(List<String> names) {
    if (names == null) return List.of();
    if (names.size() > 12
        || names.stream().anyMatch(n -> n == null || n.length() > 64 || !NAME.matcher(n).matches()))
      throw new IllegalArgumentException("필요 Skill은 kebab-case 이름 최대 12개입니다");
    return names.stream().distinct().toList();
  }

  public synchronized List<Binding> resolve(String projectId, List<String> names) {
    List<Binding> bindings = new ArrayList<>();
    List<Skill> skills = all();
    for (String name : names) {
      // Project definitions shadow global definitions, including disabled revisions.
      List<Skill> local =
          skills.stream()
              .filter(s -> s.name.equals(name) && Objects.equals(s.projectId, projectId))
              .toList();
      var candidates =
          local.isEmpty()
              ? skills.stream().filter(s -> s.name.equals(name) && s.projectId == null).toList()
              : local;
      candidates.stream()
          .filter(s -> s.status.equals("ACTIVE"))
          .findFirst()
          .ifPresent(s -> bindings.add(new Binding(s.id, s.name, s.version, s.digest)));
    }
    return List.copyOf(bindings);
  }

  public synchronized List<Requirement> requirements(Task task) {
    List<Requirement> result = new ArrayList<>();
    for (String name : task.requiredSkills) {
      Binding b = task.skills.stream().filter(x -> x.name.equals(name)).findFirst().orElse(null);
      boolean ok = false;
      String detail = "활성화된 Skill이 없습니다. Academy에서 등록·검증·사용 승인 후 재검사하세요";
      if (b != null) {
        Skill s = get(b.id);
        inspect(s);
        ok =
            s.status.equals("ACTIVE")
                && (s.projectId == null || s.projectId.equals(task.projectId))
                && s.digest.equals(b.digest)
                && valid(s);
        detail =
            s.name
                + " @ "
                + s.version
                + " · "
                + s.digest.substring(0, 12)
                + (ok ? " · 승인된 버전" : " · 비활성화 또는 검증 실패. 재검사 필요");
      }
      result.add(new Requirement("Skill: " + name, ok, detail));
    }
    return result;
  }

  public synchronized String materialize(Task task, Path artifacts) throws Exception {
    if (requirements(task).stream().anyMatch(r -> !r.available()))
      throw new IllegalStateException("Skill 조건이 변경되었습니다");
    StringBuilder instructions = new StringBuilder();
    for (Binding binding : task.skills) {
      Skill skill = get(binding.id);
      Path folder = artifacts.resolve("skills").resolve(skill.name);
      for (var file : skill.files.entrySet()) {
        Path dest = folder.resolve(file.getKey()).normalize();
        if (!dest.startsWith(folder)) throw new IllegalStateException("Skill 경로 오류");
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, file.getValue());
      }
      instructions
          .append("\nApproved skill: ")
          .append(skill.name)
          .append(" @ ")
          .append(skill.version)
          .append("\nPackage directory: ")
          .append(folder)
          .append(
              "\n"
                  + "Read SKILL.md in this package before doing the task. Resolve its relative"
                  + " references from that directory. SHA-256: ")
          .append(skill.digest)
          .append("\n");
    }
    return instructions.toString();
  }
}
