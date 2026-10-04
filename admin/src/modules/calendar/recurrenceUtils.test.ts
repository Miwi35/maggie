import { describe, expect, it } from 'vitest'
import { expandRrule } from './recurrenceUtils'

// Weekly lesson at 18:00 Paris on Sunday 18 Oct 2026 (CEST, 16:00 UTC). Clocks go
// back on Sunday 25 Oct at 03:00 CEST, so from that day 18:00 Paris is 17:00 UTC.
const DTSTART = new Date('2026-10-18T16:00:00Z')
const RANGE_START = new Date('2026-10-01T00:00:00Z')
const RANGE_END = new Date('2026-11-30T00:00:00Z')

describe('expandRrule', () => {
  it('keeps a weekly 18:00 Paris series at 18:00 across the autumn clock change', () => {
    const occurrences = expandRrule('FREQ=WEEKLY;COUNT=4', DTSTART, RANGE_START, RANGE_END, 'Europe/Paris')

    expect(occurrences.map((d) => d.toISOString())).toEqual([
      '2026-10-18T16:00:00.000Z',
      '2026-10-25T17:00:00.000Z',
      '2026-11-01T17:00:00.000Z',
      '2026-11-08T17:00:00.000Z',
    ])
  })

  it('keeps a weekly 18:00 Paris series at 18:00 across the spring clock change', () => {
    // Sunday 22 Mar 2026 18:00 CET = 17:00 UTC; clocks go forward on 29 Mar.
    const occurrences = expandRrule(
      'FREQ=WEEKLY;COUNT=3',
      new Date('2026-03-22T17:00:00Z'),
      new Date('2026-03-01T00:00:00Z'),
      new Date('2026-04-30T00:00:00Z'),
      'Europe/Paris',
    )

    expect(occurrences.map((d) => d.toISOString())).toEqual([
      '2026-03-22T17:00:00.000Z',
      '2026-03-29T16:00:00.000Z',
      '2026-04-05T16:00:00.000Z',
    ])
  })

  it('filters on the real instant of each occurrence, not on its wall-clock reading', () => {
    const occurrences = expandRrule(
      'FREQ=WEEKLY;COUNT=4',
      DTSTART,
      new Date('2026-10-25T00:00:00Z'),
      new Date('2026-10-25T17:30:00Z'),
      'Europe/Paris',
    )

    expect(occurrences.map((d) => d.toISOString())).toEqual(['2026-10-25T17:00:00.000Z'])
  })

  it('stays on fixed UTC when no time zone is given', () => {
    const occurrences = expandRrule('FREQ=WEEKLY;COUNT=3', DTSTART, RANGE_START, RANGE_END)

    expect(occurrences.map((d) => d.toISOString())).toEqual([
      '2026-10-18T16:00:00.000Z',
      '2026-10-25T16:00:00.000Z',
      '2026-11-01T16:00:00.000Z',
    ])
  })
})
