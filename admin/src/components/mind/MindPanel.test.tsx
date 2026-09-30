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

  // The chat journey counts these to tell a change of subject from a follow-up
  // (MAG-99), and the section has to be addressable even while it is empty —
  // otherwise "no context yet" and "the panel never rendered" look alike.
  test('marks the section and each context for the e2e journeys', () => {
    const { rerender } = render(<ContextList contexts={[]} />)
    expect(screen.getByTestId('mind-contexts')).toBeInTheDocument()

    rerender(<ContextList contexts={[{ id: '1', label: 'Budget e2e', status: 'dormant' }]} />)
    expect(screen.getByTestId('mind-contexts')).toBeInTheDocument()
    expect(screen.getByTestId('mind-context')).toHaveAttribute('data-status', 'dormant')
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

  // A tool's outcome is an icon with no accessible name, so `data-status` is
  // the only way a journey can tell "Maggie called create_event" from
  // "create_event worked" (MAG-99).
  test('exposes each tool call and its outcome to the e2e journeys', () => {
    const toolCalls: ToolCallState[] = [
      { toolCallId: 'tc1', toolName: 'create_event', status: 'success' },
      { toolCallId: 'tc2', toolName: 'get_events', status: 'error' },
    ]
    render(<ToolCallList toolCalls={toolCalls} />)

    const rows = screen.getAllByTestId('mind-tool-call')
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual(['success', 'error'])
    expect(screen.getByTestId('mind-activity')).toBeInTheDocument()
  })

  test('marks the section even when there is nothing to show', () => {
    render(<ToolCallList toolCalls={[]} />)
    expect(screen.getByTestId('mind-activity')).toBeInTheDocument()
  })
})
