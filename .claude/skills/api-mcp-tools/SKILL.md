---
name: api-mcp-tools
description: "MCP tool conventions for the Symfony API. Use when creating or modifying MCP tools, including CRUD tool sets, bus dispatch, repository queries, and error handling."
user-invocable: false
---

# MCP Tools (symfony/mcp-bundle)

Transport: Streamable HTTP at `/_mcp`. Tools use `#[McpTool]` attribute.

## Enforcement

Every API entity MUST be fully manageable from MCP (list/create/update/delete).
Default shape: one consolidated tool per entity.

| Tool | Class | Injects |
|------|-------|---------|
| `manage_{entities}` | `Manage{Entities}Tool` | `MessageBusInterface` + repository + `McpUserContext` |

It takes an `action` argument (`list`, `create`, `update`, `delete`) and
dispatches to a private method per action — fewer tools in the agent's context.
Split tools (`create_/update_/delete_/get_`) stay only for Event, Task and
Recipe, whose operations carry many arguments. Never mix both for one entity.

Domain tools keep their own verb: `get_upcoming_events`, `search_products`,
`end_errand`, `generate_grocery_list`…

## Rules

- One tool per class in `Maggie\{Module}\Mcp\Tool\`
- Tool names: `snake_case`
- Return: JSON-encoded string, always
- Auto-discovered via autowiring — **the module must also be in
  `discovery.scan_dirs` (`config/packages/mcp.yaml`)**, or its tools never show up
- Unknown `action` / missing required argument → JSON error, never an exception

## User scoping

`/_mcp` is guarded by `McpAccessListener`: a user JWT, or `SERVICE_TOKEN` plus
an `X-Maggie-User-Id` header. Tools read the user from `McpUserContext`
(`requireUser()` / `getUser()`) and catch `MissingMcpUserException` — never
`userRepository->findAll()[0]`. List queries always go through `findByUser()`.

## Write Tools (Command side — CQRS)

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

## Read Tools (Query side — CQRS)

Read tools inject repositories directly, and always filter by the current user:

```php
#[McpTool(name: 'get_tasks', description: 'Get tasks by status.')]
class GetTasksTool
{
    public function __construct(
        private readonly TaskRepository $taskRepository,
        private readonly McpUserContext $userContext,
    ) {}

    public function __invoke(string $status = 'pending'): string
    {
        $user = $this->userContext->requireUser();
        $tasks = $this->taskRepository->findByStatus($user, $status);
        return json_encode([
            'status' => $status,
            'tasks' => array_map(fn(Task $t) => [...], $tasks),
            'count' => count($tasks),
        ], JSON_THROW_ON_ERROR);
    }
}
```

## Supporting Classes

| Class | Location | Naming |
|-------|----------|--------|
| Command | `Message/` | `{Action}{Entity}Command` (readonly DTO) |
| Handler | `MessageHandler/` | `{Action}{Entity}Handler` (`#[AsMessageHandler]`) |
| UseCase | `UseCase/` | `{Action}{Entity}` (injected by handler) |

## Reference

For full details, read `agent-os/standards/api/mcp-tools.md`
