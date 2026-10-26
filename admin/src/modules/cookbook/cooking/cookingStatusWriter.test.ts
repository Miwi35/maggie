import { describe, expect, test } from 'vitest'
import { createStatusWriter } from './cookingStatusWriter'

const origin = { kind: 'meal', id: 'M1' } as const

const tick = () => new Promise<void>((resolve) => setTimeout(resolve))

const deferred = () => {
  let release: () => void = () => undefined
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })

  return { promise, release }
}

describe('createStatusWriter', () => {
  test('writes each state in order', async () => {
    const sent: unknown[] = []
    const writer = createStatusWriter(
      (_origin, state) => {
        sent.push(state)

        return Promise.resolve()
      },
      () => Promise.resolve(),
    )

    writer.save(origin, { step: 1 })
    await writer.settled()
    writer.save(origin, { step: 2 })
    await writer.settled()

    expect(sent).toEqual([{ step: 1 }, { step: 2 }])
  })

  test('while a write is out, only the latest state follows', async () => {
    const sent: unknown[] = []
    const gate = deferred()
    const writer = createStatusWriter(
      (_origin, state) => {
        sent.push(state)

        return sent.length === 1 ? gate.promise : Promise.resolve()
      },
      () => Promise.resolve(),
    )

    writer.save(origin, { step: 1 })
    await tick()
    writer.save(origin, { step: 2 })
    writer.save(origin, { step: 3 })
    gate.release()
    await writer.settled()

    expect(sent).toEqual([{ step: 1 }, { step: 3 }])
  })

  test('clearing drops what waits, then removes the status after the write in flight', async () => {
    const calls: string[] = []
    const gate = deferred()
    const writer = createStatusWriter(
      () => {
        calls.push('put')

        return gate.promise
      },
      () => {
        calls.push('delete')

        return Promise.resolve()
      },
    )

    writer.save(origin, { step: 1 })
    await tick()
    writer.save(origin, { step: 2 })
    writer.clear()
    gate.release()
    await writer.settled()

    expect(calls).toEqual(['put', 'delete'])
  })

  test('a write that follows a clear is sent after the removal', async () => {
    const calls: string[] = []
    const writer = createStatusWriter(
      () => {
        calls.push('put')

        return Promise.resolve()
      },
      () => {
        calls.push('delete')

        return Promise.resolve()
      },
    )

    writer.clear()
    writer.save(origin, { step: 0 })
    await writer.settled()

    expect(calls).toEqual(['delete', 'put'])
  })

  test('a failed write is dropped and the next one goes out', async () => {
    const sent: unknown[] = []
    const writer = createStatusWriter(
      (_origin, state) => {
        sent.push(state)

        return sent.length === 1 ? Promise.reject(new Error('offline')) : Promise.resolve()
      },
      () => Promise.reject(new Error('offline')),
    )

    writer.save(origin, { step: 1 })
    await writer.settled()
    writer.save(origin, { step: 2 })
    writer.clear()
    await writer.settled()

    expect(sent).toEqual([{ step: 1 }])
  })
})
