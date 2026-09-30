# Standards

- Kotlin data classes with `@Serializable` for all models
- Koin DI: `single {}` for repositories, `viewModel {}` for ViewModels
- Repository Pattern B (API-only with `Result<>` wrapper) for contexts
- ViewModel: `MutableStateFlow<UiState>` pattern with sealed state
- Mercure: `MercureService.subscribe(topic)` returns `Flow<MercureEvent>`
- AG-UI SSE: `data: {JSON}\n\n` wire format, type field inside JSON
- CUSTOM events: dispatched by `name` field (`context_update`, `tool_result`)
