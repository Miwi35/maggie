import { describe, expect, test } from 'vitest'
import { notificationLink, readFeedMessage } from './interruptions'

describe('notificationLink', () => {
  test.each([
    ['/api/events/e1', "Voir l'événement", '/calendar?eventId=%2Fapi%2Fevents%2Fe1'],
    ['/api/tasks/t1', 'Voir la tâche', '/tasks?task=t1'],
    ['/api/recipes/r1', 'Voir la recette', '/recipes/%2Fapi%2Frecipes%2Fr1/show'],
    ['/api/grocery_items/g1', 'Voir la liste de courses', '/grocery'],
    ['/tasks', 'Voir les routines', '/tasks'],
    ['/tasks/', 'Voir les routines', '/tasks'],
    ['/finance/banks', 'Reconnecter la banque', '/finance/banks'],
    ['/finance/budgets', 'Voir les enveloppes', '/envelopes'],
    ['/finance', 'Ouvrir les finances', '/finance/dashboard'],
    ['/finance/cushion', 'Ouvrir les finances', '/finance/cushion'],
  ])('%s opens %s', (iri, label, path) => {
    expect(notificationLink(iri)).toEqual({ label, path })
  })

  test.each([null, undefined, '', '/api/unknown/x', '/finance/nowhere', 'https://example.com/x'])(
    'has no link for %s',
    (iri) => {
      expect(notificationLink(iri)).toBeNull()
    },
  )
})

describe('readFeedMessage', () => {
  test('ignores what is not JSON, not an object, or none of the sources', () => {
    expect(readFeedMessage('nope')).toBeNull()
    expect(readFeedMessage('"text"')).toBeNull()
    expect(readFeedMessage('null')).toBeNull()
    // A chat context riding the same feed.
    expect(readFeedMessage(JSON.stringify({ id: 'c1', label: 'Courses', status: 'active', summary: null }))).toBeNull()
  })

  test('an approval with no summary is asked by the name of its tool', () => {
    const event = readFeedMessage(JSON.stringify({ id: 'a1', toolName: 'delete_event', summary: '', status: 'pending' }))

    expect(event).toMatchObject({ kind: 'interrupt', interruption: { source: 'approval', message: 'delete_event' } })
  })

  test.each([
    ['read', { readAt: '2026-10-07T10:01:00+00:00' }],
    ['deleted', { deleted: true }],
  ])('a notification %s elsewhere is withdrawn', (_label, update) => {
    const event = readFeedMessage(JSON.stringify({ '@id': '/api/notifications/n1', ...update }))

    expect(event).toEqual({ kind: 'withdraw', id: 'notification:/api/notifications/n1' })
  })
})

describe('a missing product (MAG-570)', () => {
  const shortage = {
    '@id': '/api/notifications/n9',
    type: 'grocery',
    title: 'Il manque : parmesan',
    body: 'Pour Pâtes carbonara, ce soir.',
    relatedEntityIri: '/api/shortage-alerts/01K0SHORTAGE0000000000000A',
    actions: ['add'],
    readAt: null,
  }

  test('is an interruption carrying the alert, with the body as message and no link', () => {
    const event = readFeedMessage(JSON.stringify(shortage))

    expect(event).toEqual({
      kind: 'interrupt',
      interruption: {
        source: 'notification',
        id: 'notification:/api/notifications/n9',
        ref: '/api/notifications/n9',
        title: 'Il manque : parmesan',
        message: 'Pour Pâtes carbonara, ce soir.',
        link: null,
        shortageId: '01K0SHORTAGE0000000000000A',
      },
    })
  })

  test('without the « add » action it is a plain notification', () => {
    const event = readFeedMessage(JSON.stringify({ ...shortage, actions: [] }))

    expect(event).toMatchObject({ kind: 'interrupt', interruption: { source: 'notification', link: null } })
    expect(event?.kind === 'interrupt' && 'shortageId' in event.interruption).toBe(false)
  })

  test('is withdrawn when the shortage is settled and the notification deleted', () => {
    const event = readFeedMessage(JSON.stringify({ '@id': shortage['@id'], deleted: true }))

    expect(event).toEqual({ kind: 'withdraw', id: 'notification:/api/notifications/n9' })
  })

  test('its alert has no screen to open', () => {
    expect(notificationLink(shortage.relatedEntityIri)).toBeNull()
  })
})

describe('a cooking timer that rang (MAG-582)', () => {
  const rang = {
    '@id': '/api/notifications/n12',
    type: 'cooking_timer',
    title: "Pâtes : c'est prêt",
    body: "minuteur de l'étape 3 · Pâtes aux légumes",
    relatedEntityIri: '/api/cooking/timers/01K0TIMER000000000000000A',
    readAt: null,
  }

  test('interrupts the other admin pages with its title and body, and opens nothing', () => {
    expect(readFeedMessage(JSON.stringify(rang))).toEqual({
      kind: 'interrupt',
      interruption: {
        source: 'notification',
        id: 'notification:/api/notifications/n12',
        ref: '/api/notifications/n12',
        title: "Pâtes : c'est prêt",
        message: "minuteur de l'étape 3 · Pâtes aux légumes",
        link: null,
      },
    })
  })

  test('« OK » on another device marks it read: it is withdrawn', () => {
    const event = readFeedMessage(JSON.stringify({ ...rang, readAt: '2026-10-25T19:00:00+00:00' }))

    expect(event).toEqual({ kind: 'withdraw', id: 'notification:/api/notifications/n12' })
  })
})
