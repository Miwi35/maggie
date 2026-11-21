import { test, expect } from 'vitest'
import { TOKENS } from '../../design/tokens'
import { AGENDA_ACCENT } from './calendarTheme'

test("the departure accent is module.agenda's dark hue, not a copy", () => {
  expect(AGENDA_ACCENT).toBe(TOKENS.module.agenda.dark)
})
