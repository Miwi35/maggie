# Standards — Salaire irrégulier et trésorerie

Les standards sont **référencés**, pas copiés : une copie divergerait du jour au lendemain. Index : `agent-os/standards/index.yml`, injection à la demande par `/inject-standards`.

## Toujours

| Standard | Pourquoi il s'applique ici |
|---|---|
| @agent-os/standards/global/testing.md | La définition de « terminé ». Chaque tâche du plan nomme les tests qu'elle doit, et chaque exemption (`N/A — …`) est écrite dans `plan.md` avec sa raison. Les lectures pures de ce projet — salaire de référence, avances, trésorerie — en ont plusieurs : elles n'écrivent rien, donc ni Mercure ni Elasticsearch. |
| @agent-os/standards/global/agent-guard-rails.md | Trois migrations additives, aucune destructive, aucun `policy.yaml` ni chemin `infra/`. Le découpage en onze tickets existe pour que chacun tienne sous 800 lignes hors tests : une tâche qui déborde se re-découpe, elle ne se fait pas valider à la main. |
| @agent-os/standards/global/worktree-checks.md | `task fix:all` avant chaque push, `task wt:up` puis `task wt:test:api -- --testsuite Finance` pour la boucle de test, `task wt:down` avant le PR. |

## API

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/api/entities.md | `TreasurySettings` est une entité neuve (ULID, `MercurePublishable`, `OwnedByUserInterface`, commande Messenger + handler + processor, migration). `Transaction` gagne deux auto-références `ManyToOne` nullables en `ON DELETE SET NULL`, `Category` un booléen indexé. |
| @agent-os/standards/api/mcp-tools.md | Six outils ou extensions d'outil : écriture par le bus, lecture par le dépôt, `McpUserContext::requireUser()`, `MissingMcpUserException` attrapée, filtrage par utilisateur. **Un module absent de `discovery.scan_dirs` dans `api/config/packages/mcp.yaml` a ses outils silencieusement masqués** — `finance` y est déjà, mais le `tools/list` réel se vérifie quand même. |
| @agent-os/standards/api/testing.md | 401, 400, happy path avec état DB, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()`. `resetMercure()` et `resetAsyncTransport()` dans `setUp()`. Référence : `api/modules/grocery/tests/Mcp/GroceryToolsTest.php`. |
| @agent-os/standards/global/real-time.md | Marquer un virement interne, ouvrir une avance, la solder : trois changements que le second onglet doit voir sans rechargement. |

## Admin et mobile

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/admin/react-admin.md | Badge et correction dans la liste de transactions, pages avances et trésorerie, hooks sur les endpoints nouveaux, abonnement Mercure. |
| @agent-os/standards/admin/testing.md | Par composant : rendu initial, l'interaction pour laquelle il existe, état d'erreur ou vide. |
| @agent-os/standards/mobile/android-app.md | Écrans Compose, `MaggieApiService`, dépôts, Koin, liens `maggie://finance/…`. **L'autocomplétion mobile ouvre une recherche plein écran, jamais une liste déroulante en ligne** — vaut pour le choix de la contrepartie d'un virement. |
| @agent-os/standards/mobile/testing.md | MockK, une transition d'état par test sur chaque ViewModel. |
| @agent-os/standards/mobile/screen-tests.md | Ce qui se vérifie en Compose sur la JVM (badge, bandeau de découvert) et ce qui demande Maestro. |

## Agent

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/agent/architecture.md | La proaction « salaire en retard » (tâche 12) passe par la file de proaction existante, pas par un chemin neuf. |
| @agent-os/standards/agent/testing.md | pytest en mode async, HTTP simulé avec respx, fixtures de `conftest`. |

## E2E

| Standard | Pourquoi |
|---|---|
| @agent-os/standards/global/e2e-environment.md | Trois journées Playwright et une Maestro, sur la pile e2e isolée, avec `LLM_PROVIDER=fake` et deux scénarios nouveaux dans `agent/fixtures/fake-llm/`. **Sans scénario correspondant, Maggie répond `[fake-llm] aucun scénario…`** : le scénario s'écrit avant l'étape. Le jugement — bon outil, ton, proposer sans exécuter — se vérifie à part par `task e2e:eval`. |

## Points de vigilance propres à ce projet

- **`isFoo()` se sérialise `foo`.** `Category::$salary` se déclare `$salary` avec la colonne `is_salary` et l'accesseur `isSalary()`, comme `$passiveIncome` / `is_passive_income` / `isPassiveIncome()` dans [MAG-46](https://linear.app/meven/issue/MAG-46) — un champ nommé `isSalary` se lirait `salary` en REST et `isSalary` sur Mercure, le désaccord que `isCushion` paie déjà, et que `DtoContractTest` surveille côté mobile.
- **Jamais de `Patch` API Platform sur un `uriTemplate` sans identifiant.** `TreasurySettings` est une ressource « me » : l'écriture passe par un contrôleur REST dédié qui n'applique que les champs présents, sinon 500 — règle tenue par `MeOperationContractTest`.
- **`bookedAt` est une date à 00:00.** Toute fenêtre glissante s'ouvre à minuit, comme `MeasureMonthlyLifestyle::sampleWindow()`, sinon elle décale d'un jour à chaque bout et le chiffre bouge dans la journée.
- **Les collections indexables sont servies par Elasticsearch** et ne retombent sur Doctrine que sur exception : un index périmé rend une liste vide en silence. `task api:console -- app:elasticsearch:status --check` après les migrations qui ajoutent un champ indexé.
- **Les fichiers de contrat (`api/contract/`) échouent quand la forme de l'API change, et c'est le but.** Lire l'échec, puis `UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`, et commiter le diff pour qu'un relecteur le voie.
