package dev.studioos;

import static dev.studioos.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;

class GithubServiceTest {
  RuntimeService runtime = mock(RuntimeService.class);
  Store store = mock(Store.class);
  GitWorkspaces git = mock(GitWorkspaces.class);
  GithubCommand cmd = mock(GithubCommand.class);
  GithubService service = new GithubService(runtime, store, git, cmd, new ObjectMapper());

  @AfterEach
  void close() {
    service.close();
  }

  @Test
  void successIsReviewUntilMerged() {
    Task t = new Task();
    t.status = Status.SUCCEEDED;
    assertThat(GithubService.column(t, null)).isEqualTo("In Review");
    assertThat(GithubService.column(t, "CLOSED")).isEqualTo("In Review");
    assertThat(GithubService.column(t, "MERGED")).isEqualTo("Done");
  }

  @Test
  void rejectedResultsNeverPublish() throws Exception {
    GithubService.Binding b = new GithubService.Binding();
    b.projectId = "p";
    GithubService.Work w = new GithubService.Work();
    w.id = "w";
    w.projectId = "p";
    w.taskId = "t";
    when(store.get("github-binding", "github-binding:p", GithubService.Binding.class))
        .thenReturn(b);
    when(store.all("github-work", GithubService.Work.class)).thenReturn(List.of(w));
    when(runtime.project("p")).thenReturn(new Project("p", "Test", "/tmp", "", List.of(), ""));
    Task t = new Task();
    t.status = Status.WAITING_APPROVAL;
    t.verification = Verification.PASSED;
    t.reviewStatus = "PASSED";
    when(runtime.task("t")).thenReturn(t);
    assertThatThrownBy(() -> service.publish("p", "w")).hasMessageContaining("결과 승인");
    verifyNoInteractions(cmd, git);
  }

  @Test
  void changedPrCannotMerge() throws Exception {
    GithubService.Binding b = new GithubService.Binding();
    b.projectId = "p";
    GithubService.Work w = new GithubService.Work();
    w.id = "w";
    w.projectId = "p";
    w.prUrl = "https://github.com/test/repo/pull/1";
    w.head = "b".repeat(40);
    w.reviewedHead = "a".repeat(40);
    when(store.get("github-binding", "github-binding:p", GithubService.Binding.class))
        .thenReturn(b);
    when(store.all("github-work", GithubService.Work.class)).thenReturn(List.of(w));
    assertThatThrownBy(() -> service.merge("p", "w", w.head)).hasMessageContaining("변경");
    verifyNoInteractions(cmd);
  }

  @Test
  void invalidBindingDoesNotExecuteCommands() {
    assertThatThrownBy(() -> service.connect("p", new GithubService.Connect("--bad", "user", 1)))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(cmd);
  }

  GithubService.Work mergeFixture() {
    GithubService.Binding b = new GithubService.Binding();
    b.projectId = "p";
    b.repo = "owner/repo";
    b.boardId = "board";
    b.fieldId = "field";
    b.options.put("Done", "done");
    GithubService.Work w = new GithubService.Work();
    w.id = "w";
    w.projectId = "p";
    w.taskId = "t";
    w.itemId = "item";
    w.prUrl = "https://github.com/owner/repo/pull/1";
    w.head = "a".repeat(40);
    w.reviewedHead = w.head;
    when(store.get("github-binding", "github-binding:p", GithubService.Binding.class))
        .thenReturn(b);
    when(store.all("github-work", GithubService.Work.class)).thenReturn(List.of(w));
    when(runtime.project("p")).thenReturn(new Project("p", "Test", "/tmp", "", List.of(), ""));
    Task t = new Task();
    t.status = Status.SUCCEEDED;
    t.verification = Verification.PASSED;
    t.reviewStatus = "PASSED";
    when(runtime.task("t")).thenReturn(t);
    return w;
  }

  @Test
  void failedChecksBlockMerge() throws Exception {
    var w = mergeFixture();
    when(cmd.run(
            Path.of("/tmp"),
            "gh",
            "pr",
            "view",
            w.prUrl,
            "--repo",
            "owner/repo",
            "--json",
            "state,isDraft,headRefOid,statusCheckRollup,mergeStateStatus"))
        .thenReturn(
            "{\"state\":\"OPEN\",\"headRefOid\":\""
                + w.head
                + "\",\"mergeStateStatus\":\"CLEAN\",\"statusCheckRollup\":[{\"conclusion\":\"FAILURE\"}]}");
    assertThatThrownBy(() -> service.merge("p", "w", w.head)).hasMessageContaining("검사");
    verify(cmd, never())
        .run(
            Path.of("/tmp"),
            "gh",
            "pr",
            "merge",
            w.prUrl,
            "--repo",
            "owner/repo",
            "--squash",
            "--match-head-commit",
            w.head);
  }

  @Test
  void successfulMergePinsReviewedCommitAndMovesBoard() throws Exception {
    var w = mergeFixture();
    when(cmd.run(
            Path.of("/tmp"),
            "gh",
            "pr",
            "view",
            w.prUrl,
            "--repo",
            "owner/repo",
            "--json",
            "state,isDraft,headRefOid,statusCheckRollup,mergeStateStatus"))
        .thenReturn(
            "{\"state\":\"OPEN\",\"headRefOid\":\""
                + w.head
                + "\",\"mergeStateStatus\":\"CLEAN\",\"statusCheckRollup\":[{\"conclusion\":\"SUCCESS\"}]}");
    when(cmd.run(
            Path.of("/tmp"),
            "gh",
            "pr",
            "view",
            w.prUrl,
            "--repo",
            "owner/repo",
            "--json",
            "state,headRefOid"))
        .thenReturn("{\"state\":\"MERGED\",\"headRefOid\":\"" + w.head + "\"}");
    assertThat(service.merge("p", "w", w.head).column).isEqualTo("Done");
    verify(cmd)
        .run(
            Path.of("/tmp"),
            "gh",
            "pr",
            "merge",
            w.prUrl,
            "--repo",
            "owner/repo",
            "--squash",
            "--match-head-commit",
            w.head);
    verify(cmd)
        .run(
            Path.of("/tmp"),
            "gh",
            "project",
            "item-edit",
            "--id",
            "item",
            "--project-id",
            "board",
            "--field-id",
            "field",
            "--single-select-option-id",
            "done");
  }
}
