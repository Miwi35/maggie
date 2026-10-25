import { describe, expect, test } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ReadingPage, READING_WIDTH } from './ReadingPage'

describe('ReadingPage', () => {
  test('caps a sheet at the reading width, centred, and still fills a narrower window', () => {
    render(<ReadingPage>la fiche</ReadingPage>)

    const page = screen.getByTestId('reading-page')
    expect(READING_WIDTH).toBe(1100)
    expect(page).toHaveTextContent('la fiche')
    expect(page).toHaveStyle({ maxWidth: '1100px', width: '100%', marginLeft: 'auto', marginRight: 'auto' })
  })
})
