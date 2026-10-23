import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeProvider } from '@mui/material/styles'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { veilleuseLightTheme } from '../../theme'
import { REVIEW_ACTIVITY, REVIEW_ACTIVITY_PATH } from '../finance/reviewActivity'
import { ActivityLaunchButton, launchTargetFor } from './ActivityLaunchButton'
import type { HomeDay } from './homeDay'

const Where = () => {
  const { pathname, search, state } = useLocation()

  return (
    <>
      <output data-testid="where">{pathname + search}</output>
      <output data-testid="from">{(state as { from?: string } | null)?.from ?? ''}</output>
    </>
  )
}

const renderButton = (day: HomeDay, entry = '/') =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <ThemeProvider theme={veilleuseLightTheme}>
        <ActivityLaunchButton module="finance" activity="la revue d'octobre" day={day} to={REVIEW_ACTIVITY_PATH} />
        <Where />
      </ThemeProvider>
    </MemoryRouter>,
  )

describe('ActivityLaunchButton', () => {
  test('is a link named « Lancer <activity> » that shows « Lancer »', () => {
    renderButton('today')

    const link = screen.getByRole('link', { name: "Lancer la revue d'octobre" })
    expect(link).toHaveAttribute('href', REVIEW_ACTIVITY_PATH)
    expect(link).toHaveTextContent('Lancer')
  })

  test('keeps a 44 px touch target around a 28 px pill', () => {
    renderButton('today')

    const link = screen.getByTestId('home-launch-finance')
    expect(link).toHaveStyle({ minHeight: '44px' })
    expect(link.querySelector('.pill')).toHaveStyle({ height: '28px', fontSize: '13px' })
  })

  test('is absent for tomorrow', () => {
    renderButton('tomorrow')

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  test('opens the activity and remembers the Accueil, on its day, to come back to', async () => {
    const user = userEvent.setup()
    renderButton('today', '/?foo=bar')

    await user.click(screen.getByRole('link', { name: "Lancer la revue d'octobre" }))

    expect(screen.getByTestId('where')).toHaveTextContent(REVIEW_ACTIVITY_PATH)
    expect(screen.getByTestId('from')).toHaveTextContent('/?foo=bar')
  })

  test('is reachable by Tab and opens on Enter', async () => {
    const user = userEvent.setup()
    renderButton('today')

    await user.tab()
    expect(screen.getByRole('link', { name: "Lancer la revue d'octobre" })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByTestId('where')).toHaveTextContent(REVIEW_ACTIVITY_PATH)
  })
})

describe('launchTargetFor', () => {
  test('the review appointment launches the Comptes activity', () => {
    expect(launchTargetFor(REVIEW_ACTIVITY)).toEqual({ module: 'finance', path: REVIEW_ACTIVITY_PATH })
  })

  test.each([null, undefined, '', 'something-else'])('%j is not an activity', (activity) => {
    expect(launchTargetFor(activity)).toBeNull()
  })
})
