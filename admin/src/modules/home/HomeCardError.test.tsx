import { test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HomeCardError } from './HomeCardError'

test('says the card could not be loaded and offers to try again', async () => {
  const user = userEvent.setup()
  const onRetry = vi.fn()
  render(<HomeCardError onRetry={onRetry} />)

  expect(screen.getByRole('alert')).toHaveTextContent('Impossible de charger cette carte.')
  await user.click(screen.getByRole('button', { name: 'Réessayer' }))

  expect(onRetry).toHaveBeenCalledTimes(1)
})
