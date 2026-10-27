import { test, expect } from '../fixtures/index.js'
import { callMcpTool } from '../helpers/mcp.js'

/**
 * The « Maison » comes from the Google account — MAG-664 (« Partir à l'heure »,
 * part 11, extends MAG-100).
 *
 * No screen: Maggie calls `list_places`, asserted on the tool's answer and on the
 * stored place. People is WireMock (`.docker/e2e/wiremock/mappings/google-people.json`:
 * a work address and a « home » one), Geocoding places every address at the seeded
 * « Dentiste » (48.118, -1.668). The account is created for the test, linked to
 * Google and without any place: the shared seeded user already has its « Maison ».
 */

test.use({ journeyAccount: 'empty' })

interface KnownPlace {
  id: string
  name: string
  address: string
  latitude: number | null
  longitude: number | null
  isHome: boolean
}

test('without a Maison, list_places takes the home address of the Google account and keeps it', async ({ api }) => {
  const listed = await callMcpTool(api, 'list_places', {})
  expect(listed.error, JSON.stringify(listed)).toBeUndefined()
  const places = listed.places as KnownPlace[]

  expect(places).toHaveLength(1)
  expect(places[0]).toMatchObject({
    name: 'Maison',
    address: '3 rue de la Maison Fictive, 35000 Rennes, France',
    latitude: 48.118,
    longitude: -1.668,
    isHome: true,
  })

  // Stored, not recomputed: the next read is the same place, not a second one.
  const again = (await callMcpTool(api, 'list_places', {})).places as KnownPlace[]
  expect(again.map((place) => place.id)).toEqual([places[0].id])
})
