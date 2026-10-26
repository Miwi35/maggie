<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Message;

use Maggie\Cookbook\State\RecipeStepsPayload;

/** @phpstan-import-type StepInput from RecipeStepsPayload */
final readonly class CreateRecipeCommand
{
    /**
     * @param string[]                                                                                                                   $tags
     * @param array<array{quantity: float, unit: string, ingredientId?: string, ciqualAlimCode?: string, pieceWeightGrams?: float}>|null $ingredients
     * @param list<array{id: string}|array{name: string}>|null                                                                           $utensils    by id or by name, the whole list
     * @param list<StepInput>|null                                                                                                       $steps       the whole list, in order
     */
    public function __construct(
        public string $userId,
        public string $name,
        public int $servings = 4,
        public array $tags = [],
        public ?string $notes = null,
        public ?array $ingredients = null,
        public ?array $utensils = null,
        public ?array $steps = null,
    ) {
    }
}
