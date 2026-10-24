import { Children, isValidElement } from 'react'
import type { ReactElement } from 'react'
import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { cookbookResources, RecipeEditRedirect } from './resources'

const resourceProps = (name: string) => {
  const children = Children.toArray((cookbookResources as ReactElement<{ children: React.ReactNode }>).props.children)
  const found = children.find((child) => isValidElement<{ name?: string }>(child) && child.props.name === name)

  return (found as ReactElement<Record<string, unknown>>).props
}

describe('cookbookResources', () => {
  test('recipes never get a guessed edit form: the edit route leads back to the sheet', () => {
    expect(resourceProps('recipes').edit).toBe(RecipeEditRedirect)

    render(
      <MemoryRouter initialEntries={['/recipes/01R']}>
        <Routes>
          <Route path="/recipes/:id" element={<RecipeEditRedirect />} />
          <Route path="/recipes/:id/show" element={<p>la fiche</p>} />
        </Routes>
      </MemoryRouter>,
    )

    expect(screen.getByText('la fiche')).toBeInTheDocument()
  })
})
