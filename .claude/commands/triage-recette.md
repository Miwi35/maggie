# Triage Recette

Close the loop on an acceptance: read what the owner left on the tickets in « Recette » after trying them in production, and turn every piece of it into a ticket, a rule proposal or a Done. Linear only — this command changes no code and writes no standard.

## Usage

```
/triage-recette [MAG-N …]
```

Without an argument, every ticket of team Maggie in « Recette ». With keys, only those.

## Steps

1. **Run `task guard:enabled`** (exit 20: the emergency stop is on — say so and stop, a stopped agent does not fill the backlog).
2. **List the tickets in « Recette »** (`list_issues`, team Maggie, state `Recette`), then for each: `get_issue`, whose `stateHistory` gives the moments it entered Recette, and `list_comments` (`orderBy: createdAt`, the bounds below are creation times) for the whole thread, replies included.
3. **Keep the owner's feedback only** — the comments whose `author` **or** `onBehalfOf` is the owner, the only human of team Maggie (`list_users`; do not take the ticket's creator as a proxy, half the backlog is written by agents and so are the tickets this command creates). A voice note dictated to Maggie arrives written out, with her as `author` and the owner as `onBehalfOf`: reading `author` alone would drop one of the three channels he uses. Keep those created **after** the later of these two:
   - the last comment whose first line is exactly `## Triage de recette`, if there is one. This bound is what makes the command safe to re-run: what a previous pass answered is never answered twice;
   - otherwise the **first** entry into « Recette » in `stateHistory` — the first, not the latest: a red deploy sends a ticket to « Emergency » and a green one brings it back, and the latest entry would silently drop the feedback left before the incident.

   A ticket carrying `needs-human` from a previous pass, whose owner has since commented, loses the label here, before anything else.

   A ticket with no feedback of its own is still being tested: leave it untouched and list it as « en attente » in the report. Images pasted in a comment are read with `extract_images` on its body; files attached to it come as download URLs in `list_comments`.
4. **Split each comment into items.** « le bouton est trop petit et la liste ne se rafraîchit pas » is two. Keep the owner's own words for each: they go verbatim into whatever you create, as the *Constat*.
5. **Classify every item and act on it.** Decide by default; ask only when the words give you nothing to act on (`CLAUDE.md` › Autonomy).

   | The item says | What you create |
   |---|---|
   | « OK », « c'est bon », nothing but approval | nothing |
   | the app does something wrong, here | a `Bug` ticket, written exactly as `/report-bug` describes (*Constat*, *Attendu*, *Reproduction*, *Piste*, *Test de reproduction attendu*, *Hors périmètre*) |
   | something is missing or awkward, in this delivery | a `Feature` ticket (`CLAUDE.md` › Creating Linear tickets), plus `needs-shaping` unless the gap is small enough for a plan comment |
   | a rule that would hold for every module — « toujours confirmer avant de supprimer », « les listes doivent… » | a proposal, step 6 |

   **Look for a duplicate before creating anything**: a recette often repeats a known gap. A duplicate earns a comment on the existing ticket, not a twin.

   Each ticket of this step carries `from-recette`, `agent-ready` — the owner has just said what he expects, so the criteria need no round trip, `needs-shaping` or not — its type label, one `area:*` per component touched, a priority and the module's project, and is `relatedTo` the ticket being accepted.
6. **A rule is proposed, never written.**
   - **UX** (screens, navigation, wording, touch targets, empty and error states) → a comment on [MAG-90](https://linear.app/meven/issue/MAG-90/regles-ux-transverses-appliquees-a-tous-les-modules): the owner's words, the rule in one sentence, and which modules are already off it.
   - **Engineering or process** → a `Task` ticket, labels `from-recette` and `needs-human` — and **no** `agent-ready`, the two contradict each other — naming the `agent-os/standards/…` file it targets and quoting the wording proposed. The owner validates by removing `needs-human`; the standard is then written through `/discover-standards`, which needs him in the loop.

   This command never edits `agent-os/standards/`, `CLAUDE.md` or `.claude/`.
7. **Every bug found in recette buys an e2e case.** Its *Parcours e2e* section is Given/When/Then and names the journey ticket (MAG-99…103) it extends; add the scenario as a line on that journey ticket when it is still open. A bug the owner found by hand is first of all a hole in a journey.
8. **Settle the accepted ticket**, then post on it one comment whose first line is exactly `## Triage de recette`, 10 lines at most — what was read, what was created, what is proposed.

   | After triage | The ticket |
   |---|---|
   | every item classified, whatever came out of it | goes to **Done** — the follow-ups carry what is left, linked by `relatedTo`, and reach their own Recette |
   | an item nobody can classify | **stays in Recette**, `needs-human`, the question asked in its thread; the next pass removes the label when the owner has answered |

   A ticket in « Emergency » is not in Recette and is never touched here.
9. **Report in one line per ticket**: its key, where it went, and the keys created. The dispatcher picks the new tickets up on its own.
