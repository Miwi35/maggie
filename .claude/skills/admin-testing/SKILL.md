---
name: admin-testing
description: "Vitest testing patterns for React Admin. Use when writing or reviewing tests for admin/ components including mocking, Testing Library queries, and jsdom polyfills."
user-invocable: false
---

# Admin Testing (Vitest)

## Required Coverage — nothing ships without it

**Every component touched** gets a `.test.tsx` covering, at minimum:

- initial render (the data it displays, or its empty state)
- the user interaction it exists for — click, submit, select — with `userEvent`
- the error state: failed fetch, invalid form, missing permission
- for components subscribing to Mercure: an `EventSource` message updates the UI

Assert what the user sees, not that a mock was called.

**Bug fix → red first.** Write the failing test, run it, then fix. Both in the same PR.

**The feature also needs an e2e journey** (Playwright, `e2e/web/`) — see
`agent-os/standards/global/testing.md` (Definition of Done).

## File Location

Co-located with source: `{Component}.test.tsx` next to `{Component}.tsx`.

Setup file: `src/test/setup.ts` (jest-dom matchers, jsdom polyfills).

## Structure

```tsx
describe('ComponentName', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  test('renders initial state', () => { ... })
  test('handles user interaction', async () => { ... })
})
```

## Mocking

```tsx
// External modules
vi.mock('@api-platform/admin', () => ({
  HydraAdmin: ({ children }: Props) => <div>{children}</div>,
}))

// Browser APIs missing in jsdom
vi.stubGlobal('EventSource', MockEventSource)
vi.stubGlobal('fetch', vi.fn().mockResolvedValue(...))
```

jsdom polyfills in `src/test/setup.ts`:
```tsx
Element.prototype.scrollIntoView = () => {}
```

## Testing Library

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const user = userEvent.setup()
await user.click(screen.getByRole('button'))
await waitFor(() => expect(screen.getByText('Done')).toBeInTheDocument())
```

- Query by role/text first, testid as last resort
- `userEvent` over `fireEvent` for realistic interactions
- `waitFor()` for async assertions

## Reference

For full details, read `agent-os/standards/admin/testing.md`
