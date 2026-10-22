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

describe('CustomMenu — the task settings entry', () => {
  test('sits under Paramètres, after Préférences, and opens /settings/tasks', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const entries = (await screen.findAllByRole('menuitem')).map((item) => item.getAttribute('href'))
    expect(entries).toContain('/settings/tasks')
    expect(entries.indexOf('/settings/tasks')).toBe(entries.indexOf('/settings/preferences') + 1)
  })

  test('is followed by « Ce qui passe en premier », which opens /settings/tasks/order', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const order = await screen.findByRole('menuitem', { name: 'Ce qui passe en premier' })
    expect(order).toHaveAttribute('href', '/settings/tasks/order')
    const entries = screen.getAllByRole('menuitem').map((item) => item.getAttribute('href'))
    expect(entries.indexOf('/settings/tasks/order')).toBe(entries.indexOf('/settings/tasks') + 1)
  })
})

describe('CustomMenu — the meal settings entry', () => {
  test('sits under Paramètres, after Ce qui passe en premier, and opens /settings/meals', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const entries = (await screen.findAllByRole('menuitem')).map((item) => item.getAttribute('href'))
    expect(entries.indexOf('/settings/meals')).toBe(entries.indexOf('/settings/tasks/order') + 1)
  })
})

describe('CustomMenu — Accueil', () => {
  test('is the first entry and leads to the Accueil', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const [first] = screen.getAllByRole('menuitem')
    expect(first).toHaveTextContent('Accueil')
    expect(first).toHaveAttribute('href', '/')
    expect(screen.queryByText('Tableau de bord')).toBeNull()
  })
})

describe('CustomMenu — Finance', () => {
  const renderFinanceMenu = async (path = '/') => {
    stubPersonality('Maggie')
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={[path]}>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
          <CustomMenu />
        </AdminContext>
      </MemoryRouter>,
    )
    await user.click(screen.getByText('Finance'))

    return user
  }

  test('opens on seven menus, in order, each on its own page', async () => {
    await renderFinanceMenu()

    const entries = [
      ['Dashboard', '/finance/dashboard'],
      ['Comptes', '/finance/summary/accounts'],
      ['Revue', '/finance/summary/review'],
      ['Budget', '/finance/summary/budget'],
      ['Trésorerie', '/finance/summary/treasury'],
      ['Planification', '/finance/summary/planning'],
      ['Configuration', '/finance/summary/settings'],
    ]
    for (const [name, href] of entries) {
      expect(await screen.findByRole('menuitem', { name })).toHaveAttribute('href', href)
    }

    const hrefs = screen.getAllByRole('menuitem').map((item) => item.getAttribute('href'))
    const finance = hrefs.slice(hrefs.indexOf('/finance/dashboard'))
    expect(finance.slice(0, 7)).toEqual(entries.map(([, href]) => href))
  })

  test('no longer lists the screens one by one', async () => {
    await renderFinanceMenu()
    await screen.findByRole('menuitem', { name: 'Trésorerie' })

    for (const old of ['Banques', 'Matelas', "Avances d'épargne", 'Prêts', 'Import de relevé', 'Revue mensuelle', "Vue d'ensemble"]) {
      expect(screen.queryByRole('menuitem', { name: old })).toBeNull()
    }
  })

  test('highlights the menu the page belongs to, not only the one whose route it is', async () => {
    await renderFinanceMenu('/finance/cushion')

    expect(await screen.findByRole('menuitem', { name: 'Trésorerie' })).toHaveClass('Mui-selected')
    expect(screen.getByRole('menuitem', { name: 'Dashboard' })).not.toHaveClass('Mui-selected')
  })

  test('highlights nothing in Finance on another module', async () => {
    await renderFinanceMenu('/calendar')

    await screen.findByRole('menuitem', { name: 'Trésorerie' })
    expect(screen.getByRole('menuitem', { name: 'Dashboard' })).not.toHaveClass('Mui-selected')
    expect(screen.getByRole('menuitem', { name: 'Comptes' })).not.toHaveClass('Mui-selected')
  })
})
