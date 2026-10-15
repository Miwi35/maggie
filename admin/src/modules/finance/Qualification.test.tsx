import { describe, test, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { Qualification, RequalifiedNotice } from './Qualification'
import type { QualificationChange } from './Qualification'
import type { QualificationValue } from './qualificationKinds'

const CATEGORIES = [
  { id: '/api/categories/loyer', name: 'Loyer', path: 'Loyer', depth: 1, parent: null },
  { id: '/api/categories/salaire', name: 'Salaire', path: 'Salaire', depth: 1, parent: null },
  { id: '/api/categories/credits', name: 'Crédits', path: 'Crédits', depth: 1, parent: null },
]

const renderQualification = (
  props: Partial<React.ComponentProps<typeof Qualification>> = {},
  qualification: QualificationValue = { mandatory: false, kind: 'none' },
) => {
  const onChange = vi.fn<(next: QualificationChange) => void>()
  render(
    <AdminContext
      dataProvider={testDataProvider({
        getList: (() => Promise.resolve({ data: CATEGORIES, total: 3 })) as unknown as DataProvider['getList'],
      })}
    >
      <Qualification category="/api/categories/credits" qualification={qualification} onChange={onChange} {...props} />
    </AdminContext>,
  )

  return onChange
}

describe('Qualification', () => {
  test('offers every category, an income one next to an expense one', async () => {
    const user = userEvent.setup()
    renderQualification()

    await user.click(await screen.findByRole('combobox', { name: /Catégorie/ }))

    expect((await screen.findAllByRole('option')).map((o) => o.textContent)).toEqual(['Crédits', 'Loyer', 'Salaire'])
  })

  test('shows the category, the switch and the type it was given', async () => {
    renderQualification({}, { mandatory: true, kind: 'repayment' })

    await waitFor(() => expect(screen.getByRole('combobox', { name: /Catégorie/ })).toHaveValue('Crédits'))
    expect(screen.getByRole('switch', { name: 'Obligatoire' })).toBeChecked()
    expect(screen.getByRole('combobox', { name: 'Type' })).toHaveTextContent('Remboursement')
  })

  test('choosing a category keeps the qualification', async () => {
    const user = userEvent.setup()
    const onChange = renderQualification({}, { mandatory: true, kind: 'loan' })

    await user.click(await screen.findByRole('combobox', { name: /Catégorie/ }))
    await user.click(await screen.findByRole('option', { name: 'Salaire' }))

    expect(onChange).toHaveBeenCalledWith({
      category: '/api/categories/salaire',
      qualification: { mandatory: true, kind: 'loan' },
    })
  })

  test('the switch and the type each change their own part, and nothing else', async () => {
    const user = userEvent.setup()
    const onChange = renderQualification({}, { mandatory: false, kind: 'saving' })

    await user.click(await screen.findByRole('switch', { name: 'Obligatoire' }))
    expect(onChange).toHaveBeenLastCalledWith({
      category: '/api/categories/credits',
      qualification: { mandatory: true, kind: 'saving' },
    })

    await user.click(screen.getByRole('combobox', { name: 'Type' }))
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Aucun',
      'Épargne',
      'Investissement',
      'Prêt',
      'Remboursement',
    ])
    await user.click(screen.getByRole('option', { name: 'Investissement' }))
    expect(onChange).toHaveBeenLastCalledWith({
      category: '/api/categories/credits',
      qualification: { mandatory: false, kind: 'investment' },
    })
  })

  test.each([
    ['out', /je rembourse/],
    ['in', /on me rembourse/],
    ['unknown', /selon le signe du montant/],
  ] as const)('a repayment reads differently when the amount is %s', async (sense, text) => {
    renderQualification({ sense }, { mandatory: false, kind: 'repayment' })

    expect(await screen.findByText(text)).toBeInTheDocument()
  })

  test('says what is missing when the category is required', async () => {
    renderQualification({ category: null, required: true, error: 'Choisissez une catégorie' })

    expect(await screen.findByText('Choisissez une catégorie')).toBeInTheDocument()
  })
})

describe('RequalifiedNotice', () => {
  test('says how many attached lines will be requalified, and that hand-made ones stay', () => {
    render(<RequalifiedNotice count={3} />)

    expect(screen.getByText(/3 transactions rattachées seront requalifiées/)).toBeInTheDocument()
    expect(screen.getByText(/qualifiées à la main ne bougent pas/)).toBeInTheDocument()
  })

  test('says it in the singular', () => {
    render(<RequalifiedNotice count={1} />)

    expect(screen.getByText(/1 transaction rattachée sera requalifiée/)).toBeInTheDocument()
  })

  test.each([0, null])('says nothing when %s lines are concerned', (count) => {
    const { container } = render(<RequalifiedNotice count={count} />)

    expect(container).toBeEmptyDOMElement()
  })
})
