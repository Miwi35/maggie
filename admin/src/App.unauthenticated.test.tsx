import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import docs from './test/fixtures/hydra-docs.json'
import entrypoint from './test/fixtures/hydra-entrypoint.json'
import entrypointContext from './test/fixtures/hydra-entrypoint-context.json'

// The real App (real react-admin, real HydraAdmin, real authProvider) against
// recorded Hydra responses: only the network is faked. MAG-140: a visitor
// without a valid session stared at a blank page for 7 to 25 s before the login
// screen, while react-query retried the rejected auth checks.
const LOGIN_BUDGET_MS = 3000

const hydraHeaders = {
  'Content-Type': 'application/ld+json',
  Link: '<http://localhost/api/docs.jsonld>; rel="http://www.w3.org/ns/hydra/core#apiDocumentation"',
}

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input instanceof Request ? input.url : input)
      let body: unknown = entrypoint
      if (url.includes('docs.jsonld')) body = docs
      if (url.includes('/contexts/')) body = entrypointContext
      return new Response(JSON.stringify(body), { status: 200, headers: hydraHeaders })
    }),
  )
}

function fakeJwt(exp: number): string {
  return `e30.${btoa(JSON.stringify({ exp }))}.sig`
}

async function renderApp() {
  vi.resetModules()
  const { authProvider } = await import('./auth/authProvider')
  const logout = vi.spyOn(authProvider, 'logout')
  const { default: App } = await import('./App')
  render(<App />)
  return { logout }
}

describe('App without a valid session', () => {
  beforeEach(() => {
    localStorage.clear()
    window.location.hash = '#/'
    stubApi()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  test.each([
    ['no session', () => undefined],
    ['an expired token', () => localStorage.setItem('token', fakeJwt(1))],
    ['a malformed token', () => localStorage.setItem('token', 'not-a-jwt')],
  ])('shows the login screen within the budget with %s', async (_label, setup) => {
    setup()
    const { logout } = await renderApp()

    expect(
      await screen.findByText('Connectez-vous pour continuer', {}, { timeout: LOGIN_BUDGET_MS }),
    ).toBeInTheDocument()
    // React-admin's requireAuth gate logs out on mount, and every logout clears
    // the query cache, which re-runs the failing auth check, which logs out
    // again: a livelock that starves the router until it has looped thousands of
    // times. A visitor with no valid session must land on /login directly.
    expect(logout).not.toHaveBeenCalled()
  }, 30_000)
})
