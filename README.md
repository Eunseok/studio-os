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
