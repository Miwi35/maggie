import { describe, test, expect } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithTheme } from '../../test/renderWithTheme'
import { veilleuseDarkTheme, veilleuseLightTheme } from '../../theme'
import { ChatBubble, DecisionLine } from './ChatBubble'
import { Linkified } from './Linkified'

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

describe('a message with a web address', () => {
  test('shows it as a link that opens in a new tab, without handing over the opener', () => {
    renderWithTheme(
      <ChatBubble role="assistant">
        <Linkified text="Source : education.gouv.fr — https://www.education.gouv.fr/calendrier" />
      </ChatBubble>,
      veilleuseLightTheme,
    )

    const link = screen.getByRole('link', { name: 'https://www.education.gouv.fr/calendrier' })
    expect(link).toHaveAttribute('href', 'https://www.education.gouv.fr/calendrier')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  test('leaves a message without address as it is', () => {
    renderWithTheme(
      <ChatBubble role="assistant">
        <Linkified text="Bonjour" />
      </ChatBubble>,
      veilleuseLightTheme,
    )

    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText('Bonjour')).toBeInTheDocument()
  })
})

describe('DecisionLine', () => {
  test('is a centred, discreet line without the bubble surface', () => {
    renderWithTheme(<DecisionLine>Vous avez répondu : Refuser — supprimer l'événement</DecisionLine>, veilleuseDarkTheme)

    const line = screen.getByTestId('chat-decision')
    expect(line).toHaveTextContent('Vous avez répondu : Refuser')
    expect(line).toHaveStyle({ alignSelf: 'center' })
    expect(getComputedStyle(line).backgroundColor).not.toBe(veilleuseDarkTheme.palette.maggie.reply)
  })
})
