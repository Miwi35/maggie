# Maggie regarde l'écran — Plan

## Task 1: Save Spec Documentation

This folder: `shape.md` (the data policy), `references.md`, `standards.md`.

## Task 2: The agent takes an image on the turn

- `ChatRequest.image: {media_type, data}` on `POST /agent/chat` and `/agent/chat/stream`;
  media type in jpeg/png/webp, base64 valid, ≤ 5 MB decoded, else 422.
- `agent_message.has_image` (boolean, `ALTER … IF NOT EXISTS`), `hasImage` in `to_dict()`.
- `build_history(image=…)`: the last user turn becomes `[image block, text block]`; an
  earlier stored turn with `has_image` gets the « plus disponible » marker.
- Fake LLM: `user_has_image: true` match key; `last_user_text` reads the text block of a
  list content; `turn_index` counts tool-result batches only. Scenario
  `03-screen-image.yaml`.
- One info log line per image (type, size), never the bytes.

Tests it owes: route 401, 422 (bad type, bad base64, too big), happy path asserting the
stored row (`has_image` true, no image anywhere) and the gateway call; history image
block + marker; fake LLM matching, text and turn index on an image turn.

## Task 3: The phone sends the screenshot

- `onHandleScreenshot` keeps the bitmap: downscaled to 1568 px long side, JPEG 80, written
  to `cacheDir/assist/`; `ScreenContext.screenshotPath` replaces `hasScreenshot`.
- With a screenshot, `toPromptBlock()` carries the app and the domain only.
- `ChatViewModel.sendMessage(text, screenContext, image)` → `sendChatStream(…, image)`;
  the file is deleted once read, or when the overlay closes without sending.
- Overlay shows the screenshot thumbnail under « Contexte » ; `MessageBubble` shows the
  thumbnail (session memory) or « Capture d'écran (non conservée) » from `hasImage`.
- e2e flavor: `maggie-e2e-assist://overlay?screenshot=fixture` loads a bundled image.

Tests it owes: `ScreenContextTest` (block without texts, intent round trip),
`ScreenshotEncoder` (downscale, never upscale), `ChatViewModelTest` (image sent once,
thumbnail kept, pending matched), `MaggieApiServiceTest` (serialization), bubble render.

## Task 4: The web says a picture was there

`ChatWidget` user bubble shows « Capture d'écran (non conservée) » when `hasImage`.
Tests it owes: render with and without `hasImage`.

## Tests

| Unit | Tests |
|---|---|
| `routes.chat` / `chat_stream` | 401, 422 ×3, happy path asserting DB row and gateway call |
| `history.build_history` | image block on the current turn only, marker on an old one |
| `fake.py` | `user_has_image`, `last_user_text`, `turn_index` on an image turn |
| `ScreenContext`, `ScreenshotEncoder` | block, intent round trip, downscale |
| `ChatViewModel` | image sent with the first sentence only, thumbnail, echo match |
| `MessageBubble` / `ChatWidget` | thumbnail, « non conservée » |

## E2E journey

**Extends:** MAG-98 — Maestro (`e2e/mobile/flows/02-voice-overlay.yaml`)

- **Given** the e2e stack with `LLM_PROVIDER=fake` and `03-screen-image.yaml`, which only
  matches a turn carrying an image
- **When** the overlay opens through `maggie-e2e-assist://overlay?screenshot=fixture` and
  the user holds the mic and speaks
- **Then** the scenario's answer « Sur votre écran, je vois une cafetière italienne » is
  shown, and `GET /agent/e2e/llm/images` (fake provider only) counts one image received
  by the model. That the agent keeps none after the turn is structural — no column, file
  or cache can hold it — and asserted on the stored row by the route's pytest.

## Definition of Done

- [ ] Unit/integration tests above, green
- [ ] E2E journey executable (Maestro)
- [ ] CI green on a PR linking the ticket
- [ ] Module functional spec + user guide updated in Linear (ADR-006)
