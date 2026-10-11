# Maggie regarde l'écran — Shaping Notes

Shaped autonomously from MAG-214, the owner's decision of 7 October on the ticket, and the
code MAG-30 left. No question was left open: the ticket asks the agent to decide and write
the data policy, so it is decided here and posted on the ticket.

## Scope

MAG-30 receives the screenshot in `onHandleScreenshot` and throws it away; the model is told
« seule une image est disponible ». This ticket carries the image end to end: the phone
sends the screenshot with the next question, the agent puts it on the turn being answered
as a Claude image block, and nothing keeps it after that turn.

Out of scope, filed as a follow-up: **selecting a part of the screen** (owner's decision 1,
« Entourer pour rechercher »). It is a UI of its own — a full-screen editor on the overlay —
and lands on the channel built here: without a selection the whole screen goes, which is
exactly what this ticket ships. Continuous capture and sharing from another app: MAG-31.

## Decisions — the data policy

### 1. What travels, and its size

- **Dilemma:** a raw screenshot is 1080×2400 PNG, ~2–4 MB.
- **Options:** send it raw · downscale on the phone · let the server resize.
- **Choice:** the phone downscales to **1568 px on the long side** (never upscales) and
  encodes **JPEG quality 80** (~150–300 KB). The agent accepts `image/jpeg`, `image/png`,
  `image/webp`, **5 MB decoded at most**, else 422.
- **Why:** 1568 px is the size above which Claude downscales anyway, so sending more pays
  for bytes the model never sees; 5 MB is the Anthropic API's own ceiling per image.

### 2. Where it lives, and for how long

- **Dilemma:** the ticket's rule is « plus accessible après le tour ».
- **Options:** store it (DB, disk, object storage) with a TTL · keep it in the request only.
- **Choice:** **never stored.** The image is a field of the chat request (`image:
  {media_type, data}` base64, JSON like the rest of the stream request), lives in the
  request's memory for the turn — routing, tool loop, answer — and is gone when the
  request ends. The agent stores one flag, `agent_message.has_image`, so every reader of
  the history knows a picture was there. On the phone, the JPEG is a file in the app's
  cache, deleted once sent or when the overlay closes without sending; the bubble's
  thumbnail is held in memory for the session only.
- **Why:** a screenshot can hold anything (bank, messages, health). A store with a TTL is a
  store that a bug, a backup or a crash keeps forever; nothing stored is the only rule
  that holds by construction. The flag costs a boolean column and keeps later turns honest
  (the model reads « [capture d'écran jointe, plus disponible] » instead of a question
  about nothing).

### 3. What reaches the model

- **Choice (owner's decision 2):** with a screenshot, the `AssistStructure` text block is
  no longer sent: the screen block carries the app and the page's domain only, and the
  image carries the content. Without a screenshot (the user turned off « Utiliser une
  capture d'écran » in Android's assistant settings) the text block stays, as in MAG-30.
  The sentence « je ne sais pas encore la regarder » goes.
- The image goes on the turn being answered only, before its text (Claude reads an image
  first best). Earlier image turns are replayed as text with the marker above.

### 4. What is logged

- **Choice:** never the bytes, never base64. One info line per image: media type and
  decoded size. The fake LLM counts image turns without keeping them.

### 5. When it is sent

- **Choice:** only on the sentence the user says after summoning the assistant — the
  screenshot rides on the same one-shot `pendingContext` as MAG-30's text. Closing the
  overlay without speaking sends nothing and deletes the file.

### 6. The bubble

- **Choice (owner's decision 3):** the user bubble shows the thumbnail, then the question;
  never the context as text. After a reload, or on the web, the image no longer exists:
  the bubble shows « Capture d'écran (non conservée) » from `hasImage`.

## Context

- **Visuals:** none.
- **References:** see `references.md`.
- **Product alignment:** `mission.md` — Maggie as a voice-first assistant that sees what the
  user sees; no conflict.

## Standards Applied

- global/testing — Definition of Done, every unit touched has its test, e2e journey.
- agent/architecture, agent/testing — the chat routes, history, fake LLM.
- mobile/android-app, mobile/testing, mobile/screen-tests — the overlay, the bubble, the VM.
- global/e2e-environment — the fake LLM scenario and the Maestro flow.
