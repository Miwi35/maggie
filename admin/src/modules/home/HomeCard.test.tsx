import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { HomeActionCard, HomeCard } from './HomeCard'
import type { ReactElement } from 'react'

const Where = () => <output data-testid="where">{useLocation().pathname}</output>

const renderCard = (ui: ReactElement) =>
  render(
    <MemoryRouter initialEntries={['/']}>
      <ThemeProvider theme={veilleuseLightTheme}>
        {ui}
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

describe('HomeCard', () => {
  test('draws its title as a link to the module and its content below', () => {
    renderCard(
      <HomeCard module="finance" title="Finance" to="/finance/dashboard">
        <p>Contenu libre</p>
      </HomeCard>,
    )

    expect(screen.getByRole('heading', { name: 'Finance' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Finance' })).toHaveAttribute('href', '/finance/dashboard')
    expect(screen.getByText('Contenu libre')).toBeInTheDocument()
  })

  test('opens the module from its header, with the mouse or the keyboard', async () => {
    const user = userEvent.setup()
    renderCard(<HomeCard module="tasks" title="Tâches" to="/tasks" />)

    await user.click(screen.getByRole('link', { name: 'Tâches' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/tasks')
  })

  test('is reachable by Tab and opens the module on Enter', async () => {
    const user = userEvent.setup()
    renderCard(<HomeCard module="grocery" title="Courses" to="/grocery" />)

    await user.tab()
    expect(screen.getByRole('link', { name: 'Courses' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('where')).toHaveTextContent('/grocery')
  })
})

describe('HomeActionCard', () => {
  test('shows its title, its moment and a button that calls the handler', async () => {
    const user = userEvent.setup()
    const onAction = vi.fn()
    renderCard(
      <HomeActionCard
        module="finance"
        icon={<svg data-testid="icon" />}
        title="Revue du mois"
        moment="ce soir"
        actionLabel="Commencer"
        onAction={onAction}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Revue du mois' })).toBeInTheDocument()
    expect(screen.getByText('ce soir')).toBeInTheDocument()
    expect(screen.getByTestId('icon')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Commencer' }))

    expect(onAction).toHaveBeenCalledTimes(1)
  })
})
