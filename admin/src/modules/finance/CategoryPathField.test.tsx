import { describe, test, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AdminContext, RecordContextProvider, ResourceContextProvider } from 'react-admin'
import { CategoryPathField } from './CategoryPathField'

const renderField = (record: Record<string, unknown>) =>
  render(
    <AdminContext>
      <ResourceContextProvider value="categories">
        <RecordContextProvider value={record}>
          <CategoryPathField />
        </RecordContextProvider>
      </ResourceContextProvider>
    </AdminContext>,
  )

describe('CategoryPathField', () => {
  test('names a category by its full path', () => {
    renderField({ id: '/api/categories/3', name: 'Obsydian', path: 'Loisirs › Bars › Obsydian' })

    expect(screen.getByText('Loisirs › Bars › Obsydian')).toBeInTheDocument()
  })

  test('falls back to the name when the record carries no path', () => {
    renderField({ id: '/api/categories/1', name: 'Courses' })

    expect(screen.getByText('Courses')).toBeInTheDocument()
  })
})
