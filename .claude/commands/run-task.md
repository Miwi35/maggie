# Run Task

Carry out one `Task` ticket: work nobody can accept through the UI — docs, refactoring, dependency upgrade, CI/CD, tests, tooling, agent-os standards and commands. No e2e journey, no Recette: merged means Done.

## Usage

```
/run-task MAG-N
```

## Steps

1. **Read the ticket** and its links. If it turns out to change what the user sees or does, it is a Feature: comment that, relabel it `Feature`, and follow `/shape-spec`.
2. **Plan in the ticket, not in a spec folder.** Post a comment of a few lines: what changes, in which files, how it will be verified. A task too large for one PR → split it into tickets (`CLAUDE.md` › Creating Linear tickets) and stop.
3. **Guard the behaviour before touching it**, according to the kind of task:
   - **Refactoring** — the existing tests must cover what moves; add characterization tests first where they do not. Behaviour must not change.
   - **Dependency upgrade** — read the changelog between the two versions, list the breaking changes that apply, fix them; one dependency family per PR.
   - **CI/CD** — validate the workflow (`actionlint`), make sure the changed jobs actually run on the PR, and note how a failure would show.
   - **Tests** — each new test must fail when the behaviour it guards is broken.
   - **Docs, standards, commands** — check every path, command and name you write against the repo.
4. **Do it**, following the surrounding code and structure.
5. **Verify**: lint and tests of every touched component (`CLAUDE.md` › Commands, and its worktree note). The plan's `E2E` line is `N/A — task`.
6. **Review loop** (`CLAUDE.md` › Review loop).
7. **Commit and PR** — the PR says what changed and how it was verified, then the Review section.
8. **Report**: the final ticket comment (`CLAUDE.md` › Reporting). After merge the ticket goes to **Done**, not Recette.
