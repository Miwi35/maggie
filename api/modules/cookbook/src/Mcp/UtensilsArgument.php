<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp;

use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\Utensil;
use Maggie\Cookbook\State\RecipeUtensilsPayload;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * The `utensils` of the recipe tools (MAG-489): a JSON array, like
 * `ingredients`, read by the same rules as the API body.
 */
final class UtensilsArgument
{
    /**
     * @return list<array{id: string}|array{name: string}>|null
     *
     * @throws BadRequestHttpException the argument is not a JSON array of ids or names
     */
    public static function parse(?string $utensils): ?array
    {
        if (null === $utensils) {
            return null;
        }

        try {
            $list = json_decode($utensils, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new BadRequestHttpException('utensils must be a JSON array of utensil ids or names.');
        }
        if (!\is_array($list) || !array_is_list($list)) {
            throw new BadRequestHttpException('utensils must be a JSON array of utensil ids or names.');
        }

        return RecipeUtensilsPayload::fromList($list);
    }

    /** @return list<array{id: string, name: string}> */
    public static function describe(Recipe $recipe): array
    {
        return array_map(
            static fn (Utensil $utensil) => ['id' => (string) $utensil->getId(), 'name' => $utensil->getName()],
            $recipe->getUtensils(),
        );
    }
}
