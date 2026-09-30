# Report Bug

Turn what the user saw — usually while using Maggie in production — into a Linear bug ticket an agent can fix without asking anything. Fast: a few minutes, no fix, no code change.

## Usage

```
/report-bug <what happened, where, and screenshots if any>
```

## Steps

1. **Understand the symptom.** Extract: where (web, mobile, Maggie in chat, voice), what was done, what happened, what was expected. Ask the user **one** question only if one of these is missing and cannot be inferred.
2. **Look for a duplicate.** `list_issues` on team Maggie with the key words, open tickets and recently closed ones. Duplicate → comment the new occurrence on it (and reopen it if it was closed), then stop.
3. **Locate, read-only, 5 minutes at most.** Find the screen, endpoint, MCP tool or agent code involved and the most likely cause. This gives the areas and the probable files; it is a lead, not a diagnosis. Do not fix anything.
4. **Create the ticket** through the Linear MCP, following `CLAUDE.md` › Creating Linear tickets, with this description:
   - **Constat** — what happens, with the exact message or screenshot.
   - **Attendu** — what should happen.
   - **Reproduction** — numbered steps, from a logged-in user; channel and version if known.
   - **Piste** — the suspected cause and files, marked as a lead.
   - **Test de reproduction attendu** — the test that must fail before the fix and pass after (unit, API or e2e, and which journey ticket MAG-99…103 it extends).
   - **Hors périmètre** — what the fix must not touch.

   Labels: `Bug`, every `area:*` touched, `from-recette` when found while using the product, `lock:migration` only if the fix needs a schema change. Priority: **Urgent** if data is lost or wrong, a main journey is blocked, or security is involved; **High** if a feature is unusable with a workaround; **Medium** otherwise. Project: the module's project, else « Correctifs de l'inventaire fonctionnel ».
5. **Answer in one line**: the ticket link and its priority. The dispatcher hands it to Cyrus, who runs `/fix-bug`.
