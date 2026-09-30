# Shaping Decisions — Global Search

## Approach
- Thin REST controller wrapping existing `SearchService` (no new Elasticsearch logic)
- Admin search module as standalone React module following existing module pattern
- AppBar quick-search with 3-per-type limit, full page for complete results

## Boundaries
- No new indexing logic — reuse all 10 existing ES indices
- No server-side rendering of highlights — pass raw `<em>` tags, render client-side
- No search history or saved searches in v1
- No autocomplete/suggestions — plain full-text search with fuzzy matching

## Key Decisions
1. **Separate endpoint vs API Platform resource**: Chose plain Symfony controller because search is cross-entity and doesn't map to a single API Platform resource
2. **Debounce strategy**: 300ms client-side debounce, no server-side rate limiting
3. **Pagination model**: Page-based (not cursor) matching ES `from`/`size` parameters
4. **Type filter**: Client-side chip selection passed as `types` query param
5. **French UI**: All labels in French matching existing admin convention
