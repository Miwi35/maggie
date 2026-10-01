import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { CalendarView } from './CalendarView'

/**
 * The handles `e2e/web/pages/CalendarPage.ts` reaches for.
 *
 * A contract between this screen and the agenda journeys (MAG-100), in the same
 * spirit as `api/contract/`: the journeys address the toolbar, the sidebar, the four
 * dialogs and the detail card by role and accessible name, and nothing in a normal
 * component test notices when one of those names moves. The journeys would — several
 * minutes later, on a stack that takes minutes to start, with a failure that reads
 * as "the calendar is broken" rather than "a label was renamed".
 *
 * So this file asserts only the names, and asserts them where the real FullCalendar
 * and the real MUI dialogs render them. It owns no behaviour: what the dialogs *do*
 * is `CalendarView.test.tsx`, `EventCreateDialog.test.tsx` and
 * `EventEditDialog.test.tsx`. Rename something here and the journey that reads it is
 * one line away in `CalendarPage`.
 */

class MockEventSource {
  onmessage: ((event: MessageEvent) => void) | null = null
  close = vi.fn()
  constructor(public url: string) {}
}

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(), vi.fn()],
}))

const mockGetList = vi.fn()
const mockCreate = vi.fn()
const mockUpdate = vi.fn()
const mockDelete = vi.fn()
vi.mock('react-admin', () => ({
  useDataProvider: () => ({
    getList: mockGetList,
    create: mockCreate,
    update: mockUpdate,
    delete: mockDelete,
  }),
  useNotify: () => vi.fn(),
}))

const AGENDAS = [
  { id: '/api/agendas/01PERSO', name: 'Perso', color: '#3f51b5', isDefault: true },
  { id: '/api/agendas/01FAMILLE', name: 'Famille', color: '#e91e63', isDefault: false },
]

/** Noon on the 15th, so the event always sits inside the month the grid opens on. */
const noon = (() => {
  const date = new Date()
  date.setHours(12, 0, 0, 0)
  date.setDate(15)

  return date
})()

const RECURRING = {
  id: 'ev-weekly',
  summary: 'Cours de piano',
  startAt: noon.toISOString(),
  endAt: new Date(noon.getTime() + 3600_000).toISOString(),
  allDay: false,
  agenda: '/api/agendas/01PERSO',
  rrule: 'FREQ=WEEKLY;COUNT=8',
}

const TASK = {
  id: 'task-1',
  title: 'Sortir les poubelles',
  priority: 'low',
  criticality: 'low',
  dueDate: noon.toISOString(),
  completedAt: new Date(noon.getTime() + 900_000).toISOString(),
}

// FullCalendar redraws the whole grid on every interaction, which is slow in jsdom.
describe('CalendarView — the handles the agenda journeys use', { timeout: 60_000 }, () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('EventSource', MockEventSource)
    mockGetList.mockImplementation((resource: string) => {
      if ('agendas' === resource) return Promise.resolve({ data: AGENDAS, total: AGENDAS.length })
      if ('events' === resource) return Promise.resolve({ data: [RECURRING], total: 1 })
      if ('tasks' === resource) return Promise.resolve({ data: [TASK], total: 1 })

      return Promise.resolve({ data: [], total: 0 })
    })
    mockCreate.mockResolvedValue({ data: {} })
    mockUpdate.mockResolvedValue({ data: {} })
    mockDelete.mockResolvedValue({ data: {} })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  /** Rendered and done loading: the sidebar's agenda rows are the journeys' own signal. */
  const open = async () => {
    render(<CalendarView />)
    await waitFor(() => expect(screen.getAllByTestId('agenda-row')).toHaveLength(AGENDAS.length))
  }

  test('the toolbar: today, the two arrows, the three views, and the title', async () => {
    await open()

    expect(screen.getByRole('button', { name: "Aujourd'hui" })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /précédent/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /suivant/ })).toBeInTheDocument()

    for (const view of ['Mois', 'Semaine', 'Jour']) {
      expect(screen.getByRole('button', { name: view })).toBeInTheDocument()
    }

    // `chooseView` waits on the pressed button's own class, which is what says the
    // grid has switched rather than merely been clicked.
    expect(screen.getByRole('button', { name: 'Mois' })).toHaveClass('MuiButton-contained')

    // The toolbar's title: the page's first level-6 heading.
    expect(screen.getAllByRole('heading', { level: 6 })[0]).toBeInTheDocument()
  })

  test('the sidebar: a named row per agenda, each with its options button', async () => {
    await open()

    for (const agenda of AGENDAS) {
      const row = screen.getAllByTestId('agenda-row').find((candidate) => candidate.textContent === agenda.name)
      expect(row, `no row whose whole text is "${agenda.name}"`).toBeDefined()
      expect(within(row!).getByRole('checkbox')).toBeInTheDocument()
      expect(within(row!).getByRole('button', { name: `Options de l'agenda ${agenda.name}` })).toBeInTheDocument()
    }

    expect(screen.getByRole('button', { name: 'Ajouter' })).toBeInTheDocument()
  })

  test('the create split button opens the event dialog by its own name', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))

    const dialog = await screen.findByRole('dialog', { name: 'Nouvel événement' })
    expect(within(dialog).getByLabelText(/Résumé/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/Début/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/Fin/)).toBeInTheDocument()
    expect(within(dialog).getByRole('switch', { name: 'Journée entière' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Créer' })).toBeInTheDocument()

    // The three selects `CalendarPage.choose()` drives, by role: MUI renders a
    // hidden input beside each `combobox`, and `getByLabelText` would find that
    // instead — clicking it opens nothing at all.
    for (const label of ['Récurrence', 'Calendrier']) {
      expect(within(dialog).getByRole('combobox', { name: label })).toBeInTheDocument()
    }
  })

  test('the recurrence picker offers its frequencies and its end condition', async () => {
    await open()
    await userEvent.click(screen.getByRole('button', { name: 'Créer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nouvel événement' })

    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Récurrence' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Toutes les semaines' }))

    // "Se termine" only exists once a frequency is chosen, which is why
    // `CalendarPage.createEvent` sets the two in that order.
    await userEvent.click(within(dialog).getByRole('combobox', { name: 'Se termine' }))
    await userEvent.click(await screen.findByRole('option', { name: 'Après' }))

    // The occurrence count is the last of the two number fields: the interval comes
    // first, which is the order `createEvent` relies on.
    expect(within(dialog).getAllByRole('spinbutton')).toHaveLength(2)
  })

  test('the create menu offers the task dialog', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Options de création' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Tâche' }))

    const dialog = await screen.findByRole('dialog', { name: 'Nouvelle tâche' })
    expect(within(dialog).getByLabelText(/Titre/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Échéance')).toBeInTheDocument()
    expect(within(dialog).getByRole('combobox', { name: 'Criticité' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Créer' })).toBeInTheDocument()
  })

  test('the "Ajouter" menu offers the Google import', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    expect(await screen.findByRole('menuitem', { name: 'Créer un agenda' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Importer depuis Google' })).toBeInTheDocument()
  })

  test('the options menu of a local agenda offers the export', async () => {
    await open()

    await userEvent.click(screen.getByRole('button', { name: "Options de l'agenda Famille" }))

    expect(await screen.findByRole('menuitem', { name: 'Exporter vers Google' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Supprimer' })).toBeInTheDocument()
  })

  test('a chip is addressable by its exact title, and opens the detail card', async () => {
    await open()

    // One chip per occurrence, each carrying the summary and nothing else — which is
    // what `CalendarPage.chip()` counts.
    const chips = await screen.findAllByText(RECURRING.summary, { exact: true })
    expect(chips.length).toBeGreaterThan(0)

    await userEvent.click(chips[0])

    // The card is a Popover in a portal; the journeys scope to its paper.
    const popover = document.querySelector('.MuiPopover-paper')
    expect(popover, 'the detail card did not open').not.toBeNull()
    for (const action of ['Modifier', 'Supprimer', 'Fermer']) {
      expect(within(popover as HTMLElement).getByRole('button', { name: action })).toBeInTheDocument()
    }
  })

  test('editing an occurrence asks which ones, by name', async () => {
    await open()

    const chips = await screen.findAllByText(RECURRING.summary, { exact: true })
    await userEvent.click(chips[0])
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

    const edit = await screen.findByRole('dialog', { name: "Modifier l'événement" })
    expect(within(edit).getByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
    await userEvent.click(within(edit).getByRole('button', { name: 'Enregistrer' }))

    // Matched on a regex because the same dialog is titled "Supprimer …" on a
    // deletion. A string name is a *full* match in Testing Library, which is what
    // keeps "Modifier l'événement" above from finding this one — the journeys say
    // the same thing with Playwright's `exact: true`, which is not a Testing
    // Library option and has no counterpart here.
    const confirm = await screen.findByRole('dialog', { name: /l'événement récurrent$/ })
    for (const scope of ['Cet événement', 'Cet événement et tous les suivants', 'Tous les événements']) {
      expect(within(confirm).getByRole('radio', { name: scope })).toBeInTheDocument()
    }
    expect(within(confirm).getByRole('button', { name: 'OK' })).toBeInTheDocument()
  })

  test('a done task is drawn with a tick, and its pencil opens the task form', async () => {
    await open()

    // `✓ ` is prepended by the view, so the journey that reopens a task looks for the
    // title with the tick on it.
    await userEvent.click(await screen.findByText(`✓ ${TASK.title}`, { exact: true }))
    await userEvent.click(await screen.findByRole('button', { name: 'Modifier' }))

    const dialog = await screen.findByRole('dialog', { name: 'Modifier la tâche' })
    expect(within(dialog).getByRole('switch', { name: 'Terminée' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Enregistrer' })).toBeInTheDocument()
  })

  test('the grid carries a date on each day cell', async () => {
    await open()

    // `chipsOnDay` narrows to `td[data-date="YYYY-MM-DD"]`, which is how an
    // occurrence is tied to the day it falls on rather than to its position.
    await waitFor(() => expect(document.querySelectorAll('td[data-date]').length).toBeGreaterThan(0))
  })
})
