import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { EnvelopeSummary } from './EnvelopeSummary'

const dataProvider = testDataProvider({
  getOne: (() =>
    Promise.resolve({
      data: { id: '/api/categories/1', name: 'Alimentation' },
    })) as unknown as DataProvider['getOne'],
})

const renderSummary = (props: Parameters<typeof EnvelopeSummary>[0]) =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <EnvelopeSummary {...props} />
    </AdminContext>,
  )

describe('EnvelopeSummary', () => {
  test('asks for the missing pieces while the form is incomplete', () => {
    renderSummary({ mode: 'monthly', year: 2026, month: 9 })

    expect(screen.getByText(/Choisissez une catégorie, un montant et une période/i)).toBeInTheDocument()
  })

  test('restates a monthly envelope in one sentence', async () => {
    renderSummary({
      category: '/api/categories/1',
      mode: 'monthly',
      year: 2026,
      month: 9,
      amountCents: 40000,
      currency: 'EUR',
    })

    expect(await screen.findByText('Alimentation')).toBeInTheDocument()
    expect(screen.getByText(/Septembre 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Le budget repart à zéro le mois suivant/)).toBeInTheDocument()
  })

  test('says an annual envelope spans the whole year', async () => {
    renderSummary({
      category: '/api/categories/1',
      mode: 'annual',
      year: 2026,
      month: null,
      amountCents: 120000,
    })

    expect(await screen.findByText(/Année 2026/)).toBeInTheDocument()
    expect(screen.getByText(/couvre les douze mois/)).toBeInTheDocument()
  })
})
