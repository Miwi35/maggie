# References for Maggie regarde l'écran

### MAG-30's screen context

- **Location:** `agent/app/llm/screen_context.py`, `agent/app/llm/history.py` (`_turns`),
  `mobile/app/src/main/java/com/maggie/app/voice/ScreenContext.kt`, `AssistantActivity.kt`
- **Relevance:** the channel the image rides on — its own request field, attached to the
  turn being answered only, never stored.

### Audio upload for Whisper

- **Location:** `agent/app/api/routes.py` (`transcribe`), `MaggieApiService.transcribe`
- **Relevance:** the size guard and the 400/422 on an empty or oversized upload.

### Fake TTS / transcription counters

- **Location:** `agent/app/e2e.py`, `e2e/mobile/flows/02-voice-overlay.yaml`
- **Relevance:** how a Maestro flow asks the agent what happened on the server side.
