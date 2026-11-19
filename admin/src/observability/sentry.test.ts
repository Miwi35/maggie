import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@sentry/react', () => ({ init: vi.fn() }))

import * as Sentry from '@sentry/react'
import { initSentry } from './sentry'

describe('initSentry', () => {
  beforeEach(() => {
    vi.mocked(Sentry.init).mockClear()
  })

  it('sends nothing when the DSN is empty', () => {
    expect(initSentry({ VITE_SENTRY_DSN: '' })).toBe(false)
    expect(initSentry({ VITE_SENTRY_DSN: '   ' })).toBe(false)
    expect(initSentry({})).toBe(false)
    expect(Sentry.init).not.toHaveBeenCalled()
  })

  it('starts with the release, the environment and the component tag', () => {
    const started = initSentry({
      VITE_SENTRY_DSN: 'https://key@glitchtip.meven.fr/3',
      VITE_SENTRY_RELEASE: 'abc1234',
    })

    expect(started).toBe(true)
    expect(Sentry.init).toHaveBeenCalledWith(
      expect.objectContaining({
        dsn: 'https://key@glitchtip.meven.fr/3',
        release: 'abc1234',
        environment: 'prod',
        dataCollection: expect.objectContaining({ userInfo: false, cookies: false }),
        initialScope: { tags: { component: 'admin' } },
      }),
    )
  })

  it('drops errors raised by browser extensions', () => {
    initSentry({ VITE_SENTRY_DSN: 'https://key@glitchtip.meven.fr/3' })

    const options = vi.mocked(Sentry.init).mock.calls[0][0]!
    const denied = (url: string) => options.denyUrls!.some((p) => (p instanceof RegExp ? p.test(url) : url.includes(p)))

    expect(denied('chrome-extension://nkbihfbeogaeaoehlefnkodbefgpgknn/scripts/inpage.js')).toBe(true)
    expect(denied('moz-extension://abc/content.js')).toBe(true)
    expect(denied('safari-web-extension://abc/content.js')).toBe(true)
    expect(denied('https://maggie.meven.fr/assets/index-abc.js')).toBe(false)
  })

  it('keeps an explicit environment', () => {
    initSentry({ VITE_SENTRY_DSN: 'https://key@glitchtip.meven.fr/3', VITE_SENTRY_ENVIRONMENT: 'staging' })

    expect(Sentry.init).toHaveBeenCalledWith(expect.objectContaining({ environment: 'staging' }))
  })
})
