# Reference Code — Global Search

## API References
- `api/modules/core/src/Elasticsearch/SearchService.php` — search logic to wrap
- `api/modules/core/src/Controller/GoogleAuthController.php` — controller pattern
- `api/modules/core/src/Elasticsearch/State/ElasticsearchCollectionProvider.php:109-115` — auth pattern
- `api/tests/Core/Elasticsearch/Mcp/SearchToolTest.php` — test pattern with mocked SearchService

## Admin References
- `admin/src/modules/settings/resources.tsx` — CustomRoutes pattern
- `admin/src/modules/cookbook/resources.tsx` — complex resources pattern
- `admin/src/App.tsx` — module composition
- `admin/src/components/layout/AppBar.tsx` — AppBar to modify
- `admin/src/components/chat/ChatWidget.test.tsx` — test pattern with mocks and user events
- `admin/src/modules/calendar/CalendarView.test.tsx` — test pattern with data provider mocks
