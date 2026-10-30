import { describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { veilleuseLightTheme } from '../../theme'
import { SuiviMeasurementForm } from './SuiviMeasurementForm'
import type { BodyMeasurement } from './bodyMeasurementsApi'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

function renderForm(measurement?: BodyMeasurement, overrides: Partial<DataProvider> = {}) {
  const create = vi.fn((_resource: string, params: { data: Record<string, unknown> }) =>
    Promise.resolve({ data: { id: '/api/body-measurements/01NEW', ...params.data } }),
  )
  const update = vi.fn((_resource: string, params: { data: Record<string, unknown> }) => Promise.resolve({ data: { id: measurement?.id, ...params.data } }))
  const dataProvider = testDataProvider({
    create: create as unknown as DataProvider['create'],
    update: update as unknown as DataProvider['update'],
    ...overrides,
  })
  const onClose = vi.fn()
  render(
    <MemoryRouter>
      <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider} theme={veilleuseLightTheme}>
        <SuiviMeasurementForm open measurement={measurement} onClose={onClose} />
        <Notification />
      </AdminContext>
    </MemoryRouter>,
  )

  return { create, update, onClose }
}

const dialog = () => screen.getByRole('dialog')
const box = (name: string) => within(dialog()).getByRole('textbox', { name })
const save = (user: ReturnType<typeof userEvent.setup>) => user.click(within(dialog()).getByRole('button', { name: 'Enregistrer' }))

describe('SuiviMeasurementForm', () => {
  test('adds a weighing: a comma decimal is a number, the time carries its offset, the source is manual', async () => {
    const user = userEvent.setup()
    const { create, onClose } = renderForm()

    expect(within(dialog()).getByRole('heading', { name: 'Ajouter une pesée' })).toBeInTheDocument()
    fireEvent.change(within(dialog()).getByLabelText('Date et heure'), { target: { value: '2026-10-21T07:12' } })
    await user.type(box('Poids (kg)'), '71,8')
    await user.type(box('Masse grasse (%)'), '21.3')
    await save(user)

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    const [resource, params] = create.mock.calls[0]
    expect(resource).toBe('body-measurements')
    expect(params.data).toMatchObject({ weightKg: 71.8, bodyFatPercent: 21.3, waistCm: null, source: 'manual' })
    expect(params.data.measuredAt).toMatch(/^2026-10-21T07:12:00[+-]\d{2}:\d{2}$/)
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  test('a waist alone is enough', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await user.type(box('Tour de taille (cm)'), '84')
    await save(user)

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({ weightKg: null, bodyFatPercent: null, waistCm: 84 })
  })

  test('refuses a weighing without any value, and sends nothing', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await save(user)

    expect(await screen.findByText('Indiquez au moins le poids, la masse grasse ou le tour de taille.')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  test('refuses a weight of 0 and a body fat of 100 %', async () => {
    const user = userEvent.setup()
    const { create } = renderForm()

    await user.type(box('Poids (kg)'), '0')
    await user.type(box('Masse grasse (%)'), '100')
    await save(user)

    expect(await screen.findByText('Le poids doit être supérieur à 0 et au plus de 500 kg.')).toBeInTheDocument()
    expect(screen.getByText('La masse grasse doit être comprise entre 0 et 100 %.')).toBeInTheDocument()
    expect(create).not.toHaveBeenCalled()
  })

  test('says in French when the weighing was not added, and keeps the form open', async () => {
    const user = userEvent.setup()
    const { onClose } = renderForm(undefined, { create: (() => Promise.reject(new Error('boom'))) as unknown as DataProvider['create'] })

    await user.type(box('Poids (kg)'), '72')
    await save(user)

    expect(await screen.findByText('Erreur : la pesée n’a pas été ajoutée.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  test('edits a weighing: its values are in the form, the source and « écartée » are not sent', async () => {
    const user = userEvent.setup()
    const measurement: BodyMeasurement = {
      id: '/api/body-measurements/01TODAY',
      measuredAt: new Date(2026, 9, 21, 7, 12).toISOString(),
      weightKg: 72.4,
      bodyFatPercent: 21.3,
      waistCm: null,
      source: 'health_connect',
      externalId: 'hc-1',
      excluded: false,
    }
    const { update } = renderForm(measurement)

    expect(within(dialog()).getByRole('heading', { name: 'Modifier la pesée' })).toBeInTheDocument()
    expect(box('Poids (kg)')).toHaveValue('72,4')
    await user.clear(box('Poids (kg)'))
    await user.type(box('Poids (kg)'), '72,1')
    await save(user)

    await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
    const [, params] = update.mock.calls[0] as unknown as [string, { id: string; data: Record<string, unknown> }]
    expect(params.id).toBe('/api/body-measurements/01TODAY')
    expect(params.data).toMatchObject({ weightKg: 72.1, bodyFatPercent: 21.3 })
    expect(params.data).not.toHaveProperty('source')
    expect(params.data).not.toHaveProperty('excluded')
  })
})
