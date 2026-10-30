import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '@mui/material/styles'
import { veilleuseDarkTheme } from '../../theme'
import { ProviderBanner } from './ProviderBanner'

const answer = (body: unknown, ok = true) =>
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok, json: () => Promise.resolve(body) }))

const show = () =>
  render(
    <ThemeProvider theme={veilleuseDarkTheme}>
      <ProviderBanner />
    </ThemeProvider>,
  )

afterEach(() => vi.unstubAllGlobals())

describe('ProviderBanner', () => {
  it('shows the empty credit with « Recharger » to the billing page', async () => {
    answer({ status: 'credit', since: '2026-10-30T08:00:00+00:00' })
    show()

    expect(await screen.findByText('Maggie ne peut plus répondre')).toBeInTheDocument()
    expect(screen.getByText('Crédit Anthropic épuisé')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Recharger' })).toHaveAttribute(
      'href',
      'https://console.anthropic.com/settings/billing',
    )
  })

  it('shows the refused key with « Ouvrir la console » to the keys page', async () => {
    answer({ status: 'auth', since: '2026-10-30T08:00:00+00:00' })
    show()

    expect(await screen.findByText('Clé Anthropic refusée')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ouvrir la console' })).toHaveAttribute(
      'href',
      'https://console.anthropic.com/settings/keys',
    )
  })

  it('shows nothing when the provider answers', async () => {
    answer({ status: 'ok', since: null })
    show()

    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/agent/provider-status', expect.anything()))
    expect(screen.queryByTestId('provider-banner')).not.toBeInTheDocument()
  })

  it('shows nothing when the status cannot be read', async () => {
    answer(null, false)
    show()

    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(screen.queryByTestId('provider-banner')).not.toBeInTheDocument()
  })
})
