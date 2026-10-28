import { describe, expect, test } from 'vitest'
import { isCushionAccount } from './accountCushion'

describe('isCushionAccount', () => {
  test.each([
    [{ id: 'a', cushion: true }, true],
    [{ id: 'a', isCushion: true }, true],
    [{ id: 'a', cushion: false }, false],
    [{ id: 'a', isCushion: false }, false],
    [{ id: 'a' }, false],
  ])('%j reads %s', (account, expected) => {
    expect(isCushionAccount(account)).toBe(expected)
  })
})
