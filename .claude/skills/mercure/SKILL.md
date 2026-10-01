---
name: mercure
description: "Mercure real-time SSE conventions. Use when implementing real-time updates, Mercure topics, EventSource subscriptions, or server-to-server publishing."
user-invocable: false
---

# Mercure Real-Time (SSE)

All real-time updates use **Mercure** (SSE), not WebSockets.

## Why Mercure

- Native API Platform integration: `#[ApiResource(mercure: true)]` auto-publishes
- SSE simpler than WebSocket lifecycle (no heartbeat, reconnect built-in)

## URLs

| Context | URL |
|---------|-----|
| Internal (server-to-server) | `http://mercure/.well-known/mercure` |
| Public (client-facing) | `http://maggie.local/.well-known/mercure` (via Traefik) |

## Topic Conventions

- Entity updates: `/api/{resource}/{id}` (auto from API Platform)
- User-scoped: `/users/{userId}/api/{resource}/{id}` (middleware adds prefix)
- Agent (published by the agent service, not under `/users/`): `/chat/{userId}`, `/contexts/{userId}`, `/proactions/{userId}`, `/instructions/{userId}`, `/skills/{userId}`. `userId` is the API user's ULID, the `sub` of the JWT the agent authenticates — never the `user_id` field/param the mobile sends (`"default"`, ignored). Spelled only in `agent/app/mercure/topics.py`, published in `agent/contract/mercure-topics.json`; clients use `MercureTopics.agentScoped` (mobile) and `agentTopic()` (admin). `MercureService.subscribe` never substitutes: a `{userId}` in a topic is a dead subscription.

## Privacy — every update is private

A public update is delivered to any subscriber whose requested topic matches, **whatever their token's `mercure.subscribe` claim says**. So:
- PHP: `new Update(topics, data, private: true)`; agent: `private=on` in the publish form (`MercurePublisher` does it).
- The subscriber token (`MercureSubscriberTokenFactory`) lists `/users/{id}/{+topic}` (`{+topic}` crosses `/`, `{topic}` does not) plus the agent topics above, keyed by the user's id. A new topic outside `/users/{id}/` needs a selector there or it goes silent.
- Every `EventSource` passes `{ withCredentials: true }` (cookie `mercureAuthorization`); the hub runs without `anonymous`.

## API Entity Publication

Every entity MUST implement `MercurePublishable` with `toMercurePayload()`:
- Returns data fields only (no IRI — middleware adds `@id`)
- `MercurePublishMiddleware` auto-publishes on Create/Update/Delete commands
- Delete publishes `{'@id': '...', 'deleted': true}`
- Topic pattern: `/users/{userId}/api/{entities}/{id}`

## Differential Updates

**Rule: publish only changed fields, never full entity snapshots for updates.**

Two mechanisms handle differential updates:

### 1. CRUD Update — UOW-based differential (automatic)

For standard CRUD Update commands, the middleware automatically publishes only the changed fields using Doctrine UOW changesets:

```
Handler modifies entity → $em->flush()
  → onFlush: ChangesetCaptureListener stores changed field names in ChangesetStore
  → SQL executes, UOW clears
  → Handler returns entity via HandledStamp
  → MercurePublishMiddleware reads ChangesetStore
  → entity->toMercurePayload($changedProperties) → filtered payload
```

**Entity implementation** — use `MercurePayloadFilterTrait`:

```php
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;

class Event implements MercurePublishable
{
    use MercurePayloadFilterTrait;

    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'summary' => $this->summary,
            'startAt' => $this->startAt->format('c'),
            'endAt' => $this->endAt->format('c'),
        ], $changedProperties);
    }
}
```

**Property mapping** — when Doctrine field name differs from payload key:

```php
// Task: Doctrine tracks 'completedAt', payload uses 'isDone'
return self::filterPayload($payload, $changedProperties, ['completedAt' => 'isDone']);

// Product: Doctrine tracks 'preferredStore', payload uses 'preferredStoreId'
return self::filterPayload($payload, $changedProperties, [
    'preferredStore' => 'preferredStoreId',
    'fallbackStore' => 'fallbackStoreId',
]);
```

**Fallback** — if no changeset properties map to payload keys (e.g. unmapped collection changes), the full payload is published.

### 2. Non-CRUD Actions — MercureActionPayload (explicit)

Non-CRUD commands (Reorder, Check, Remove, etc.) MUST implement `MercureActionPayload` to publish lightweight action payloads instead of the full `toMercurePayload()`.

```php
// api/modules/core/src/Contract/MercureActionPayload.php
interface MercureActionPayload
{
    /** @return array<string, mixed> */
    public function toMercureActionPayload(): array;
}
```

Command example:

```php
final readonly class CheckGroceryItemCommand implements MercureActionPayload
{
    public function __construct(
        public string $groceryItemId,
        public bool $checked,
    ) {}

    public function toMercureActionPayload(): array
    {
        return ['action' => 'check', 'itemId' => $this->groceryItemId, 'checked' => $this->checked];
    }
}
```

### Action payload conventions

- Always include an `action` field to identify the operation
- Include only the changed fields / identifiers
- Examples:
  - Reorder: `{action: "reorder", items: [{id, position}, ...]}`
  - Check: `{action: "check", itemId: "...", checked: true}`
  - Remove: `{action: "remove", itemId: "..."}`

### Client — Apply patches inline, fallback to refresh

Clients MUST parse the `action` field and apply the diff to local state. If parsing fails or the action is unknown, fall back to `refresh()`.

**Admin (React):**

```tsx
const handleMercure = useCallback((data?: string) => {
  if (!data) { fetchList(); return }
  try {
    const payload = JSON.parse(data)
    switch (payload.action) {
      case 'reorder': /* apply position map */ break
      case 'check':   /* toggle item checked */ break
      case 'remove':  /* filter out item */ break
      default:
        // Full data payload (create/add) or unknown — try items array, else refetch
        if (Array.isArray(payload.items)) { /* replace items */ }
        else { fetchList() }
    }
  } catch { fetchList() }
}, [fetchList])
useMercure(TOPICS, handleMercure)
```

**Mobile (Kotlin):**

```kotlin
val payload = json.parseToJsonElement(event.data).jsonObject
when (payload["action"]?.jsonPrimitive?.contentOrNull) {
    "reorder" -> { /* apply position map */ }
    "check"   -> { /* toggle item checked */ }
    "remove"  -> { /* filter out item */ }
    else      -> { /* try full items array, else refresh() */ }
}
```

### When to use what

| Scenario | Mechanism | Payload |
|----------|-----------|---------|
| CRUD Create | `toMercurePayload(null)` | All entity fields |
| CRUD Update | `toMercurePayload($changedProperties)` | Only changed fields (auto via UOW) |
| CRUD Delete | Middleware auto | `{@id, deleted: true}` |
| Non-CRUD mutation (Check, Reorder, Remove, Move…) | `MercureActionPayload.toMercureActionPayload()` | Only changed fields + `action` |

## Admin useMercure Hook

`admin/src/hooks/useMercure.ts` — subscribes to user-scoped topics via SSE.

- Callback stored in a ref (not in effect deps) — prevents EventSource recreation
- Signature: `useMercure(topics: string[], onMessage: (data?: string) => void)`
- For simple refetch callers: `useMercure(TOPICS, () => { refetch() })`
- For patch-aware callers: parse `data` and apply inline

## Health Check

No `/healthz` endpoint. Test with: `curl http://maggie.local/.well-known/mercure`

## Reference

- `api/modules/core/src/Contract/MercureActionPayload.php`
- `api/modules/core/src/Contract/MercurePublishable.php`
- `api/modules/core/src/Mercure/ChangesetStore.php`
- `api/modules/core/src/Mercure/Listener/ChangesetCaptureListener.php`
- `api/modules/core/src/Mercure/Trait/MercurePayloadFilterTrait.php`
- `api/modules/core/src/Mercure/Middleware/MercurePublishMiddleware.php`
- `admin/src/hooks/useMercure.ts`
- `mobile/app/src/main/java/com/maggie/app/ui/screens/cookbook/grocery/GroceryViewModel.kt` (reference client)
- `admin/src/modules/grocery/GroceryListView.tsx` (reference client)
- `agent-os/standards/global/real-time.md`
