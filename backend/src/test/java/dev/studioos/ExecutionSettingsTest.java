package dev.studioos;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionSettingsTest {
  @Test
  void capturesExactlyWhatEachAdapterPassesIncludingClaudeDefaults() {
    var task = new Domain.Task();
    task.worktree = ".";
    task.model = "chosen-model";
    task.reasoning = "high";
    var codex = new CodexAdapter("codex");
    assertThat(ExecutionSettings.fromCommand("codex", codex.command(task, Path.of("."))))
        .isEqualTo(new ExecutionSettings("codex", "chosen-model", "high"));
    task.model = null;
    task.reasoning = null;
    assertThat(ExecutionSettings.fromCommand("codex", codex.command(task, Path.of("."))))
        .isEqualTo(new ExecutionSettings("codex", null, null));
    var claude = new ClaudeAdapter("claude");
    assertThat(ExecutionSettings.fromCommand("claude", claude.command(task, Path.of("."))))
        .isEqualTo(new ExecutionSettings("claude", "sonnet", "medium"));
  }

  @Test
  void doesNotReadPromptAsFlagsOrGuessUnknownSettings() {
    assertThat(ExecutionSettings.fromCommand("custom", List.of("cli", "--model fake")))
        .isEqualTo(new ExecutionSettings("custom", null, null));
    assertThat(ExecutionSettings.fromCommand("claude", List.of("cli", "--", "--model", "fake", "prompt")))
        .isEqualTo(new ExecutionSettings("claude", null, null));
  }

  @Test
  void historicalMessagesKeepTheirOwnSettingsAndLegacyMessagesRemainUnknown() throws Exception {
    var json = new ObjectMapper();
    var message = new ChiefService.Message("assistant", "답변", "time",
        new ExecutionSettings("codex", "model", "low"));
    assertThat(json.readValue(json.writeValueAsString(message), ChiefService.Message.class))
        .isEqualTo(message);
    assertThat(json.readValue("{\"role\":\"assistant\",\"text\":\"old\",\"at\":\"time\"}", ChiefService.Message.class).execution())
        .isNull();
  }
}
