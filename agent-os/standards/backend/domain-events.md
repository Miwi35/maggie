# Domain Events

A business effect (add to a list, notify, recompute…) is the **consequence of a domain event**, i.e. of a state transition. A handler listens to the event. No use case, MCP tool or controller calls the effect itself: whichever door saves the entity, the effect follows.

## The chain

```
UpdateProductHandler → use case → flush
  └─ Doctrine listener (ProductStockListener)
       onFlush   : notes the entities and their changeset
       postFlush : if ((new IsProductOutOfStock($changeSet))->isSatisfiedBy($product))
                       $eventBus->dispatch(new ProductOutOfStockEvent($productId))
            └─ event handler (RestockOnProductOutOfStock): $commandBus->dispatch(new RestockProductCommand($productId))
                 └─ command handler (RestockProductHandler) → use case RestockProduct → service GroceryListItems::addProduct()
                      ← the list's lines are read from Doctrine: ProjectionMiddleware publishes and indexes the list (projection.md)
```

| Tier | Does | Never |
|---|---|---|
| Doctrine listener | notes the changeset (`onFlush`), asks the Specification, dispatches the event (`postFlush`) | any business effect |
| Specification (`isSatisfiedBy`) | holds the rule « did this transition happen? », reads the changeset, has its own unit test | side effects |
| Event | a fact in the past (`ProductOutOfStockEvent`), **identifiers only, never an entity** | logic |
| Event handler | translates the fact into a command on the command bus | logic, a direct call to a use case |
| Command handler → use case → service | the rule (`autoRestock`? quantity?) and the write; the service is the single place the logic lives | being called from anywhere but the use case |

## Buses

- `messenger.bus.default`: commands, with `ProjectionMiddleware` (`projection.md`).
- `event.bus`: events only, `allow_no_handlers` (an event nobody listens to is fine), no Mercure or Elasticsearch middleware. Inject each bus by name (`#[Autowire(service: 'event.bus')]`); the handler of an event is `#[AsMessageHandler(bus: 'event.bus')]`.
- Route the event in `messenger.yaml` (`sync` like the commands of the module). Going `async` later is a routing change only.

## Rules

1. **Dispatch in `postFlush`, never in `onFlush`**: the changeset only exists during `onFlush`, so note it there; a handler that flushes while the unit of work is still computing would corrupt it. Default choice, a case may justify another.
2. **Empty the pending list before dispatching**: a handler may flush again and re-enter `postFlush`.
3. **A Specification reads the changeset** (before → after), so a re-save of the same state is not a transition. Doctrine gives the previous enum value as a string, and `null` before a creation.
4. **Creation counts**: the listener covers inserts as well as updates.
5. **Forget on `preFlush`**: a flush that fails after `onFlush` never reaches `postFlush`; the listener empties what it noted when the next flush starts, so a rolled-back transition is never sent later.
6. **The save outlives its effects**: `postFlush` runs once the row is committed (outside an enclosing transaction), so a failing event is logged and swallowed — the save is not turned into an error. A restock lost this way is read in the logs, not silently.
7. **Test each tier**: the Specification (unit), the listener (dispatches only when satisfied, after the flush), the event handler (dispatches the command), the command handler (the list is published by Mercure and reindexed), the use case and the service.

Example: `api/modules/grocery/src/Doctrine/ProductStockListener.php`, `Specification/IsProductOutOfStock.php`, `EventSubscriber/RestockOnProductOutOfStock.php`.
