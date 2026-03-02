export interface ContextState {
  id: string
  label: string
  status: 'active' | 'dormant' | 'closed'
}

export interface ToolCallState {
  toolCallId: string
  toolName: string
  status: 'running' | 'success' | 'error'
}

export type AgentState = 'idle' | 'thinking' | 'acting'
