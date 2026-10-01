export interface ContextState {
  id: string
  label: string
  status: 'active' | 'dormant' | 'closed'
  /** What the thread is about, written by Maggie once it is long enough (MAG-11). */
  summary?: string | null
}

export interface ToolCallState {
  toolCallId: string
  toolName: string
  status: 'running' | 'success' | 'error'
}

export type AgentState = 'idle' | 'thinking' | 'acting'
