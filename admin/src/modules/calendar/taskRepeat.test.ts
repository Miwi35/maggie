import { describe, test, expect } from 'vitest'
import {
  buildRule,
  checkedChoice,
  parseRule,
  pickChoice,
  repeatOf,
  repeats,
  ruleText,
  untilLabel,
  untilSuffix,
  withCustom,
  withEnd,
  type RepeatRule,
} from './taskRepeat'

const TUESDAY = '2026-11-24'
const WEDNESDAY = '2026-11-25'

const text = (rrule: string | null, anchor: string | null = TUESDAY) => ruleText(rrule, anchor)
const sent = (rrule: string | null, anchor = TUESDAY) => repeatOf(rrule, anchor)

describe('« Répéter » contract (MAG-888, as MAG-887)', () => {
  test('no rule reads Jamais', () => {
    expect(text(null)).toBe('Jamais')
  })

  test('daily reads Chaque jour, every 3 days reads Tous les 3 jours', () => {
    expect(text('FREQ=DAILY')).toBe('Chaque jour')
    expect(text('FREQ=DAILY;INTERVAL=3')).toBe('Tous les 3 jours')
  })

  test('weekly on Tuesday reads Chaque mardi', () => {
    expect(text('FREQ=WEEKLY;BYDAY=TU')).toBe('Chaque mardi')
  })

  test('every other Tuesday reads Un mardi sur deux', () => {
    expect(text('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')).toBe('Un mardi sur deux')
  })

  test('every 3 weeks on Monday and Thursday reads Le lundi et jeudi, toutes les 3 semaines', () => {
    expect(text('FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,TH')).toBe('Le lundi et jeudi, toutes les 3 semaines')
  })

  test('weekly on three days reads Chaque lundi, mercredi et vendredi', () => {
    expect(text('FREQ=WEEKLY;BYDAY=MO,WE,FR')).toBe('Chaque lundi, mercredi et vendredi')
  })

  test("weekly without BYDAY reads the anchor's weekday", () => {
    expect(text('FREQ=WEEKLY')).toBe('Chaque mardi')
    expect(text('FREQ=WEEKLY', WEDNESDAY)).toBe('Chaque mercredi')
  })

  test('monthly on the 3rd reads Le 3 de chaque mois, on the 1st Le 1er de chaque mois', () => {
    expect(text('FREQ=MONTHLY;BYMONTHDAY=3')).toBe('Le 3 de chaque mois')
    expect(text('FREQ=MONTHLY;BYMONTHDAY=1')).toBe('Le 1er de chaque mois')
  })

  test('every 2 months on the 24th reads Le 24, tous les 2 mois', () => {
    expect(text('FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=24')).toBe('Le 24, tous les 2 mois')
  })

  test("monthly without BYMONTHDAY reads the anchor's day", () => {
    expect(text('FREQ=MONTHLY')).toBe('Le 24 de chaque mois')
  })

  test('a rule outside the contract falls back to the event text', () => {
    expect(text('FREQ=YEARLY')).toBe('Tous les ans')
    expect(parseRule('FREQ=MONTHLY;BYDAY=MO;BYSETPOS=2')).toBeNull()
    expect(parseRule('FREQ=MONTHLY;BYMONTHDAY=-1')).toBeNull()
    expect(text('FREQ=MONTHLY;BYMONTHDAY=-1')).toBe('Tous les mois')
  })

  test('UNTIL stays out of the rule text and reads as a suffix on the sheet', () => {
    const rule = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;UNTIL=20261231'
    expect(text(rule)).toBe('Un mardi sur deux')
    expect(untilSuffix(rule)).toBe(', jusqu’au jeu. 31 déc.')
    expect(untilSuffix('FREQ=DAILY')).toBeNull()
  })

  test('the end field reads Aucune fin or the day', () => {
    expect(untilLabel(null)).toBe('Aucune fin')
    expect(untilLabel('2026-12-31')).toBe('Jeu. 31 déc.')
  })

  test('each preset writes its rule from the anchor day', () => {
    const write = (choice: Parameters<typeof pickChoice>[1], anchor = TUESDAY) =>
      buildRule(pickChoice(repeatOf(null, anchor), choice), anchor)
    expect(write('never')).toBeNull()
    expect(write('daily')).toBe('FREQ=DAILY')
    expect(write('weekly')).toBe('FREQ=WEEKLY;BYDAY=TU')
    expect(write('biweekly')).toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')
    expect(write('monthly')).toBe('FREQ=MONTHLY;BYMONTHDAY=24')
    expect(write('weekly', WEDNESDAY)).toBe('FREQ=WEEKLY;BYDAY=WE')
  })

  test('a custom rule writes INTERVAL above 1 only and its days in week order', () => {
    const write = (rule: RepeatRule) => buildRule(withCustom(repeatOf(null, TUESDAY), rule), TUESDAY)
    expect(write({ unit: 'day', interval: 5, days: [] })).toBe('FREQ=DAILY;INTERVAL=5')
    expect(write({ unit: 'day', interval: 1, days: [] })).toBe('FREQ=DAILY')
    expect(write({ unit: 'week', interval: 3, days: ['SU', 'MO', 'FR'] })).toBe('FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,FR,SU')
    expect(write({ unit: 'month', interval: 2, days: [] })).toBe('FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=24')
  })

  test('an end day is written UNTIL=YYYYMMDD, last', () => {
    const state = withEnd(pickChoice(repeatOf(null, TUESDAY), 'biweekly'), '2026-12-31')
    expect(buildRule(state, TUESDAY)).toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;UNTIL=20261231')
    expect(buildRule(withEnd(state, null), TUESDAY)).toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')
  })

  test('the preset checked is the one that writes the rule, anything else is custom', () => {
    const checked = (rule: string | null) => checkedChoice(sent(rule), TUESDAY)
    expect(checked(null)).toBe('never')
    expect(checked('FREQ=DAILY')).toBe('daily')
    expect(checked('FREQ=WEEKLY;BYDAY=TU;UNTIL=20261231')).toBe('weekly')
    expect(checked('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')).toBe('biweekly')
    expect(checked('FREQ=MONTHLY;BYMONTHDAY=24')).toBe('monthly')
    expect(checked('FREQ=WEEKLY;BYDAY=MO')).toBe('custom')
    expect(checked('FREQ=DAILY;INTERVAL=3')).toBe('custom')
    expect(checked('FREQ=YEARLY')).toBe('custom')
  })

  test('an untouched rule is sent as stored even when the day moves', () => {
    const state = sent('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')
    expect(buildRule(state, WEDNESDAY)).toBe('FREQ=WEEKLY;INTERVAL=2;BYDAY=TU')
    expect(state.touched).toBe(false)
  })

  test('Jamais clears the end day, a preset keeps it', () => {
    const state = sent('FREQ=DAILY;UNTIL=20261231')
    expect(state.until).toBe('2026-12-31')
    expect(pickChoice(state, 'weekly').until).toBe('2026-12-31')
    const never = pickChoice(state, 'never')
    expect(never.until).toBeNull()
    expect(buildRule(never, TUESDAY)).toBeNull()
  })

  test('no anchor day means no rule', () => {
    expect(buildRule(pickChoice(repeatOf(null, null), 'daily'), null)).toBeNull()
  })

  test('only the end day touched keeps a rule the form cannot write', () => {
    expect(buildRule(withEnd(sent('FREQ=YEARLY'), '2030-01-01'), TUESDAY)).toBe('FREQ=YEARLY;UNTIL=20300101')
  })

  test('repeats only for an open task with a rule', () => {
    expect(repeats({ rrule: 'FREQ=DAILY' })).toBe(true)
    expect(repeats({ rrule: 'FREQ=DAILY', completedAt: '2026-11-24T10:00:00+00:00' })).toBe(false)
    expect(repeats({ rrule: null })).toBe(false)
    expect(repeats({})).toBe(false)
  })
})
