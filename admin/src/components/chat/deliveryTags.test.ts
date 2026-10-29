import { describe, expect, it } from 'vitest'
import { stripDeliveryTags } from './deliveryTags'

describe('stripDeliveryTags', () => {
  it('removes a leading tag', () => {
    expect(stripDeliveryTags('[warmly] Bonjour monsieur.')).toBe('Bonjour monsieur.')
  })

  it('removes tags in the middle and collapses spaces', () => {
    expect(stripDeliveryTags('Oui [softly] bien sûr')).toBe('Oui bien sûr')
  })

  it('removes a tag stuck to punctuation with its leading space', () => {
    expect(stripDeliveryTags('Voilà [sighs].')).toBe('Voilà.')
  })

  it('keeps bracketed text longer than 40 characters', () => {
    const long = `[${'a'.repeat(41)}] fin`
    expect(stripDeliveryTags(long)).toBe(long)
  })

  it('holds back a tag still open while streaming', () => {
    expect(stripDeliveryTags('[warm')).toBe('')
    expect(stripDeliveryTags('Salut [warm')).toBe('Salut')
    expect(stripDeliveryTags('[warmly] Salut')).toBe('Salut')
  })
})
