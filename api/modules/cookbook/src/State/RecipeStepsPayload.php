<?php

declare(strict_types=1);

namespace Maggie\Cookbook\State;

use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Uid\Ulid;

/**
 * Reads the `steps` of a recipe request body (MAG-490), the whole list, in
 * order: the first one is step 1.
 *
 * A step is `{text, durationMinutes?, background?, ingredientIds?, utensils?,
 * waitsFor?}`. It is also accepted as the API serves it, so a client can send
 * back what it read: `ingredients` as `[{id, name}]`, and `id` and `position`,
 * which are ignored — the position is the place in the list.
 *
 * An ingredient is named by its id (ULID, IRI or object with `id`) or by its
 * name in the recipe; a utensil as {@see RecipeUtensilsPayload} reads it.
 * `waitsFor` lists the positions of earlier steps.
 *
 * @phpstan-type StepIngredientRef array{id: string}|array{name: string}
 * @phpstan-type StepUtensilRef array{id: string}|array{name: string}
 * @phpstan-type StepInput array{text: string, durationMinutes: int|null, background: bool, ingredients: list<StepIngredientRef>, utensils: list<StepUtensilRef>, waitsFor: list<int>}
 */
final class RecipeStepsPayload
{
    /**
     * @param array<string, mixed> $context
     *
     * @return list<StepInput>|null null when the body has no `steps`
     */
    public static function fromContext(array $context): ?array
    {
        $request = $context['request'] ?? null;
        if (!$request instanceof Request) {
            return null;
        }

        $body = json_decode($request->getContent(), true);
        if (!\is_array($body) || !\array_key_exists('steps', $body)) {
            return null;
        }

        return self::fromList($body['steps']);
    }

    /**
     * The same reading, for a list that does not come from a request (MCP).
     *
     * @return list<StepInput>
     *
     * @throws BadRequestHttpException
     */
    public static function fromList(mixed $items): array
    {
        if (!\is_array($items) || !array_is_list($items)) {
            throw new BadRequestHttpException('steps must be a list.');
        }

        $steps = [];
        foreach ($items as $index => $item) {
            $steps[] = self::step($item, $index + 1);
        }

        return $steps;
    }

    /** @return StepInput */
    private static function step(mixed $item, int $position): array
    {
        if (!\is_array($item)) {
            throw new BadRequestHttpException("Step {$position} must be an object.");
        }

        $text = $item['text'] ?? null;
        if (!\is_string($text) || '' === trim($text)) {
            throw new BadRequestHttpException("Step {$position} needs a text.");
        }

        $duration = $item['durationMinutes'] ?? null;
        if (null !== $duration && (!\is_int($duration) || $duration <= 0)) {
            throw new BadRequestHttpException("Step {$position}: durationMinutes must be a whole number of minutes greater than 0.");
        }

        $background = $item['background'] ?? false;
        if (!\is_bool($background)) {
            throw new BadRequestHttpException("Step {$position}: background must be true or false.");
        }
        if ($background && null === $duration) {
            throw new BadRequestHttpException("Step {$position}: a step runs in the background only with a duration.");
        }

        return [
            'text' => trim($text),
            'durationMinutes' => $duration,
            'background' => $background,
            'ingredients' => self::ingredients($item['ingredientIds'] ?? $item['ingredients'] ?? [], $position),
            'utensils' => self::utensils($item['utensils'] ?? [], $position),
            'waitsFor' => self::waitsFor($item['waitsFor'] ?? [], $position),
        ];
    }

    /** @return list<StepIngredientRef> */
    private static function ingredients(mixed $items, int $position): array
    {
        if (!\is_array($items) || !array_is_list($items)) {
            throw new BadRequestHttpException("Step {$position}: ingredientIds must be a list.");
        }

        $refs = [];
        foreach ($items as $item) {
            if (\is_array($item)) {
                $id = $item['@id'] ?? $item['id'] ?? null;
                $item = \is_string($id) && '' !== $id ? $id : ($item['name'] ?? null);
            }
            if (!\is_string($item) || '' === trim($item)) {
                throw new BadRequestHttpException("Step {$position}: each ingredient must be an id or the name of one of the recipe's ingredients.");
            }

            $refs[] = str_starts_with($item, '/api/') || Ulid::isValid($item)
                ? ['id' => self::idOf($item)]
                : ['name' => trim($item)];
        }

        return $refs;
    }

    /** @return list<StepUtensilRef> */
    private static function utensils(mixed $items, int $position): array
    {
        if (!\is_array($items) || !array_is_list($items)) {
            throw new BadRequestHttpException("Step {$position}: utensils must be a list.");
        }

        return RecipeUtensilsPayload::fromList($items);
    }

    /** @return list<int> */
    private static function waitsFor(mixed $items, int $position): array
    {
        if (!\is_array($items) || !array_is_list($items)) {
            throw new BadRequestHttpException("Step {$position}: waitsFor must be a list of step positions.");
        }

        $positions = [];
        foreach ($items as $item) {
            if (!\is_int($item) || $item < 1 || $item >= $position) {
                throw new BadRequestHttpException("Step {$position} can only wait for an earlier step: waitsFor lists positions from 1 to ".($position - 1).'.');
            }
            $positions[$item] = $item;
        }
        sort($positions);

        return $positions;
    }

    private static function idOf(string $id): string
    {
        // "/api/ingredients/01HXYZ..." → "01HXYZ..."
        $parts = explode('/', rtrim($id, '/'));

        return end($parts);
    }
}
