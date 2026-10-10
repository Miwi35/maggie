export interface ContextState {
  id: string
  label: string
  status: 'active' | 'dormant' | 'closed'
  /** What the thread is about, written by Maggie once it is long enough (MAG-11). */
  summary?: string | null
  /**
   * How many messages the thread holds — what deleting it takes with it (MAG-342).
   * Only `GET /agent/contexts` carries it; a Mercure update keeps the last known count.
   */
  messageCount?: number
}

export interface ToolCallState {
  toolCallId: string
  toolName: string
  status: 'running' | 'success' | 'error'
}

export type AgentState = 'idle' | 'thinking' | 'acting'
