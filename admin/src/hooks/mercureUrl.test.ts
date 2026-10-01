import { describe, expect, it } from 'vitest'

import { mercureUrl } from './mercureUrl'

describe('mercureUrl', () => {
  it('subscribes an exact topic with match', () => {
    const url = mercureUrl('http://hub.test/.well-known/mercure', ['/chat/user-1'])

    expect(url.searchParams.getAll('match')).toEqual(['/chat/user-1'])
    expect(url.searchParams.has('topic')).toBe(false)
  })

  it('subscribes a {id} resource pattern with match_urlpattern, as :id', () => {
    const url = mercureUrl('http://hub.test/.well-known/mercure', ['/users/u1/api/recipes/{id}'])

    expect(url.searchParams.getAll('match_urlpattern')).toEqual(['/users/u1/api/recipes/:id'])
    expect(url.searchParams.has('match')).toBe(false)
  })

  it('mixes both kinds in one URL', () => {
    const url = mercureUrl('http://hub.test/.well-known/mercure', ['/chat/u1', '/users/u1/api/events/{id}', '/users/u1/api/tasks/{id}'])

    expect(url.searchParams.getAll('match')).toEqual(['/chat/u1'])
    expect(url.searchParams.getAll('match_urlpattern')).toEqual(['/users/u1/api/events/:id', '/users/u1/api/tasks/:id'])
  })

  it('resolves a relative hub URL against the current origin', () => {
    const url = mercureUrl('/.well-known/mercure', ['/chat/u1'])

    expect(url.origin).toBe(window.location.origin)
    expect(url.pathname).toBe('/.well-known/mercure')
  })
})
