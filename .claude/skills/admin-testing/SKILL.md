---
name: admin-testing
description: "Vitest testing patterns for React Admin. Use when writing or reviewing tests for admin/ components including mocking, Testing Library queries, and jsdom polyfills."
user-invocable: false
---

# Admin Testing (Vitest)

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
