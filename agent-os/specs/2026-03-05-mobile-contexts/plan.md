# Mobile Contexts + AG-UI Streaming Migration

## Goals
1. Display conversation contexts in mobile app via brain icon + ModalBottomSheet
2. Migrate mobile chat from non-streaming POST to AG-UI streaming endpoint
3. Bridge context_update stream events to context UI in real-time

## Phases
- **Phase A**: Context display (data model, API, repository, ViewModel, UI, wiring)
- **Phase B**: AG-UI streaming (event model, SSE parser, streaming chat, UI updates)
- **Phase C**: Testing + build verification
