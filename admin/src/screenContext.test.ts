import { describe, test, expect } from 'vitest'
import { saidInMessage, SCREEN_CONTEXT_HEADER } from './screenContext'

const BLOCK = [
  SCREEN_CONTEXT_HEADER,
  'Application : Chrome (com.android.chrome)',
  'Page : https://dice.fm/event/x?utm_source=spam&utm_campaign=track',
  "Texte à l'écran :",
  '- DICE',
  '- Concert ce soir',
].join('\n')

describe('saidInMessage', () => {
  // The mobile assistant used to glue the screen block to the message, so the
  // conversation the web chat reads still holds the pages of those exchanges.
  // The bubble shows what was said (MAG-30, refused recette).
  test('drops the screen-context block a stored message carries', () => {
    expect(saidInMessage(`${BLOCK}\n\nDe quoi parle cette page ?`)).toBe('De quoi parle cette page ?')
  })

  test('leaves an ordinary message alone', () => {
    expect(saidInMessage('Ajoute du beurre')).toBe('Ajoute du beurre')
    expect(saidInMessage('')).toBe('')
  })

  test('keeps a message that merely mentions the header', () => {
    const said = `explique-moi ${SCREEN_CONTEXT_HEADER}\n\ndans tes logs`
    expect(saidInMessage(said)).toBe(said)
  })

  test('keeps a block with nothing said after it, rather than emptying the bubble', () => {
    expect(saidInMessage(BLOCK)).toBe(BLOCK)
    expect(saidInMessage(`${BLOCK}\n\n   `)).toBe(`${BLOCK}\n\n   `)
  })
})
