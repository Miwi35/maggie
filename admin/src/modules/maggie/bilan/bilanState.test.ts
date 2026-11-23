import { describe, expect, test } from 'vitest'
import { recordedResponse } from '../../../test/recordedResponse'
import type { BilanSession } from './bilanSession'
import type { BilanStatus } from './bilanStatusApi'
import { isPrepared, leftForTomorrow, plannedForTomorrow, tomorrowSpares, tomorrowTasks, withSwap, withTripAnswer } from './bilanState'

const TODAY = '2026-11-23'

const session = (state: Record<string, unknown>): BilanSession => ({ day: TODAY, step: 'demain', state })

describe('bilanState', () => {
  test('reads the lists of the recorded bilan', () => {
    const recorded = recordedResponse<BilanStatus>('activity_status.bilan.read.json')
    const read = session(recorded.state)

    expect(tomorrowTasks(read).map((task) => task.title)).toEqual(['Trier les papiers', 'Appeler le garage'])
    expect(tomorrowTasks(read)[0].reason).toBe('reportée hier')
    expect(tomorrowSpares(read).map((task) => task.title)).toEqual(['Ranger les photos'])
  })

  test('a swap takes the first spare and sends the task out last in the reserve', () => {
    const read = session({
      tomorrowTasks: [{ taskId: 'A', title: 'A' }, { taskId: 'B', title: 'B' }],
      tomorrowSpares: [{ taskId: 'C', title: 'C' }, { taskId: 'D', title: 'D' }],
    })

    expect(withSwap(read, 'A')).toEqual({
      tomorrowTasks: [{ taskId: 'C', title: 'C' }, { taskId: 'B', title: 'B' }],
      tomorrowSpares: [{ taskId: 'D', title: 'D' }, { taskId: 'A', title: 'A' }],
    })
  })

  test('no spare, no swap', () => {
    expect(withSwap(session({ tomorrowTasks: [{ taskId: 'A', title: 'A' }], tomorrowSpares: [] }), 'A')).toBeNull()
  })

  test('swapping a task left at Journée puts it off', () => {
    const read = session({ answers: { tasks: [{ taskId: 'J', choice: 'tomorrow' }] }, tomorrowTasks: [], tomorrowSpares: [{ taskId: 'C', title: 'C' }] })

    expect(leftForTomorrow(read)).toEqual(['J'])
    const patch = withSwap(read, 'J', 'Journée task')
    expect(patch?.answers).toMatchObject({ tasks: [{ taskId: 'J', choice: 'later' }] })
    expect(patch?.tomorrowSpares).toEqual([{ taskId: 'J', title: 'Journée task' }])
  })

  test('the tasks left for tomorrow come first, without duplicates', () => {
    const read = session({ answers: { tasks: [{ taskId: 'B', choice: 'tomorrow' }] }, tomorrowTasks: [{ taskId: 'A', title: 'A' }, { taskId: 'B', title: 'B', reason: 'Parce que' }] })

    expect(plannedForTomorrow(read).map((task) => [task.taskId, task.fromJournee])).toEqual([['B', true], ['A', false]])
  })

  test('a step-less status of today is prepared', () => {
    const origin = { kind: 'day', id: TODAY }
    const status = (state: Record<string, unknown>, id = TODAY) => ({ origin: { ...origin, id }, state, updatedAt: '' }) as BilanStatus

    expect(isPrepared(status({ tomorrowTasks: [] }), TODAY)).toBe(true)
    expect(isPrepared(status({ step: 'sante' }), TODAY)).toBe(false)
    expect(isPrepared(status({}, '2026-11-20'), TODAY)).toBe(false)
    expect(isPrepared(null, TODAY)).toBe(false)
  })

  test('a trip answer is recorded once, and can be taken back', () => {
    const read = session({ answers: { trips: ['E1:origin'] } })

    expect(withTripAnswer(read, 'E1:mode')).toEqual({ answers: { trips: ['E1:mode', 'E1:origin'] } })
    expect(withTripAnswer(read, 'E1:origin', false)).toEqual({ answers: { trips: [] } })
  })
})
