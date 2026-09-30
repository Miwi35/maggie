# Global Search Feature with Elasticsearch

## Context

The Elasticsearch backend is fully built (10 indexed entities, `SearchService` with multi-index full-text search, CQRS read providers, async indexation via RabbitMQ). This plan adds:

1. A new `GET /api/search` REST endpoint wrapping the existing `SearchService`
2. An AppBar quick-search bar with `Ctrl+K` shortcut and grouped results dropdown
3. A dedicated `/search` page with type filter chips and paginated results

All search results link to their react-admin edit page.

## Tasks

### Task 2: Create `GET /api/search` Endpoint
- File: `api/modules/core/src/Controller/SearchController.php`
- Wraps existing `SearchService::search()`
- JWT auth, pagination, type filtering

### Task 3: Unit Test for SearchController
- File: `api/tests/Core/Controller/SearchControllerTest.php`
- Mocked SearchService and Security

### Task 4: Search Config + useSearch Hook
- File: `admin/src/modules/search/searchConfig.ts`
- Maps ES index names to admin display info + debounced search hook

### Task 5: SearchBar in AppBar
- Files: `admin/src/modules/search/SearchBar.tsx`, modified `AppBar.tsx`
- Ctrl+K shortcut, debounced input, grouped results dropdown

### Task 6: Search Page
- Files: `SearchPage.tsx`, `SearchResultCard.tsx`, `index.ts`
- Type filter chips, pagination, route at `/search`

### Task 7: Admin Tests
- Files: `SearchBar.test.tsx`, `SearchPage.test.tsx`
