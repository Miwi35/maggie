# Shape

## Context Display
- Brain icon (Psychology) in ChatBottomBar with badge showing active context count
- ModalBottomSheet at 50% height with status-colored context list
- Status indicators: green (active), orange (dormant), grey (closed)

## AG-UI Streaming
- Sealed class AgUiEvent modeling all server event types
- SSE parser converting ByteReadChannel lines to Flow<AgUiEvent>
- Token-by-token text display via StreamingMessage ChatListItem
- Context updates bridged from ChatViewModel to ContextViewModel via SharedFlow
