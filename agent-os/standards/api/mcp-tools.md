# MCP Tools

MCP tools use `symfony/mcp-bundle` with `#[McpTool]` attribute. Transport: Streamable HTTP at `/_mcp`.

## Enforcement

**Every API entity MUST be fully manageable from MCP** — list, create, update
and delete. The default shape is one consolidated tool per entity:

| Tool | Class | Injects |
|------|-------|---------|
| `manage_{entities}` | `Manage{Entities}Tool` | `MessageBusInterface` + repository + `McpUserContext` |

It takes an `action` argument (`list`, `create`, `update`, `delete`) and
dispatches to a private method per action. One tool per entity keeps the
agent's tool list short, which matters more than tool granularity.

Split tools (`create_{entity}` / `update_{entity}` / `delete_{entity}` /
`get_{entities}`, one class each) remain for entities whose operations carry
many arguments or diverge in shape — Event, Task and Recipe are the ones we
keep this way. Do not mix the two for one entity.

Domain tools on top of the CRUD set are encouraged and keep their own verb:
`get_upcoming_events`, `check_conflicts`, `search_products`, `end_errand`,
`generate_grocery_list`, `assign_product_store`…

## Rules

- One tool per class in `Maggie\{Module}\Mcp\Tool\`
- Tool names: `snake_case`
- Return: JSON-encoded string, always
- Auto-discovered via service autowiring — **and the module must be listed in
  `discovery.scan_dirs` in `config/packages/mcp.yaml`**, otherwise its tools
  exist but are never exposed
- Unknown `action` returns an error listing the valid ones; a missing required
  argument returns an error naming it

## Authentication and user scoping

`/_mcp` is outside the API firewall — `McpAccessListener` (Maggie\Core\Mcp)
guards it and rejects anything without a bearer token:

| Credential | Who | User acted for |
|---|---|---|
| user JWT | a human client (prompt lab, curl) | the token subject (`sub`) |
| `SERVICE_TOKEN` + `X-Maggie-User-Id` | the agent hub | the header's user |
| `SERVICE_TOKEN` alone | the agent at startup | none — `initialize` / `tools/list` only |

The resolved user goes into the security token storage, so tools read it with
`McpUserContext`:

```php
$user = $this->userContext->requireUser();   // throws MissingMcpUserException
$user = $this->userContext->getUser();       // null when nothing is bound
```

**Never resolve the user from the database** (`findAll()[0]`, `findOneBy([])`):
that returns someone else's data as soon as a second user exists. Every list
query goes through a `findByUser()` on the repository, and `__invoke()` catches
`MissingMcpUserException` to answer with a JSON error rather than a 500.

## Write Tools (Command side — CQRS)

Write tools dispatch a Command to the Messenger bus. The full chain:

**Tool → Bus → Handler → UseCase → Persist + Mercure (via middleware)**

```php
#[McpTool(name: 'manage_stores', description: 'List, create, update, or delete stores. …')]
class ManageStoresTool
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly StoreRepository $storeRepository,
        private readonly McpUserContext $userContext,
    ) {}

    public function __invoke(string $action, ?string $storeId = null, ?string $name = null): string
    {
        try {
            return match ($action) {
                'list' => $this->list(),
                'create' => $this->create($name),
                'update' => $this->update($storeId, $name),
                'delete' => $this->delete($storeId),
                default => json_encode(['error' => "Unknown action: {$action}. Use list, create, update, or delete."], JSON_THROW_ON_ERROR),
            };
        } catch (MissingMcpUserException $e) {
            return json_encode(['error' => $e->getMessage()], JSON_THROW_ON_ERROR);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;
            return json_encode(['error' => $cause->getMessage()], JSON_THROW_ON_ERROR);
        }
    }

    private function create(?string $name): string
    {
        if ($name === null) {
            return json_encode(['error' => 'Name is required for create.'], JSON_THROW_ON_ERROR);
        }

        $user = $this->userContext->requireUser();

        $envelope = $this->bus->dispatch(new CreateStoreCommand(userId: (string) $user->getId(), name: $name));

        /** @var Store $store */
        $store = $envelope->last(HandledStamp::class)->getResult();

        return json_encode(['success' => true, 'store' => $this->serialize($store)], JSON_THROW_ON_ERROR);
    }
}
```

Required supporting classes per entity:

| Class | Location | Naming |
|-------|----------|--------|
| Command | `Message/` | `{Action}{Entity}Command` (readonly DTO) |
| Handler | `MessageHandler/` | `{Action}{Entity}Handler` (`#[AsMessageHandler]`) |
| UseCase | `UseCase/` | `{Action}{Entity}` (injected by handler) |

## Read Tools (Query side — CQRS)

Read tools inject repositories directly, and always filter by the current user.
This will evolve toward a CQRS Query pattern (TBD).

```php
#[McpTool(name: 'get_tasks', description: 'Get tasks by status.')]
class GetTasksTool
{
    public function __construct(private readonly TaskRepository $taskRepository) {}

    public function __invoke(string $status = 'pending'): string
    {
        $tasks = match ($status) {
            'pending' => $this->taskRepository->findPending(),
            'done' => $this->taskRepository->findDone(),
            // ...
        };

        return json_encode([
            'status' => $status,
            'tasks' => array_map(fn(Task $t) => [...], $tasks),
            'count' => count($tasks),
        ], JSON_THROW_ON_ERROR);
    }
}
```

## Calendar schedule contract (MAG-321)

`create_event` and `update_event` take a start and an end, never a duration, in one of two complete forms: `start_date` + `start_time` + `end_date` + `end_time`, or `all_day: true` + `start_date` + `end_date` (last day included). Nothing is deduced: an incomplete schedule, or an end not after the start, is refused with nothing written, and `update_event` then returns `currentSchedule`. The schedule is parsed by `EventSchedule`, and the result carries `EventSchedule::describe()` — `allDay`, and `startAt`/`endAt` in the event's own zone for a timed event, `startDate`/`endDate` (last day included, no instant) for an all-day one (MAG-382) — so Maggie announces what was stored. A tool that gives Maggie an input to deduce from "now" or from the stored value will announce what she meant, not what happened: ask for the whole value.

## New Entity Checklist

When adding a new entity to the API:

1. Create entity implementing `MercurePublishable` (see [entities](entities.md))
2. Create `Create/Update/Delete{Entity}Command` DTOs
3. Create `Create/Update/Delete{Entity}Handler` message handlers
4. Create `Create/Update/Delete{Entity}` use cases
5. Add a `findByUser()` query to the repository
6. Create the `Manage{Entities}Tool` MCP tool (or the split set, for a rich entity)
7. Register commands in `messenger.yaml` routing (sync transport)
8. Make sure the module is in `discovery.scan_dirs` (`config/packages/mcp.yaml`)
9. Add Mercure publication tests (see [testing](testing.md))
10. Add MCP tool tests, including the no-user case (see [testing](testing.md))

## Current tools

| Module | CRUD tools | Domain tools |
|---|---|---|
| calendar | `manage_agendas`, `create_event`/`update_event`/`delete_event`, `create_task`/`update_task`/`delete_task` | `get_events_by_date`, `get_upcoming_events`, `get_tasks`, `check_conflicts` |
| cookbook | `manage_meals`, `manage_ingredients`, `create_recipe`/`update_recipe`/`delete_recipe` | `get_recipe`, `search_recipes`, `search_ingredients`, `search_ciqual_foods`, `generate_grocery_list` |
| grocery | `manage_stores`, `manage_products`, `manage_recurring_groceries` | `get_grocery_list`, `add_grocery_item`, `check_grocery_item`, `remove_grocery_item`, `reorder_grocery_items`, `search_products`, `assign_product_store`, `move_to_fallback`, `end_errand` |
| finance | `manage_accounts`, `manage_categories`, `manage_transactions` | — |
| notification | `manage_notifications` | — |
| core | — | `search` (cross-module Elasticsearch) |
