package dev.studioos;

import static dev.studioos.Domain.*;

import java.nio.file.Path;
import java.util.List;

/** Provider boundary: future Claude adapters supply their own enforced execution policy. */
public interface AgentAdapter {
  String type();

  List<Requirement> inspect();

  List<String> command(Task task, Path artifacts);

  default void simulate(Task task, Path artifacts) throws Exception {
    throw new UnsupportedOperationException();
  }
}
