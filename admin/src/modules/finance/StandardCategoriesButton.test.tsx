import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import { StandardCategoriesButton } from './StandardCategoriesButton'

const fetchMock = vi.fn()

const renderButton = () =>
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <StandardCategoriesButton />
      <Notification />
    </AdminContext>,
  )

describe('StandardCategoriesButton', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('it asks the API to lay down the starting set', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ created: 12, kept: 0 }) } as Response)

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: /Catégories de départ/ }))

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/finance/categories/standard',
        expect.objectContaining({ method: 'POST' }),
      ),
    )
  })

  test('a run that creates nothing says so rather than staying silent', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ created: 0, kept: 12 }) } as Response)

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: /Catégories de départ/ }))

    expect(await screen.findByText(/déjà là/)).toBeInTheDocument()
  })

  test('a refusal is reported', async () => {
    fetchMock.mockResolvedValue({ ok: false, json: async () => ({}) } as Response)

    renderButton()
    await userEvent.click(screen.getByRole('button', { name: /Catégories de départ/ }))

    expect(await screen.findByText(/n'ont pas pu être créées/)).toBeInTheDocument()
  })
})
