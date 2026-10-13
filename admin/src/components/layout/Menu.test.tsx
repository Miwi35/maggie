import { describe, test, expect, afterEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { CustomMenu } from './Menu'
import { Breadcrumbs } from './Breadcrumbs'
import { announceAgentName } from '../../hooks/useAgentName'
import { veilleuseLightTheme } from '../../theme'

const stubPersonality = (name: string | null) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      name === null
        ? new Response('down', { status: 503 })
        : new Response(JSON.stringify({ name, language: 'fr', backstory: '' })),
    ),
  )

const renderMenu = async () => {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={['/']}>
      <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
        <CustomMenu />
      </AdminContext>
    </MemoryRouter>,
  )
  await user.click(screen.getByText('Paramètres'))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CustomMenu — the agent entry', () => {
  test('is called Maggie by default and opens the agent settings', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    expect(await screen.findByRole('menuitem', { name: 'Maggie' })).toHaveAttribute('href', '/settings/agent')
    expect(screen.queryByRole('menuitem', { name: 'Agent' })).toBeNull()
  })

  test('follows the name the user gave her', async () => {
    stubPersonality('Alfred')
    await renderMenu()

    expect(await screen.findByRole('menuitem', { name: 'Alfred' })).toHaveAttribute('href', '/settings/agent')
    expect(screen.queryByRole('menuitem', { name: 'Maggie' })).toBeNull()
  })

  test('stays Maggie when the agent does not answer', async () => {
    stubPersonality(null)
    await renderMenu()

    expect(await screen.findByRole('menuitem', { name: 'Maggie' })).toBeInTheDocument()
  })

  test('stays Maggie when the name is blank', async () => {
    stubPersonality('   ')
    await renderMenu()

    expect(await screen.findByRole('menuitem', { name: 'Maggie' })).toBeInTheDocument()
  })

  test('is renamed as soon as the personality is saved under another name', async () => {
    stubPersonality('Maggie')
    await renderMenu()
    await screen.findByRole('menuitem', { name: 'Maggie' })

    act(() => announceAgentName('Alfred'))

    expect(await screen.findByRole('menuitem', { name: 'Alfred' })).toBeInTheDocument()
  })

  test('the breadcrumb of the agent settings carries the same name', async () => {
    stubPersonality('Alfred')
    render(
      <MemoryRouter initialEntries={['/settings/agent']}>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
          <Breadcrumbs />
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Alfred')).toHaveAttribute('aria-current', 'page')
  })
})
