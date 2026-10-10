import { describe, expect, it } from 'vitest'
import { expandRrule, expandRruleDays } from './recurrenceUtils'

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

describe('expandRruleDays', () => {
  it('puts a yearly birthday on its date every year', () => {
    expect(expandRruleDays('FREQ=YEARLY', '2001-01-01', 0, '2036-12-01', '2037-02-01')).toEqual(['2037-01-01'])
  })

  it('keeps the occurrences that start in the range, and none on the end day', () => {
    expect(expandRruleDays('FREQ=WEEKLY', '2026-10-05', 0, '2026-10-01', '2026-10-26')).toEqual([
      '2026-10-05',
      '2026-10-12',
      '2026-10-19',
    ])
  })

  it('keeps an occurrence that started before the range and is still running in it', () => {
    // Three days a week, from Sat 26 Sep: the occurrence of 26–28 Sep still covers the
    // 28th, the first day shown; the next one (3 Oct) is past the range.
    expect(expandRruleDays('FREQ=WEEKLY', '2026-09-26', 2, '2026-09-28', '2026-10-01')).toEqual(['2026-09-26'])
  })

  it('honours an UNTIL written as the last day at 23:59:59Z', () => {
    expect(
      expandRruleDays('FREQ=DAILY;UNTIL=20261003T235959Z', '2026-10-01', 0, '2026-10-01', '2026-10-31'),
    ).toEqual(['2026-10-01', '2026-10-02', '2026-10-03'])
  })
})
