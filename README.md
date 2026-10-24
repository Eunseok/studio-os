# Studio OS

Self-hosted AI development studio. Talk with a coordinator, plan work, run coding agents in isolated Git worktrees, verify changes and review results.

## Run locally

Requires Java 21+, Node.js 22+, Git and Python 3 on macOS or Linux. Install and sign in to Codex CLI or Claude Code for AI execution.

```sh
./dev.sh
```

Open http://localhost:3000. Runtime state, conversations, shared documents and execution artifacts live in the ignored `data/` directory. Do not commit local data, credentials or test output.

## Projects and work

Connect any existing Git repository. Studio OS itself can be connected as an ordinary project. Discuss improvements with the coordinator, review the proposed plan and approve the final verified result. Manual tasks, Workflow Factory and Capability Academy are advanced tools.

## Development

```sh
./scripts/check.sh
```

Run browser tests against a separate test instance and database. GitHub integration is being added through pull requests; merging code and updating a running local application are separate actions.

MIT licensed. See LICENSE and SECURITY.md.

## GitHub Projects and pull requests

Install GitHub CLI and sign in with `gh auth login`, then grant Projects access with `gh auth refresh -s project`. In Projects, prepare a `Status` field with `Todo`, `In Progress`, `In Review`, and `Done` options. Connect the repository and board from the Studio project screen.

New work is mirrored to GitHub Issues; only titles and workflow metadata are published, never conversation transcripts or shared documents. Workflow stages share one issue. Existing completed history is not imported. Synchronization runs every 30 seconds while Studio is running, and errors remain visible on each card.

A PR requires passed verification, independent review, user result approval, an unchanged reviewed patch, and the same base commit on GitHub. Review the PR before using **Merge**. Changed PR heads, failed/pending GitHub checks, and merge conflicts are rejected. Closed, unmerged PRs are not marked done.

After merging, **Get merged code** performs a fast-forward only on a clean default branch with no active execution. Local changes and divergent histories are never overwritten. Restart using your normal launch script; Studio does not manage application processes. Old local repositories with unrelated history must be migrated separately before publishing results.

## Project workroom

Start in **프로젝트 공간**, choose a project, and describe the change to its coordinator. Approve the proposed scope; the coordinator selects execution models and advances implementation, tests, and independent review. The workroom follows these real states, including model selection, interrupted work, and result approval. Click a role to inspect its responsibility or the actual result. Connection loss pauses animation and labels the last known state.

Review the inline diff and verification evidence, then choose **결과 승인하고 반영** to explicitly authorize delivery. For a linked project this creates a PR, checks the reviewed head and GitHub checks, merges, and updates the clean local default branch. Problems stop delivery with an explanation and a retry action. Without GitHub, the reviewed result is applied directly to the local repository. This does not restart an application.

Shared knowledge and conversation remain scoped to the selected project and stored in Studio, not new Git documents. Interrupted runs can be resumed from the preserved worktree; recovery invalidates old verification and requires tests and independent review again. Existing newer conversations are preserved when reconnecting an older run.
