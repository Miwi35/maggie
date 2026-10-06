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
  /** `pending_approval`: the policy held the call back until the user answers it (MAG-4). */
  status: 'running' | 'success' | 'error' | 'pending_approval'
}

export type AgentState = 'idle' | 'thinking' | 'acting'
