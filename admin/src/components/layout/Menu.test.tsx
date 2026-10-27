import { describe, test, expect, afterEach, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, memoryStore, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { CustomMenu } from './Menu'
import { Breadcrumbs, PageHeading } from './Breadcrumbs'
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

const renderMenuAt = (path: string) => {
  const user = userEvent.setup()
  render(
    <MemoryRouter initialEntries={[path]}>
      <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
        <CustomMenu />
      </AdminContext>
    </MemoryRouter>,
  )

  return user
}

const renderMenu = async () => {
  renderMenuAt('/settings/preferences')
}

const hrefs = () => screen.getAllByRole('menuitem').map((item) => item.getAttribute('href'))

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

  test('the h1 of the agent settings carries the same name', async () => {
    stubPersonality('Alfred')
    render(
      <MemoryRouter initialEntries={['/settings/agent']}>
        <AdminContext dataProvider={testDataProvider()} theme={veilleuseLightTheme} store={memoryStore()}>
          <PageHeading />
        </AdminContext>
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { level: 1, name: 'Alfred' })).toBeInTheDocument()
  })
})

describe('CustomMenu — the header', () => {
  test("wears Maggie's face, 32 px, beside her name", async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const header = screen.getByTestId('menu-header')
    const avatar = within(header).getByTestId('maggie-avatar')

    expect(avatar).toHaveStyle({ width: '32px', height: '32px' })
    expect(avatar.querySelector('img')).toHaveAttribute('src', '/admin/maggie.png')
    expect(await within(header).findByText('Maggie')).toBeInTheDocument()
  })
})

describe('CustomMenu — the task settings entry', () => {
  test('sits under Réglages, after Préférences, and opens /settings/tasks', async () => {
    stubPersonality('Maggie')
    await renderMenu()

    const entries = (await screen.findAllByRole('menuitem')).map((item) => item.getAttribute('href'))
    expect(entries).toContain('/settings/tasks')
    expect(entries.indexOf('/settings/tasks')).toBe(entries.lastIndexOf('/settings/preferences') + 1)
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
  test('sits under Réglages, after Ce qui passe en premier, and opens /settings/meals', async () => {
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
  const renderFinanceMenu = async (path = '/finance/dashboard') => {
    stubPersonality('Maggie')

    return renderMenuAt(path)
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
    const finance = hrefs.slice(hrefs.indexOf('/finance/dashboard') + 1)
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

  test('lists no Finance menu on another module', async () => {
    await renderFinanceMenu('/calendar')

    await screen.findByRole('menuitem', { name: 'Finance' })
    expect(screen.queryByRole('menuitem', { name: 'Dashboard' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Trésorerie' })).toBeNull()
  })
})

describe('CustomMenu — the modules of the mockup', () => {
  const MODULES = [
    ['Accueil', '/'],
    ['Agenda', '/calendar'],
    ['Tâches', '/tasks'],
    ['Courses', '/grocery'],
    ['Cuisine', '/meals'],
    ['Finance', '/finance/dashboard'],
    ['Réglages', '/settings/preferences'],
  ]

  test('lists Accueil · Agenda · Tâches · Courses · Cuisine · Finance, then Réglages, in that order', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/')

    for (const [name, href] of MODULES) {
      expect(await screen.findByRole('menuitem', { name })).toHaveAttribute('href', href)
    }
    expect(hrefs()).toEqual(MODULES.map(([, href]) => href))
  })

  test('has no Chat, Calendrier, Paramètres, Nutrition or Données brutes at the first level', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/')
    await screen.findByRole('menuitem', { name: 'Réglages' })

    for (const old of ['Chat', 'Calendrier', 'Paramètres', 'Nutrition', 'Données brutes']) {
      expect(screen.queryByText(old)).toBeNull()
    }
  })

  test('opens the Tâches page from the menu', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/')

    expect(await screen.findByRole('menuitem', { name: 'Tâches' })).toHaveAttribute('href', '/tasks')
  })

  test('shows the sub-entries of the current module only', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/products')

    expect(await screen.findByRole('menuitem', { name: 'Liste de courses' })).toHaveAttribute('href', '/grocery')
    expect(screen.getByRole('menuitem', { name: 'Produits' })).toHaveAttribute('href', '/products')
    expect(screen.getByRole('menuitem', { name: 'Magasins' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Courses passées' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Recettes' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Préférences' })).toBeNull()
  })

  test('places the sub-entries right under their module', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/recipes')
    await screen.findByRole('menuitem', { name: 'Recettes' })

    expect(hrefs()).toEqual([
      '/',
      '/calendar',
      '/tasks',
      '/grocery',
      '/meals',
      '/meals',
      '/recipes',
      '/finance/dashboard',
      '/settings/preferences',
    ])
  })

  test('gives Cuisine one Repas entry, then Recettes: no second page for the meals', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/meals')
    await screen.findByRole('menuitem', { name: 'Recettes' })

    expect(screen.getByRole('menuitem', { name: 'Repas' })).toHaveAttribute('href', '/meals')
    expect(screen.queryByRole('menuitem', { name: /Planning des repas|Repas de la semaine/ })).toBeNull()
    expect(hrefs().filter((href) => (href ?? '').startsWith('/meals/'))).toEqual([])
  })

  test.each(['/', '/calendar', '/tasks'])('shows no sub-entry on %s', async (path) => {
    stubPersonality('Maggie')
    renderMenuAt(path)
    await screen.findByRole('menuitem', { name: 'Réglages' })

    expect(hrefs()).toEqual(MODULES.map(([, href]) => href))
  })

  test('puts the raw data lists under Réglages', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/settings/preferences')
    await screen.findByRole('menuitem', { name: 'Préférences' })

    for (const [name, href] of [
      ['Agendas', '/agendas'],
      ['Événements', '/events'],
      ['Ingrédients', '/ingredients'],
    ]) {
      expect(screen.getByRole('menuitem', { name })).toHaveAttribute('href', href)
    }
  })

  test('marks the module of the page as selected, and its entry too', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/stores')

    expect(await screen.findByRole('menuitem', { name: 'Courses' })).toHaveClass('Mui-selected')
    expect(screen.getByRole('menuitem', { name: 'Magasins' })).toHaveClass('Mui-selected')
    expect(screen.getByRole('menuitem', { name: 'Produits' })).not.toHaveClass('Mui-selected')
    expect(screen.getByRole('menuitem', { name: 'Agenda' })).not.toHaveClass('Mui-selected')
  })

  test('draws no icon on a sub-entry', async () => {
    stubPersonality('Maggie')
    renderMenuAt('/stores')

    const entry = await screen.findByRole('menuitem', { name: 'Magasins' })
    expect(entry.querySelector('svg')).toBeNull()
  })
})
