import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ContextList } from './ContextList'
import { ToolCallList } from './ToolCallList'
import type { ContextState, ToolCallState } from './types'

describe('ContextList', () => {
  test('shows empty state when no contexts', () => {
    render(<ContextList contexts={[]} />)
    expect(screen.getByText('Aucun contexte actif')).toBeInTheDocument()
  })

  test('renders contexts', () => {
    const contexts: ContextState[] = [
      { id: '1', label: 'Liste de courses', status: 'active' },
      { id: '2', label: 'Agenda semaine', status: 'dormant' },
    ]
    render(<ContextList contexts={contexts} />)
    expect(screen.getByText('Liste de courses')).toBeInTheDocument()
    expect(screen.getByText('Agenda semaine')).toBeInTheDocument()
  })
})

describe('ToolCallList', () => {
  test('shows empty state when no tool calls', () => {
    render(<ToolCallList toolCalls={[]} />)
    expect(screen.getByText('Aucune activité')).toBeInTheDocument()
  })

  test('renders tool calls', () => {
    const toolCalls: ToolCallState[] = [
      { toolCallId: 'tc1', toolName: 'add_grocery_item', status: 'success' },
      { toolCallId: 'tc2', toolName: 'get_events', status: 'running' },
    ]
    render(<ToolCallList toolCalls={toolCalls} />)
    expect(screen.getByText('add_grocery_item')).toBeInTheDocument()
    expect(screen.getByText('get_events')).toBeInTheDocument()
  })
})
