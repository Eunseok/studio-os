package dev.studioos;

import java.nio.file.*;
import java.util.*;

/** Conservative evidence-based categories: an unknown failure never silently escalates. */
public final class FailurePolicy {
  public record Assessment(String category, boolean adapt, String reason) {}

  public static Assessment assess(Domain.Task t, Path dir) {
    String logs = Objects.toString(t.message, "");
    for (String name : List.of("worker.log", "tests.log", "agent.jsonl", "review.log")) {
      if (name.equals("agent.jsonl") && (t.agentExitCode == null || t.agentExitCode == 0)) continue;
      Path p = dir.resolve(name);
      if (Files.isRegularFile(p))
        try (var f = new java.io.RandomAccessFile(p.toFile(), "r")) {
          f.seek(Math.max(0, f.length() - 12000));
          byte[] bytes = new byte[(int) (f.length() - f.getFilePointer())];
          f.readFully(bytes);
          logs += "\n" + new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
    String s = logs.toLowerCase(Locale.ROOT);
    if (s.matches("(?s).*(rate.?limit|quota exceeded|usage limit|too many requests|429).*"))
      return new Assessment("LIMIT", false, "서비스 한도 신호 · 한도 복구 후 동일 설정 재시도");
    if (s.matches("(?s).*(not logged in|unauthorized|authentication failed|invalid api key|401).*"))
      return new Assessment("AUTH", false, "인증 신호 · 로그인 복구 필요");
    if (s.matches(
        "(?s).*(permission denied|operation not permitted|eperm|command not found|no such"
            + " file|sandbox-exec:|sandbox_apply:|unknown option|unexpected argument|서버가 재시작|선행 변경"
            + " 충돌|patch 해시).*"))
      return new Assessment("ENVIRONMENT", false, "환경·권한·도구·입력 무결성 문제 신호 · 모델 승격 없이 원인 해결");
    if ("CHANGES_REQUIRED".equals(t.reviewStatus))
      return new Assessment("REVIEW", true, "독립 리뷰가 수정 사항을 발견했습니다 · 리뷰 근거로 모델 재선택");
    if (s.matches("(?s).*(timeoutexception|timed out|시간 제한).*"))
      return new Assessment("TIMEOUT", false, "시간 제한 · 추론 승격이 지연을 악화할 수 있어 동일 설정 유지");
    if (t.testExitCode != null && t.testExitCode != 0)
      return new Assessment("TEST", true, "검증 실패 · 테스트 증거를 읽고 모델·추론 재조정 (환경 문제 가능성도 확인)");
    return new Assessment("UNKNOWN", false, "확정할 수 없는 실행 실패 · 로그/보완 지시 확인, 자동 승격하지 않음");
  }
}
