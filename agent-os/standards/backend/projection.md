# Projection — Mercure and Elasticsearch

A handler **never publishes and never indexes**. `ProjectionMiddleware` (on `messenger.bus.default`) announces everything a message changed, to the owner of each row, once the message is done.

## How

```
root message → ProjectionMiddleware::begin()
  handler … $em->flush() (as many as needed; nested commands included)
    └─ WorkCollector (Doctrine onFlush/postFlush): one Change per row — inserted / updated (+ properties) / deleted
  handler returns → the returned MercurePublishable is added (MercureActionPayload replaces its payload)
→ project(): for each Change, Mercure Update(s) + IndexDocumentCommand / DeleteDocumentCommand
```

- **Unit of work, not return value.** A store, a product and a list created on the way to a grocery line are all announced; the handler returns whatever it likes.
- **Once, at the root.** A nested command's changes are folded into its parent's frame; a row touched twice is published once (class + id), insert wins over update, delete wins over everything.
- **After the handler.** Nothing leaves while the handler runs. A handler that throws *after* a flush still has its committed rows projected (they exist); a flush that failed projects nothing.
- **To the owner of the row**, never to whoever is logged in (`Security` is empty in a worker): `OwnedByUserInterface`, `OwnedThroughInterface`, or the `User` itself. A row with no owner is not published.
- **Payload**: insert → full; update → differential (the changed Doctrine properties, `MercurePayloadFilterTrait`); delete → `{"deleted": true}`. A class and its published ancestors (Meal/Event, Ingredient/Product) each get their topic.
- **Index**: every `IndexableInterface` row is reindexed; a removed one is deindexed from its indices. Same real class, never a Doctrine proxy's.

## Aggregate roots

A child that is only shown through its parent (a grocery line on its list, a recipe line on its recipe) carries `#[AggregateRoot('groceryList')]` — the name of the relation to the root. Any change to the child touches the root, published with the inverse collection as changed property. Do not broadcast the root by hand.

## Rules

- No `HubInterface`, `EntityBroadcaster` or `IndexDocumentCommand` in a handler, use case or service of the bus flow. `ProjectionArchitectureTest` lists what still does it (`TO_MIGRATE`): the list only shrinks.
- A change made **outside** the bus (console command, Doctrine listener without message, controller) is not projected: dispatch a command, or keep the explicit call and list it in `TO_MIGRATE`.
- New entity: implement `MercurePublishable` / `IndexableInterface` and an owner contract; nothing to register.
- Test a handler's projection by dispatching its command on the bus with nobody logged in, and assert `assertMercurePublishedOnce`, `assertMercureDeletePublished`, `assertElasticsearchIndexDispatchedFor` (`MercureAssertionTrait`, `ElasticsearchAssertionTrait`). Reference: `ProjectionMiddlewareTest`, `GroceryProjectionTest`, `MealProjectionTest`.
- A failed publication is logged and swallowed (`critical` for an unsignable `MERCURE_JWT_SECRET`): the write is done and a worker would retry the command.
