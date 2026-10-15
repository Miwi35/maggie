<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Uid\Ulid;

/**
 * Reads the `utensils` of a recipe request body (MAG-489).
 *
 * A utensil is named by its id — a ULID, an IRI, or an object with `@id` or
 * `id`, as the API serves it — or by its name, as a string or an object with
 * `name` alone. A name the catalogue does not have yet creates the utensil.
 */
final class RecipeUtensilsPayload
{
    /**
     * @param array<string, mixed> $context
     *
     * @return list<array{id: string}|array{name: string}>|null null when the body has no `utensils`
     */
    public static function fromContext(array $context): ?array
    {
        $request = $context['request'] ?? null;
        if (!$request instanceof Request) {
            return null;
        }

        $body = json_decode($request->getContent(), true);
        if (!\is_array($body) || !\array_key_exists('utensils', $body)) {
            return null;
        }

        if (!\is_array($body['utensils']) || !array_is_list($body['utensils'])) {
            throw new BadRequestHttpException('utensils must be a list.');
        }

        return self::fromList($body['utensils']);
    }

    /**
     * The same reading, for a list that does not come from a request (MCP).
     *
     * @param list<mixed> $items
     *
     * @return list<array{id: string}|array{name: string}>
     */
    public static function fromList(array $items): array
    {
        $refs = [];
        foreach ($items as $item) {
            $refs[] = self::reference($item);
        }

        return $refs;
    }

    /** @return array{id: string}|array{name: string} */
    private static function reference(mixed $item): array
    {
        if (\is_array($item)) {
            $id = $item['@id'] ?? $item['id'] ?? null;
            if (\is_string($id) && '' !== $id) {
                return ['id' => self::idOf($id)];
            }
            $item = $item['name'] ?? null;
            if (!\is_string($item)) {
                throw new BadRequestHttpException('Each utensil object must have an id or a name.');
            }
        }

        if (!\is_string($item) || '' === trim($item)) {
            throw new BadRequestHttpException('Each utensil must be an id or a non-empty name.');
        }
        if (mb_strlen(trim($item)) > 255) {
            throw new BadRequestHttpException('A utensil name is 255 characters at most.');
        }

        if (str_starts_with($item, '/api/utensils/') || Ulid::isValid($item)) {
            return ['id' => self::idOf($item)];
        }

        return ['name' => trim($item)];
    }

    private static function idOf(string $id): string
    {
        // "/api/utensils/01HXYZ..." → "01HXYZ..."
        $parts = explode('/', rtrim($id, '/'));

        return end($parts);
    }
}
