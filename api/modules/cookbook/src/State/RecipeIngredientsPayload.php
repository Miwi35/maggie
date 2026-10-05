<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use Maggie\Grocery\Enum\Unit;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * Reads the `ingredients` lines of a recipe request body.
 *
 * A line is accepted in the shape the API itself serves (`ingredient` as an
 * embedded object, plus `ciqualAlimCode`), so a client can send back what it
 * read, as well as in the shape of a write (`ingredient` as an IRI).
 */
final class RecipeIngredientsPayload
{
    /**
     * @param array<string, mixed> $context
     *
     * @return list<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}>|null
     */
    public static function fromContext(array $context): ?array
    {
        $request = $context['request'] ?? null;
        if (!$request instanceof Request) {
            return null;
        }

        $body = json_decode($request->getContent(), true);
        if (!\is_array($body) || !isset($body['ingredients']) || !\is_array($body['ingredients'])) {
            return null;
        }

        $lines = [];
        foreach ($body['ingredients'] as $item) {
            if (!\is_array($item)) {
                throw new BadRequestHttpException('Each ingredient must be an object.');
            }
            $lines[] = self::line($item);
        }

        return $lines;
    }

    /**
     * @param array<string, mixed> $item
     *
     * @return array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string}
     */
    private static function line(array $item): array
    {
        $quantity = $item['quantity'] ?? null;
        if (!is_numeric($quantity) || (float) $quantity <= 0) {
            throw new BadRequestHttpException('Each ingredient quantity must be a number greater than 0.');
        }

        $unit = $item['unit'] ?? Unit::Gram->value;
        if (!\is_string($unit) || null === Unit::tryFrom($unit)) {
            throw new BadRequestHttpException('Each ingredient unit must be a known unit.');
        }

        $line = ['quantity' => (float) $quantity, 'unit' => $unit];

        $ingredientId = self::ingredientId($item['ingredient'] ?? $item['ingredientId'] ?? null);
        if (null !== $ingredientId) {
            $line['ingredientId'] = $ingredientId;
        }
        if (isset($item['ciqualAlimCode']) && \is_string($item['ciqualAlimCode']) && '' !== $item['ciqualAlimCode']) {
            $line['ciqualAlimCode'] = $item['ciqualAlimCode'];
        }

        return $line;
    }

    private static function ingredientId(mixed $ingredient): ?string
    {
        if (\is_array($ingredient)) {
            $ingredient = $ingredient['@id'] ?? $ingredient['id'] ?? null;
        }
        if (!\is_string($ingredient) || '' === $ingredient) {
            return null;
        }

        // "/api/ingredients/01HXYZ..." → "01HXYZ..."
        $parts = explode('/', rtrim($ingredient, '/'));

        return end($parts);
    }
}
