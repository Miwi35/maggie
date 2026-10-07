import { describe, expect, test } from 'vitest'
import { notificationLink, readFeedMessage } from './interruptions'

describe('notificationLink', () => {
  test.each([
    ['/api/events/e1', "Voir l'événement", '/calendar?eventId=%2Fapi%2Fevents%2Fe1'],
    ['/api/tasks/t1', 'Voir la tâche', '/tasks/%2Fapi%2Ftasks%2Ft1/show'],
    ['/api/recipes/r1', 'Voir la recette', '/recipes/%2Fapi%2Frecipes%2Fr1/show'],
    ['/api/grocery_items/g1', 'Voir la liste de courses', '/grocery'],
    ['/finance/banks', 'Reconnecter la banque', '/finance/banks'],
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
})
