# Standards pour le compteur d'indépendance

Référencés, jamais recopiés : le plan reste court et les standards restent la
source de vérité (`/inject-standards`, mode References).

| Standard | Pourquoi ici |
|---|---|
| @agent-os/standards/global/testing.md | Définition de « terminé » : ce que chaque unité touchée doit comme test, parcours e2e obligatoire |
| @agent-os/standards/api/entities.md | `Category` gagne `passiveIncome` : colonne, commandes Messenger, Mercure, document Elasticsearch |
| @agent-os/standards/api/mcp-tools.md | `get_independence_counter` en lecture, `passiveIncome` sur `manage_categories`, user via `McpUserContext` |
| @agent-os/standards/api/testing.md | Fixtures Alice, tests d'outil MCP, assertions Mercure et Elasticsearch |
| @agent-os/standards/global/real-time.md | La carte admin se rafraîchit sur les topics Mercure du dashboard |
| @agent-os/standards/admin/react-admin.md | Carte du dashboard et champ de formulaire React Admin |
| @agent-os/standards/admin/testing.md | Vitest co-localisé pour la carte |
| @agent-os/standards/mobile/android-app.md | DTO `@Serializable` à valeurs par défaut, carte Compose |
| @agent-os/standards/mobile/screen-tests.md | Le rendu de la carte se vérifie sur la JVM, pas sur un émulateur |
| @agent-os/standards/global/e2e-environment.md | Parcours Playwright, fixtures ancrées, `task e2e:*` |
| @agent-os/standards/global/worktree-checks.md | `task fix:all`, `task wt:test:*` depuis ce worktree |
