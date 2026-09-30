# Applicable Standards — Global Search

## API
- Symfony controller with `#[Route]` attribute, `final` class, constructor DI
- JWT authentication via `Security::getUser()`
- JSON responses with proper HTTP status codes (200, 400, 401)
- User entity uses ULID IDs

## Admin
- React modules export resources via `resources.tsx` with `CustomRoutes`
- MUI components for UI (TextField, Paper, Chip, Popper, Pagination)
- French labels throughout
- Tests: Vitest + @testing-library/react + @testing-library/user-event
- Mock external dependencies with `vi.mock()`, global APIs with `vi.stubGlobal()`
