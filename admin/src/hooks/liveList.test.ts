import { describe, expect, test } from 'vitest'
import { applyEvent, applyOverlay } from './liveList'
import type { LiveConfig, LiveView, Overlay, Row } from './liveList'

const config: LiveConfig = {
  topics: ['/api/things/{id}'],
  required: ['name', 'owner'],
  references: { ownerId: { field: 'owner', collection: '/api/owners' } },
}

const view: LiveView = { sort: { field: 'name', order: 'ASC' }, page: 1, perPage: 10, filtered: false }

const row = (id: string, name: string): Row => ({ id: `/api/things/${id}`, name, owner: '/api/owners/O1' })

const events = (...payloads: Record<string, unknown>[]): Overlay =>
  payloads.reduce<Overlay>((overlay, payload) => applyEvent(overlay, payload, config), {})

const created = (id: string, name: string) => ({ '@id': `/api/things/${id}`, name, ownerId: 'O1' })

describe('applyEvent', () => {
  test('ignores what names no row, keeping the same overlay', () => {
    const overlay: Overlay = {}

    expect(applyEvent(overlay, { name: 'x' }, config)).toBe(overlay)
    expect(applyEvent(overlay, null, config)).toBe(overlay)
    expect(applyEvent(overlay, 'text', config)).toBe(overlay)
  })

  test('turns a ULID reference into the IRI the records carry', () => {
    const overlay = events(created('A', 'Alpha'))

    expect(overlay['/api/things/A'].fields).toEqual({ name: 'Alpha', owner: '/api/owners/O1' })
  })

  test('a reference cleared in the payload clears the IRI', () => {
    const overlay = events({ '@id': '/api/things/A', ownerId: null })

    expect(overlay['/api/things/A'].fields).toEqual({ owner: null })
  })

  test('merges a differential update into what the events already said', () => {
    const overlay = events(created('A', 'Alpha'), { '@id': '/api/things/A', name: 'Alpha 2' })

    expect(overlay['/api/things/A'].fields).toEqual({ name: 'Alpha 2', owner: '/api/owners/O1' })
  })

  test('a deletion wins, and a later event brings the row back', () => {
    const deleted = events(created('A', 'Alpha'), { '@id': '/api/things/A', deleted: true })
    expect(deleted['/api/things/A'].deleted).toBe(true)

    const back = applyEvent(deleted, created('A', 'Alpha'), config)
    expect(back['/api/things/A'].deleted).toBe(false)
  })

  test('takes the fallback once, for a row first seen through an event', () => {
    let calls = 0
    const withFallback: LiveConfig = { ...config, fallback: () => ({ createdAt: `t${++calls}` }) }
    let overlay = applyEvent({}, created('A', 'Alpha'), withFallback)
    overlay = applyEvent(overlay, { '@id': '/api/things/A', name: 'Alpha 2' }, withFallback)

    expect(overlay['/api/things/A'].fallback).toEqual({ createdAt: 't1' })
  })
})

describe('applyOverlay', () => {
  test('returns the read itself when no event came', () => {
    const listed = [row('A', 'Alpha')]

    expect(applyOverlay(listed, {}, config, view)).toEqual({ data: listed, added: 0, removed: 0 })
  })

  test('updates a known row in place, wherever it is', () => {
    const listed = [row('A', 'Alpha'), row('B', 'Bravo')]
    const { data, added, removed } = applyOverlay(listed, events({ '@id': '/api/things/B', name: 'Bravo 2' }), config, view)

    expect(data.map((r) => r.name)).toEqual(['Alpha', 'Bravo 2'])
    expect([added, removed]).toEqual([0, 0])
  })

  test('adds a created row in sort order and counts it', () => {
    const listed = [row('A', 'Alpha'), row('C', 'Charlie')]
    const { data, added } = applyOverlay(listed, events(created('B', 'Bravo')), config, view)

    expect(data.map((r) => r.name)).toEqual(['Alpha', 'Bravo', 'Charlie'])
    expect(added).toBe(1)
  })

  test('sorts descending, nulls last, accents and case aside', () => {
    const listed = [row('A', 'alpha'), row('B', 'Émile')]
    const { data } = applyOverlay(listed, events(created('C', 'Zoé')), config, { ...view, sort: { field: 'name', order: 'DESC' } })

    expect(data.map((r) => r.name)).toEqual(['Zoé', 'Émile', 'alpha'])

    const noName = applyOverlay(listed, events({ '@id': '/api/things/A', name: null }), config, view)
    expect(noName.data.map((r) => r.id)).toEqual(['/api/things/B', '/api/things/A'])
  })

  test('re-sorts when an update moves a known row', () => {
    const listed = [row('A', 'Alpha'), row('B', 'Bravo')]
    const { data } = applyOverlay(listed, events({ '@id': '/api/things/A', name: 'Zulu' }), config, view)

    expect(data.map((r) => r.name)).toEqual(['Bravo', 'Zulu'])
  })

  test('drops a deleted row and counts it only when the read held it', () => {
    const listed = [row('A', 'Alpha'), row('B', 'Bravo')]
    const overlay = events({ '@id': '/api/things/A', deleted: true }, { '@id': '/api/things/GONE', deleted: true })
    const { data, removed } = applyOverlay(listed, overlay, config, view)

    expect(data.map((r) => r.name)).toEqual(['Bravo'])
    expect(removed).toBe(1)
  })

  test('does not show a differential update of a row it has never seen', () => {
    const { data, added } = applyOverlay([row('A', 'Alpha')], events({ '@id': '/api/things/X', name: 'Xray' }), config, view)

    expect(data.map((r) => r.name)).toEqual(['Alpha'])
    expect(added).toBe(0)
  })

  test('keeps a full page at its size', () => {
    const listed = [row('B', 'Bravo'), row('C', 'Charlie')]
    const { data } = applyOverlay(listed, events(created('A', 'Alpha')), config, { ...view, perPage: 2 })

    expect(data.map((r) => r.name)).toEqual(['Alpha', 'Bravo'])
  })

  test('on a later page, what sorts before the page belongs to an earlier one', () => {
    const listed = [row('M', 'Mike'), row('N', 'November')]
    const { data, added } = applyOverlay(listed, events(created('A', 'Alpha'), created('P', 'Papa')), config, { ...view, page: 2 })

    expect(data.map((r) => r.name)).toEqual(['Mike', 'November', 'Papa'])
    expect(added).toBe(2)
  })

  test('on a filtered list a new row is not inserted, but a known one is still updated or dropped', () => {
    const listed = [row('A', 'Alpha'), row('B', 'Bravo')]
    const overlay = events(created('N', 'New'), { '@id': '/api/things/A', name: 'Alpha 2' }, { '@id': '/api/things/B', deleted: true })
    const { data, added, removed } = applyOverlay(listed, overlay, config, { ...view, filtered: true })

    expect(data.map((r) => r.name)).toEqual(['Alpha 2'])
    expect([added, removed]).toEqual([0, 1])
  })

  test('a new row lacking a required field waits for the rest', () => {
    const { data } = applyOverlay([], events({ '@id': '/api/things/A', name: 'Alpha' }), config, view)

    expect(data).toEqual([])
  })

  test('a permanent filter the client can check keeps other rows out', () => {
    const scoped: LiveConfig = { ...config, accept: (r) => r.owner === '/api/owners/O1' }
    const overlay = [created('A', 'Alpha'), { ...created('B', 'Bravo'), ownerId: 'O2' }].reduce<Overlay>(
      (acc, payload) => applyEvent(acc, payload, scoped),
      {},
    )
    const { data } = applyOverlay([], overlay, scoped, view)

    expect(data.map((r) => r.name)).toEqual(['Alpha'])
  })
})
