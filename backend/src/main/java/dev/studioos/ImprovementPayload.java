package dev.studioos;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

public final class ImprovementPayload {
  public record Proposal(
      String id,
      String title,
      String rationale,
      List<String> evidence,
      String effort,
      List<String> risks,
      List<String> acceptance,
      String implementationPrompt,
      List<String> requiredSkills) {}

  public record Result(List<Proposal> proposals) {}

  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public static String schema() {
    Map<String, Object> text = Map.of("type", "string"),
        strings = Map.of("type", "array", "items", text);
    Map<String, Object> props = new LinkedHashMap<>();
    for (String key : List.of("id", "title", "rationale", "effort", "implementationPrompt"))
      props.put(key, text);
    for (String key : List.of("evidence", "risks", "acceptance", "requiredSkills"))
      props.put(key, strings);
    try {
      return JSON.writeValueAsString(
          Map.of(
              "type",
              "object",
              "additionalProperties",
              false,
              "required",
              List.of("proposals"),
              "properties",
              Map.of(
                  "proposals",
                  Map.of(
                      "type",
                      "array",
                      "items",
                      Map.of(
                          "type",
                          "object",
                          "additionalProperties",
                          false,
                          "properties",
                          props,
                          "required",
                          new ArrayList<>(props.keySet()))))));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Result read(Path path) throws Exception {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 131072)
      throw new IllegalArgumentException("제안 JSON이 없거나 128 KiB 제한을 초과했습니다");
    Result r = JSON.readValue(Files.readString(path), Result.class);
    if (r == null || r.proposals == null || r.proposals.isEmpty() || r.proposals.size() > 3)
      throw new IllegalArgumentException("개선 후보는 1~3개여야 합니다");
    Set<String> ids = new HashSet<>();
    for (var p : r.proposals) {
      if (p == null || p.id == null || !p.id.matches("[a-z][a-z0-9-]{0,47}") || !ids.add(p.id))
        throw new IllegalArgumentException("제안 ID가 잘못되었습니다");
      text(p.title, 120);
      text(p.rationale, 3000);
      text(p.implementationPrompt, 12000);
      if (!Set.of("small", "medium", "large").contains(p.effort))
        throw new IllegalArgumentException("작업 크기는 small/medium/large입니다");
      strings(p.evidence, 1, 12);
      strings(p.risks, 0, 10);
      strings(p.acceptance, 1, 10);
      strings(p.requiredSkills, 0, 12);
      for (String name : p.requiredSkills)
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
          throw new IllegalArgumentException("Skill 이름 형식 오류");
    }
    return r;
  }

  private static void text(String value, int max) {
    if (value == null || value.isBlank() || value.length() > max)
      throw new IllegalArgumentException("제안 내용이 누락되거나 너무 깁니다");
  }

  private static void strings(List<String> a, int min, int max) {
    if (a == null || a.size() < min || a.size() > max)
      throw new IllegalArgumentException("제안 목록 크기 오류");
    for (String s : a) text(s, 1500);
  }
}
