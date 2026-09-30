import { beforeEach, describe, expect, test } from 'vitest'
import { routeVisitorWithoutSessionToLogin } from './authProvider'

function jwt(exp: number): string {
  return `e30.${btoa(JSON.stringify({ exp }))}.sig`
}

const inOneHour = () => Math.floor(Date.now() / 1000) + 3600

describe('routeVisitorWithoutSessionToLogin', () => {
  beforeEach(() => {
    localStorage.clear()
    window.history.replaceState({}, '', '/admin/#/')
  })

  test('sends a visitor with no token to the login page', () => {
    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(window.location.pathname).toBe('/admin/')
  })

  test.each([
    ['expired', jwt(1)],
    ['malformed', 'not-a-jwt'],
  ])('clears an %s token and sends the visitor to the login page', (_label, token) => {
    localStorage.setItem('token', token)
    localStorage.setItem('user', '{"id":"1"}')

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('user')).toBeNull()
  })

  test('leaves a valid session on the requested page', () => {
    localStorage.setItem('token', jwt(inOneHour()))
    window.history.replaceState({}, '', '/admin/#/events')

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/events')
    expect(localStorage.getItem('token')).not.toBeNull()
  })

  test('does not rewrite the URL when already on the login page', () => {
    window.history.replaceState({}, '', '/admin/#/login')
    const before = window.history.length

    routeVisitorWithoutSessionToLogin()

    expect(window.location.hash).toBe('#/login')
    expect(window.history.length).toBe(before)
  })
})
