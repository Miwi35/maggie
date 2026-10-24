# Module boundaries

`api/modules/*` are independent business modules. `api/deptrac.yaml` states who may depend on whom; `task api:lint` (and CI's *API Lint*) fails on any other `use`.

```
Core ← Notification ← Calendar ← Finance
Core ← Grocery        Core ← Cookbook ──(catalogue only)──▶ Grocery
Core, Calendar, Notification, Cookbook, Grocery ← MealGrocery  (the bridge)
```

- **Core depends on no module.** What it needs from them it declares as an interface they implement, tagged by `#[AutoconfigureTag]` and read with `#[AutowireIterator]`: `Maggie\Core\Reminder\ReminderSourceInterface`, `Maggie\Core\E2e\Fixture\FixtureEffectsInterface`, `Maggie\Core\Home\HomeCardProviderInterface`. A fact core knows is an event on `event.bus` that the module handles (`UserSignedInWithGoogle` → calendar pulls its tasks).
- **Notification** depends on core only: `maggie:notification:check-reminders` runs the `ReminderSourceInterface` of every module (calendar: events, tasks; the bridge: shortages). A module with something to remind adds a source; nothing else to register.
- **Cookbook and Grocery know nothing of each other.** Everything that links a meal or a recipe to the grocery list lives in the bridge `modules/meal-grocery` (`Maggie\MealGrocery`): contributions, shortage alerts, generation, the `manage_meal_groceries` / `generate_grocery_list` MCP tools, the `recipe-need` and `meal-grocery-*` endpoints. It reacts to cookbook's events (`MealRemoved`, `MealRescheduled`, `MealGuestsChanged`, `MealPassed`, `DefaultGuestsChanged`) and implements the ports the two sides declare (`Cookbook\Port\MealGroceryPreviewInterface`, `Grocery\Port\RecipeDemandInterface`), aliased in the bridge's `config/services.yaml`.
- **One documented exception — the catalogue.** An `Ingredient` is a `Product` (single-table inheritance), so Cookbook may use Grocery's catalogue classes, listed in the `GroceryCatalogue` layer of `deptrac.yaml` (product, store, seasonal line, aisles, units, categories, exceptions, the product use cases and processors). Nothing else of Grocery. The rule that stops an ingredient's deletion while a recipe uses it (`IngredientDeletionGuard`) therefore lives in the bridge, not in Cookbook: it plugs into Grocery's `ProductDeletionGuard`, which is outside the catalogue.
- A new bundle under `modules/` needs a layer and a ruleset line in `deptrac.yaml`, its `modules/<name>/src` in `discovery.scan_dirs` of `config/packages/mcp.yaml` (check with a real `tools/list`), and its suite in `phpunit.dist.xml`.
- A domain event the bridge needs but cannot get from the entity once deleted (cascade) is captured before the flush by a Doctrine `onFlush` listener of the bridge (`MealRemovalLedger`), never by making the module pass the other's data in the event.
- Never add a violation to an ignore list to go green: move the code to the bridge, or declare a port.
