# Fix Bug

Fix one bug ticket, proven by a test that fails before and passes after. The short path: no spec folder, no `/shape-spec`, no refactoring beyond the fix.

## Usage

```
/fix-bug MAG-N
```

For tickets labelled `Bug`. Features go through `/shape-spec` then `/implement-spec`, tasks through `/run-task`.

## Steps

1. **Run `task guard:enabled`** (exit 20: the emergency stop is on, comment `needs-human` and stop), then **read the ticket** (description, comments, linked tickets). Its *Piste* is a lead, not a diagnosis.
2. **Reproduce with a test, red first.** Write the smallest test at the lowest level that shows the bug: unit or API test first, the e2e journey only when the bug lives in the interaction. Run it and **see it fail for the reported reason**. Cannot reproduce after a real attempt → comment what you tried, add `needs-human`, stop.
3. **Find the cause**, not the symptom: why does the code do this? If the same cause affects other places, fix them in the same PR and list them.
4. **Fix minimally.** Follow the surrounding code; no unrelated cleanup, no new pattern. A fix that needs a schema change, a new endpoint or a design decision is no longer a bug fix: comment, `needs-human`, stop.
5. **Verify**: the reproduction test passes; lint of every touched component and the targeted tests of the fix pass — never the full suite, CI runs it (`CLAUDE.md` › Tests, and the worktree note).
6. **Review loop** (`CLAUDE.md` › Review loop). For a fix it usually ends in one round.
7. **Commit and PR.** One commit: the subject says what now works, the body gives the cause. PR: a Cyrus session follows its `verify-and-ship` skill (a draft for the merge train); any other session ships as `CLAUDE.md` › Shipping says. Link the ticket, *Cause* / *Fix* / *Test* in three short paragraphs, then the Review section.
8. **Report**: the final ticket comment (`CLAUDE.md` › Reporting) — cause, fix, test, PR link.

Also extend the module's e2e journey ticket (MAG-99…103) with this scenario when the bug was visible to the user and the journey does not cover it yet — as a line in that ticket, not in this PR.
