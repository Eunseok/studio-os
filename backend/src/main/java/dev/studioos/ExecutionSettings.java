package dev.studioos;

import java.util.List;

/** Settings explicitly passed to the CLI, not inferred provider defaults or resolved model IDs. */
public record ExecutionSettings(String tool, String model, String reasoning) {
  static ExecutionSettings fromCommand(String tool, List<String> command) {
    String model = null, reasoning = null;
    // The final argument is the prompt; never inspect its contents as options.
    for (int i = 1; i < command.size() - 1; i++) {
      String option = command.get(i);
      if (option.equals("--")) break;
      if (i + 1 >= command.size() - 1) continue;
      if (option.equals("--model")) model = command.get(++i);
      else if (option.equals("--effort")) reasoning = command.get(++i);
      else if (option.equals("-c")) {
        String value = command.get(++i);
        if (value.startsWith("model_reasoning_effort="))
          reasoning = value.substring("model_reasoning_effort=".length()).replace("\"", "");
      }
    }
    return new ExecutionSettings(tool, model, reasoning);
  }
}
