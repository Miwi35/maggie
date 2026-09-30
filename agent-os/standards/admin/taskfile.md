# Admin Task Reference

All commands run in the `node` container via `docker compose exec`.

## Dependencies

| Task | What it does |
|---|---|
| `task admin:install` | `npm install` |
| `task admin:update` | `npm update` |
| `task admin:add -- <pkg>` | `npm install <pkg>` |

## Development & Build

| Task | What it does |
|---|---|
| `task admin:dev` | Start Vite dev server |
| `task admin:build` | Production build |

## Testing & Quality

| Task | What it does |
|---|---|
| `task admin:test` | Run tests |
| `task admin:lint` | ESLint |
| `task admin:typecheck` | TypeScript type check |
