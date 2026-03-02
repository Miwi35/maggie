import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MindPanel } from './MindPanel'
import type { ContextState, ToolCallState } from './types'

const defaultProps = {
  open: true,
  contexts: [] as ContextState[],
  toolCalls: [] as ToolCallState[],
  agentState: 'idle' as const,
  onClose: vi.fn(),
}

describe('MindPanel', () => {
  test('renders header when open', () => {
    render(<MindPanel {...defaultProps} />)
    expect(screen.getByText("Maggie's Mind")).toBeInTheDocument()
  })

  test('renders section labels', () => {
    render(<MindPanel {...defaultProps} />)
    expect(screen.getByText('Contextes')).toBeInTheDocument()
    expect(screen.getByText('Activité')).toBeInTheDocument()
  })

  test('shows empty states when no data', () => {
    render(<MindPanel {...defaultProps} />)
    expect(screen.getByText('Aucun contexte actif')).toBeInTheDocument()
    expect(screen.getByText('Aucune activité')).toBeInTheDocument()
  })

  test('renders contexts', () => {
    const contexts: ContextState[] = [
      { id: '1', label: 'Liste de courses', status: 'active' },
      { id: '2', label: 'Agenda semaine', status: 'dormant' },
    ]
    render(<MindPanel {...defaultProps} contexts={contexts} />)
    expect(screen.getByText('Liste de courses')).toBeInTheDocument()
    expect(screen.getByText('Agenda semaine')).toBeInTheDocument()
  })

  test('renders tool calls', () => {
    const toolCalls: ToolCallState[] = [
      { toolCallId: 'tc1', toolName: 'add_grocery_item', status: 'success' },
      { toolCallId: 'tc2', toolName: 'get_events', status: 'running' },
    ]
    render(<MindPanel {...defaultProps} toolCalls={toolCalls} />)
    expect(screen.getByText('add_grocery_item')).toBeInTheDocument()
    expect(screen.getByText('get_events')).toBeInTheDocument()
  })

  test('shows activity pulse when thinking', () => {
    const { container } = render(<MindPanel {...defaultProps} agentState="thinking" />)
    // The pulse is a small Box with animation
    const pulseElements = container.querySelectorAll('[class*="MuiBox-root"]')
    expect(pulseElements.length).toBeGreaterThan(0)
  })
})
