import { describe, test, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { ChatBubble } from './ChatBubble'

describe.each([
  ['dark', veilleuseDarkTheme],
  ['light', veilleuseLightTheme],
])('ChatBubble in the %s theme', (_mode, theme) => {
  test('puts the owner on the right with the accent, corners 22/22/6/22', () => {
    renderWithTheme(<ChatBubble role="user">Bonjour</ChatBubble>, theme)

    const bubble = screen.getByText('Bonjour')
    expect(bubble).toHaveStyle({ alignSelf: 'flex-end', borderRadius: '22px 22px 6px 22px' })
    expect(getComputedStyle(bubble).backgroundColor).toMatch(/^rgba\(/)
  })

  test('puts Maggie on the left on her reply surface, corners 22/22/22/6', () => {
    renderWithTheme(<ChatBubble role="assistant">Bonjour</ChatBubble>, theme)

    expect(screen.getByText('Bonjour')).toHaveStyle({
      alignSelf: 'flex-start',
      borderRadius: '22px 22px 22px 6px',
      backgroundColor: theme.palette.maggie.reply,
    })
  })

  test('rings the message a search jumped to', () => {
    renderWithTheme(
      <ChatBubble role="assistant" highlighted>
        Trouvé
      </ChatBubble>,
      theme,
    )

    expect(screen.getByText('Trouvé')).toHaveStyle({ boxShadow: `0 0 0 2px ${theme.palette.warning.main}` })
  })
})
