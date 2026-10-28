# Add a business effect on a state change

**When**: something must happen *because* an entity changed state (ran out, completed, moved, was removed) — whichever door saved it (HTTP, MCP, sync, console). Not when the user asks for the effect directly (→ `api-write-use-case.md`). A date that arrives (no changeset) → console command in place of the listener, see « Temporal facts » in the standard.
**Reference**: `api/modules/grocery/src/Doctrine/ProductStockListener.php`, `api/modules/grocery/src/Specification/IsProductOutOfStock.php`, `api/modules/grocery/src/EventSubscriber/RestockOnProductOutOfStock.php` → `UseCase/RestockProduct.php` (MAG-366). Event raised in one module, handled in another: `core/src/Doctrine/UserLocationListener.php` → `calendar/src/EventSubscriber/ReplanTripsOnUserLocationChanged.php`.

## Steps

1. `api/modules/<module>/src/Specification/<Is|Has><Transition>.php` — `final readonly class`, constructor takes the changeset (`array<string, array{0: mixed, 1: mixed}>`), `isSatisfiedBy(<Entity>): bool`. Before value may be the column string or `null` (creation).
2. `api/modules/<module>/src/Event/<Noun><PastTense>[Event].php` — `final readonly class`, identifiers only (strings); a snapshot of plain values only when a cascade erases the data.
3. `api/modules/<module>/src/Doctrine/<Noun><Concern>Listener.php` — `#[AsDoctrineListener(event: Events::preFlush)]`, `onFlush`, `postFlush`; inject `#[Autowire(service: 'event.bus')] MessageBusInterface $eventBus` and `LoggerInterface`. `preFlush` empties the pending list; `onFlush` notes `[entity, changeSet]` from insertions + updates; `postFlush` copies and empties the list, asks the Specification, dispatches the event in a `try/catch (\Throwable)` that logs `error` and swallows.
4. `api/modules/<module>/src/EventSubscriber/<Effect>On<Event>.php` — `#[AsMessageHandler(bus: 'event.bus')]`, inject `#[Autowire(service: 'messenger.bus.default')] MessageBusInterface $commandBus`, `__invoke(<Event>)` dispatches one command. No logic.
5. The command, its handler, use case and service: `api-write-use-case.md`. The use case re-checks the rule, so a second door or a replay never applies the effect twice.
6. `api/config/packages/messenger.yaml` → `routing`: the event **and** the command, `sync`.
7. Across modules: the handler lives in the module that reacts, and only in a direction `api/deptrac.yaml` allows (core events → any module; cookbook ↔ grocery only through `meal-grocery`).

## Tests owed

- `tests/Specification/<Spec>Test.php` (`TestCase`): each transition satisfied, re-save of the same state and the reverse not (`IsProductOutOfStockTest`).
- `tests/Doctrine/<Listener>Test.php` (`KernelTestCase`): event dispatched only when satisfied, after the flush, creation included; nothing on another change; a failed flush sends nothing later; a failing event does not undo the save (`ProductStockListenerTest`).
- `tests/EventSubscriber/<Handler>Test.php`: the event dispatches the command (`RestockOnProductOutOfStockTest`).
- `tests/MessageHandler/<Command>HandlerTest.php` + `tests/UseCase/…Test.php`: the write, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()`, idempotence (`RestockProductHandlerTest`, `RestockProductTest`).
- Run: `task wt:test:api -- --filter '<Spec>Test|<Listener>Test|<Handler>Test|<Command>HandlerTest'`.

## Standards

- `agent-os/standards/backend/domain-events.md` (the chain, buses, rules 1-8)
- `agent-os/standards/backend/projection.md`
- `agent-os/standards/backend/module-boundaries.md`

## Pitfalls

- Dispatching in `onFlush` corrupts the unit of work; reading the changeset in `postFlush` finds nothing. Note in `onFlush`, dispatch in `postFlush`.
- A handler that flushes re-enters `postFlush`: copy and empty the pending list before dispatching, or the event fires in a loop.
- Calling the effect from the use case or the MCP tool "because it is simpler" misses the other doors — the effect must hang on the transition (owner's rule).
- An event carrying an entity breaks once async: identifiers only.
- `ReplanTripsOnUserLocationChanged` reads repositories and evaluates a Specification inside the event handler: do not copy that part — the rule belongs in the Specification/listener or the use case.
