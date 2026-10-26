import { test, expect } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * Known places, saved in conversation — MAG-652 (extends MAG-100).
 *
 * No screen: Maggie saves a place through `save_place` and reads them back
 * through `list_places`, asserted on the tool's answers. The seeded « Maison »
 * and « Dentiste » (`api/fixtures/e2e/21-places.yaml`) are the trips' own
 * fixtures (« Partir à l'heure », parts 4 to 10): this journey adds a place of
 * its own, far from them, and changes none of them.
 */

interface KnownPlace {
  id: string
  name: string
  address: string
  latitude: number | null
  longitude: number | null
  parkingAddress: string | null
  parkingLatitude: number | null
  parkingLongitude: number | null
  isHome: boolean
}

/** A name the current attempt alone writes: CI retries once and nothing reseeds in between. */
function nameOfThisAttempt(): string {
  const { retry } = test.info()

  return `Dentiste MAG-652${0 === retry ? '' : ` essai ${retry}`}`
}

test('a place Maggie saves with its parking is listed after « Maison »', async ({ api }) => {
  const name = nameOfThisAttempt()

  const saved = await callMcpTool(api, 'save_place', {
    name,
    address: '9 rue du Parcours Fictif, 29000 Quimper',
    latitude: 47.996,
    longitude: -4.102,
    parkingAddress: 'Parking du Parcours Fictif, 29000 Quimper',
    parkingLatitude: 47.9965,
    parkingLongitude: -4.1015,
  })
  expect(saved.error, JSON.stringify(saved)).toBeUndefined()
  const id = (saved.place as KnownPlace).id

  const listed = await callMcpTool(api, 'list_places', {})
  expect(listed.error, JSON.stringify(listed)).toBeUndefined()
  const places = listed.places as KnownPlace[]

  // Home first, and only the user's: the neighbour's « Maison » is not there.
  expect(places[0].name).toBe('Maison')
  expect(places[0].isHome).toBe(true)
  expect(places.filter((place) => place.isHome)).toHaveLength(1)

  const dentist = places.find((place) => place.id === id)
  expect(dentist, `${name} should be listed`).toBeDefined()
  expect(dentist).toMatchObject({
    name,
    parkingAddress: 'Parking du Parcours Fictif, 29000 Quimper',
    parkingLatitude: 47.9965,
    parkingLongitude: -4.1015,
    isHome: false,
  })

  // The seeded dentist keeps its parking for the trips.
  const seeded = places.find((place) => place.name === 'Dentiste')
  expect(seeded?.parkingAddress).toBe('Parking du Square Inventé, 35000 Rennes')
})

test('a place without its address is refused, naming the field', async ({ api }) => {
  const refused = await callMcpTool(api, 'save_place', { name: nameOfThisAttempt() })

  expect(refused.field).toBe('address')
  expect(String(refused.error)).toMatch(/^address:/)
})
