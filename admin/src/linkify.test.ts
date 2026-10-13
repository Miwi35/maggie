import { describe, test, expect } from 'vitest'
import { splitLinks } from './linkify'

describe('splitLinks', () => {
  test('a message without an address is one piece of text', () => {
    expect(splitLinks('Bonjour')).toEqual([{ text: 'Bonjour' }])
    expect(splitLinks('')).toEqual([])
  })

  test('the source line of a web answer yields its link, nothing else', () => {
    expect(splitLinks('Du 17 octobre.\n\nSource : education.gouv.fr — https://www.education.gouv.fr/calendrier')).toEqual([
      { text: 'Du 17 octobre.\n\nSource : education.gouv.fr — ' },
      { text: 'https://www.education.gouv.fr/calendrier', url: 'https://www.education.gouv.fr/calendrier' },
    ])
  })

  test('several sources on one line are as many links, the separator staying text', () => {
    const parts = splitLinks('Sources : a.fr — https://a.fr/x · b.fr — http://b.fr/y?q=1')

    expect(parts.filter((part) => part.url).map((part) => part.url)).toEqual(['https://a.fr/x', 'http://b.fr/y?q=1'])
    expect(parts.map((part) => part.text).join('')).toBe('Sources : a.fr — https://a.fr/x · b.fr — http://b.fr/y?q=1')
  })

  test('an address stops at the space and loses the punctuation that ends the sentence', () => {
    expect(splitLinks('Voir https://a.fr/x, puis ailleurs.')[1]).toEqual({ text: 'https://a.fr/x', url: 'https://a.fr/x' })
    expect(splitLinks('Voir https://a.fr/x.')[1]).toEqual({ text: 'https://a.fr/x', url: 'https://a.fr/x' })
    expect(splitLinks('(voir https://a.fr/x)')[1]).toEqual({ text: 'https://a.fr/x', url: 'https://a.fr/x' })
  })

  test('a closing parenthesis that belongs to the address stays', () => {
    expect(splitLinks('https://fr.wikipedia.org/wiki/Rennes_(ville)')).toEqual([
      { text: 'https://fr.wikipedia.org/wiki/Rennes_(ville)', url: 'https://fr.wikipedia.org/wiki/Rennes_(ville)' },
    ])
  })

  test('only http and https are links', () => {
    expect(splitLinks('javascript:alert(1) ftp://a.fr/x')).toEqual([{ text: 'javascript:alert(1) ftp://a.fr/x' }])
  })

  test('a scheme with nothing after it is text', () => {
    expect(splitLinks('https://')).toEqual([{ text: 'https://' }])
  })
})
