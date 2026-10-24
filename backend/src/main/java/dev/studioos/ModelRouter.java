package dev.studioos;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic policy, not an assertion about model benchmark superiority. Frozen per attempt. */
@Service
public class ModelRouter {
  private final String codex, claude, claudeDeep;

  public ModelRouter(
      @Value("${studio.model.codex:gpt-6.1-sol}") String codex,
      @Value("${studio.model.claude:sonnet}") String claude,
      @Value("${studio.model.claude-deep:opus}") String deep) {
    this.codex = codex;
    this.claude = claude;
    this.claudeDeep = deep;
  }

  public List<String> models(String provider) {
    return provider.equals("codex")
        ? List.of(codex)
        : new ArrayList<>(new LinkedHashSet<>(List.of(claude, claudeDeep)));
  }

  public void selected(
      Domain.Task t, String model, String reasoning, String reason, String routingId) {
    if (!models(t.adapter).contains(model) || !Set.of("low", "medium", "high").contains(reasoning))
      throw new IllegalArgumentException("등록된 모델과 추론 수준만 선택할 수 있습니다");
    t.model = model;
    t.reasoning = reasoning;
    t.routingReason = reason;
    t.routingId = routingId;
  }

  public void apply(Domain.Task task) {
    if (task.adapter.equals("demo")) return;
    String text = (task.title + " " + task.prompt).toLowerCase(Locale.ROOT);
    boolean complex =
        text.matches(
            "(?s).*(아키텍처|마이그레이션|보안|동시성|인증|architecture|migration|security|concurrency|authentication).*");
    boolean simple = text.matches("(?s).*(readme|오타|문구|typo|문서).*");
    task.reasoning = complex ? "high" : simple ? "low" : "medium";
    task.model = task.adapter.equals("claude") ? (complex ? claudeDeep : claude) : codex;
    task.routingReason =
        "정책 v1 · "
            + (task.improvementAnalysis ? "개선 조사/제안" : "코드 구현")
            + " · "
            + (complex ? "설계·보안 등 복잡도 신호 → high" : simple ? "문서·작은 수정 신호 → low" : "일반 작업 → medium")
            + " · "
            + task.adapter
            + " / "
            + task.model;
  }
}
