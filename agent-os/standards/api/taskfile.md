# API Task Reference

All commands run in the `php` container via `docker compose exec`.

## Composer

| Task | What it does |
|---|---|
| `task api:install` | `composer install` |
| `task api:update` | `composer update` |
| `task api:require -- <pkg>` | `composer require` |
| `task api:validate` | Validate composer.json |
| `task api:dump-autoload` | Regenerate autoloader |

## Symfony Console

`task api:console -- <command>` — runs any `bin/console` command.

Common:
- `cache:clear`
- `doctrine:migrations:migrate --no-interaction`
- `doctrine:migrations:diff`
- `doctrine:fixtures:load --no-interaction`
- `doctrine:mapping:info`
- `doctrine:schema:validate`
- `debug:container --tag=mcp.tool`
- `debug:router --show-controllers`

## Testing & Quality

| Task | What it does |
|---|---|
| `task api:test` | PHPUnit |
| `task api:test:coverage` | PHPUnit + HTML coverage |
| `task api:phpstan` | Static analysis |
| `task api:lint` | All linting (runs phpstan) |
