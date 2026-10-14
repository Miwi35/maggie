---
name: admin-testing
description: "Vitest testing patterns for React Admin. Use when writing or reviewing tests for admin/ components including mocking, Testing Library queries, and jsdom polyfills."
user-invocable: false
---

# Admin Testing (Vitest)

**Use when** writing or reviewing a test in `admin/`.

**Read** `agent-os/standards/admin/testing.md`; what a ticket owes overall: `agent-os/standards/global/testing.md`.

## Gotchas

- Every component touched owes render, the interaction it exists for, and its error state; a Mercure subscriber also owes an `EventSource` message test.
- Test files sit next to the component (`{Component}.test.tsx`); jsdom polyfills go in `src/test/setup.ts`.
- `userEvent` over `fireEvent`, role/text queries first, `waitFor()` for async assertions.
- Assert what the user sees, not that a mock was called.
- A bug fix starts with a red test; the Playwright journey lives in `e2e/web/`.
