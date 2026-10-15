import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { fetchApiDocumentation, withRuleResource } from './apiDocumentation'

const parsed = { api: { resources: [] }, response: new Response(), status: 200 }
const noWait = [0, 0, 0]

describe('fetchApiDocumentation', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  test.each([
    ['the API restarts (503)', { status: 503 }],
    ['the proxy answers 502', { status: 502 }],
    ['the request gets no answer', { error: new TypeError('Failed to fetch') }],
  ])('waits and retries when %s', async (_label, failure) => {
    const parse = vi.fn().mockRejectedValueOnce(failure).mockRejectedValueOnce(failure).mockResolvedValue(parsed)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).resolves.toBe(parsed)
    expect(parse).toHaveBeenCalledTimes(3)
  })

  test('gives up with the parser failure once the retries are spent', async () => {
    const failure = { status: 503 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(noWait.length + 1)
  })

  test('does not retry an answer that will not change (404)', async () => {
    const failure = { status: 404 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(1)
  })

  test('drops the session and reloads on a 401 instead of retrying', async () => {
    localStorage.setItem('token', 'stale')
    const reload = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({ ...window.location, reload } as Location)
    const failure = { status: 401 }
    const parse = vi.fn().mockRejectedValue(failure)

    await expect(fetchApiDocumentation('http://localhost/api', parse as never, noWait)).rejects.toBe(failure)
    expect(parse).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('token')).toBeNull()
    expect(reload).toHaveBeenCalled()
  })
})

describe('withRuleResource', () => {
  class Resource {
    constructor(
      public name: string,
      public url: string,
      public fields: { name: string }[],
    ) {}
  }
  const operations = () => new Resource('recurring_operations', 'http://localhost/api/recurring_operations', [{ name: 'labelPattern' }])
  const documented = (...resources: Resource[]) => ({ api: { resources }, response: new Response(), status: 200 }) as never

  // A rule is an operation without recurrence (MAG-478): Hydra documents one
  // class per entity, so /categorization_rules/create crashed on « Resource
  // categorization_rules not present inside API description ».
  test('describes the rules as the operations, at their own path', () => {
    const parsed = withRuleResource(documented(operations()))
    const rules = (parsed as { api: { resources: Resource[] } }).api.resources.find(({ name }) => name === 'categorization_rules')

    expect(rules).toBeInstanceOf(Resource)
    expect(rules?.url).toBe('http://localhost/api/categorization_rules')
    expect(rules?.fields).toEqual([{ name: 'labelPattern' }])
  })

  test('leaves the documentation alone once it describes the rules itself', () => {
    const own = new Resource('categorization_rules', 'http://localhost/api/categorization_rules', [])
    const parsed = withRuleResource(documented(operations(), own))

    expect((parsed as { api: { resources: Resource[] } }).api.resources).toHaveLength(2)
  })

  test('is applied to what the parser returns', async () => {
    const parse = vi.fn().mockResolvedValue(documented(operations()))
    const parsed = await fetchApiDocumentation('http://localhost/api', parse as never, noWait)

    expect((parsed as { api: { resources: Resource[] } }).api.resources.map(({ name }) => name)).toEqual([
      'recurring_operations',
      'categorization_rules',
    ])
  })
})
