# Admin Testing (Vitest)

## File Location & Naming

Co-located with source:

```
admin/src/
  App.test.tsx
  components/chat/ChatWidget.test.tsx
  modules/calendar/CalendarView.test.tsx
```

- File: `{Component}.test.tsx` next to `{Component}.tsx`
- Setup: `src/test/setup.ts` (jest-dom matchers, jsdom polyfills)

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

// jsdom polyfills (in setup.ts)
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
