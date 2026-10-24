import { Children, isValidElement } from 'react'
import type { ReactElement } from 'react'
import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, AdminUI, Resource, testDataProvider } from 'react-admin'
import { MemoryRouter, useLocation, useParams } from 'react-router-dom'
import { cookbookResources, RecipeEditRedirect } from './resources'

const resourceProps = (name: string) => {
  const children = Children.toArray((cookbookResources as ReactElement<{ children: React.ReactNode }>).props.children)
  const found = children.find((child) => isValidElement<{ name?: string }>(child) && child.props.name === name)

  return (found as ReactElement<Record<string, unknown>>).props
}

// A redirect that keeps growing the address never settles: stop it so the test fails instead of hanging.
const RedirectLoopGuard = () => {
  const { pathname } = useLocation()
  if (pathname.split('/').length > 8) {
    throw new Error(`redirect loop: ${pathname}`)
  }

  return null
}

const Sheet = () => <p>la fiche {useParams().id}</p>

const iri = encodeURIComponent('/api/recipes/01R')

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <RedirectLoopGuard />
      <AdminContext dataProvider={testDataProvider()}>
        <AdminUI>
          <Resource name="recipes" show={Sheet} edit={RecipeEditRedirect} />
        </AdminUI>
      </AdminContext>
    </MemoryRouter>,
  )

describe('cookbookResources', () => {
  test('recipes never get a guessed edit form: the edit route is the redirect', () => {
    expect(resourceProps('recipes').edit).toBe(RecipeEditRedirect)
  })

  test('the edit route of a recipe leads back to its sheet', async () => {
    renderAt('/recipes/01R')

    expect(await screen.findByText(/^la fiche/)).toBeInTheDocument()
  })

  test('the redirect keeps the IRI id of an API Platform recipe', async () => {
    renderAt(`/recipes/${iri}`)

    expect(await screen.findByText('la fiche /api/recipes/01R')).toBeInTheDocument()
  })

  test('a mistyped address under a recipe does not loop forever', async () => {
    renderAt('/recipes/01R/edit')

    expect(await screen.findByText(/^la fiche/)).toBeInTheDocument()
  })
})
