package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.Path;
import java.util.List;

/** Provider boundary: future Claude adapters supply their own enforced execution policy. */
public interface AgentAdapter {
  String type();

  List<Requirement> inspect();

  List<String> command(Task task, Path artifacts);

  /** Capabilities of this invocation, rather than of the provider's interactive UI. */
  default String steeringCapability(List<String> command) {
    return "직접 전달 미지원 · 입력 채널이 없는 단일 호출, 같은 worktree에서 중단·재개";
  }

  default void simulate(Task task, Path artifacts) throws Exception {
    throw new UnsupportedOperationException();
  }
}
